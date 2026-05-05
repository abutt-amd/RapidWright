/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.rapidsa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses an accelerator JSON config (as emitted by pytorch-bridge/bridge.py)
 * into an ordered list of layer descriptors. Pads tile dimensions up to
 * multiples of {@link #GEMM_DIM} so each layer's tileIn/tileOut maps
 * cleanly onto an integer number of GEMM tiles. Pad amounts are retained
 * on each {@link LinearLayer} so downstream code can mask the unused lanes.
 */
public class AccelConfig {

    /** GEMMTile per-tile dimension (PEs along width/height of a single tile). */
    public static final int GEMM_DIM = 4;

    public enum LayerKind { LINEAR, RELU }

    public static abstract class Layer {
        public final LayerKind kind;
        public final String dtype;
        protected Layer(LayerKind kind, String dtype) {
            this.kind = kind;
            this.dtype = dtype;
        }
    }

    public static final class LinearLayer extends Layer {
        public final int in;
        public final int out;
        public final int tileIn;
        public final int tileOut;
        public final int paddedTileIn;
        public final int paddedTileOut;
        public final boolean bias;

        public LinearLayer(int in, int out, int tileIn, int tileOut,
                           boolean bias, String dtype) {
            super(LayerKind.LINEAR, dtype);
            this.in = in;
            this.out = out;
            this.tileIn = tileIn;
            this.tileOut = tileOut;
            this.paddedTileIn = roundUp(tileIn, GEMM_DIM);
            this.paddedTileOut = roundUp(tileOut, GEMM_DIM);
            this.bias = bias;
        }

        public int padIn()  { return paddedTileIn  - tileIn;  }
        public int padOut() { return paddedTileOut - tileOut; }
        public int nRows()  { return paddedTileIn  / GEMM_DIM; }
        public int nCols()  { return paddedTileOut / GEMM_DIM; }
    }

    public static final class ReluLayer extends Layer {
        public final int size;
        public final int tile;

        public ReluLayer(int size, int tile, String dtype) {
            super(LayerKind.RELU, dtype);
            this.size = size;
            this.tile = tile;
        }
    }

    private final List<Layer> layers;

    private AccelConfig(List<Layer> layers) {
        this.layers = Collections.unmodifiableList(layers);
    }

    public List<Layer> getLayers() { return layers; }

    public List<LinearLayer> getLinearLayers() {
        List<LinearLayer> result = new ArrayList<>();
        for (Layer l : layers) {
            if (l instanceof LinearLayer) result.add((LinearLayer) l);
        }
        return result;
    }

    public static AccelConfig parse(Path jsonPath) throws IOException {
        String text = new String(Files.readAllBytes(jsonPath));
        JSONArray arr = new JSONArray(text);
        List<Layer> parsed = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String type = obj.getString("type");
            String dtype = obj.getString("dtype");
            switch (type) {
                case "linear":
                    parsed.add(new LinearLayer(
                            obj.getInt("in"),
                            obj.getInt("out"),
                            obj.getInt("tile_in"),
                            obj.getInt("tile_out"),
                            obj.getBoolean("bias"),
                            dtype));
                    break;
                case "relu":
                    parsed.add(new ReluLayer(
                            obj.getInt("size"),
                            obj.getInt("tile"),
                            dtype));
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported layer type at index " + i + ": " + type);
            }
        }
        validate(parsed);
        return new AccelConfig(parsed);
    }

    private static void validate(List<Layer> layers) {
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("Accel config has no layers");
        }
        if (layers.get(0).kind != LayerKind.LINEAR) {
            throw new IllegalArgumentException(
                    "Accel config must start with a linear layer (got "
                            + layers.get(0).kind + ")");
        }
        if (layers.get(layers.size() - 1).kind != LayerKind.LINEAR) {
            throw new IllegalArgumentException(
                    "Accel config must end with a linear layer (got "
                            + layers.get(layers.size() - 1).kind
                            + " at index " + (layers.size() - 1) + ")");
        }

        for (int i = 0; i < layers.size(); i++) {
            Layer cur = layers.get(i);
            if (!"int8".equals(cur.dtype)) {
                throw new IllegalArgumentException(
                        "Layer " + i + ": only dtype=int8 is supported, got "
                                + cur.dtype);
            }
            if (i + 1 < layers.size()) {
                Layer nxt = layers.get(i + 1);
                if (cur.kind == nxt.kind) {
                    throw new IllegalArgumentException(
                            "Two consecutive " + cur.kind + " layers at indices "
                                    + i + " and " + (i + 1));
                }
                int curOut = (cur instanceof LinearLayer)
                        ? ((LinearLayer) cur).out
                        : ((ReluLayer) cur).size;
                int nxtIn = (nxt instanceof LinearLayer)
                        ? ((LinearLayer) nxt).in
                        : ((ReluLayer) nxt).size;
                if (curOut != nxtIn) {
                    throw new IllegalArgumentException(
                            "Dimension mismatch between layer " + i + " (out="
                                    + curOut + ") and layer " + (i + 1)
                                    + " (in=" + nxtIn + ")");
                }
            }
        }

        // Linear/Relu pair tile sizes must agree where both are present
        for (int i = 0; i + 1 < layers.size(); i++) {
            Layer a = layers.get(i);
            Layer b = layers.get(i + 1);
            if (a instanceof LinearLayer && b instanceof ReluLayer) {
                int aTileOut = ((LinearLayer) a).tileOut;
                int bTile = ((ReluLayer) b).tile;
                if (aTileOut != bTile) {
                    throw new IllegalArgumentException(
                            "Tile size mismatch: linear[" + i + "].tile_out="
                                    + aTileOut + " vs relu[" + (i + 1) + "].tile="
                                    + bTile);
                }
            }
        }
    }

    private static int roundUp(int value, int multiple) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "Cannot pad non-positive value " + value);
        }
        return ((value + multiple - 1) / multiple) * multiple;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: AccelConfig <accel.json>");
            System.exit(1);
        }
        AccelConfig cfg = parse(Paths.get(args[0]));
        System.out.println("Layers (" + cfg.layers.size() + "):");
        for (int i = 0; i < cfg.layers.size(); i++) {
            Layer l = cfg.layers.get(i);
            if (l instanceof LinearLayer) {
                LinearLayer ll = (LinearLayer) l;
                System.out.printf(
                        "  [%d] LINEAR  in=%d out=%d  tile_in=%d (padded %d, +%d) "
                                + "tile_out=%d (padded %d, +%d)  nRows=%d nCols=%d  bias=%s%n",
                        i, ll.in, ll.out,
                        ll.tileIn, ll.paddedTileIn, ll.padIn(),
                        ll.tileOut, ll.paddedTileOut, ll.padOut(),
                        ll.nRows(), ll.nCols(), ll.bias);
            } else {
                ReluLayer rl = (ReluLayer) l;
                System.out.printf("  [%d] RELU    size=%d tile=%d%n",
                        i, rl.size, rl.tile);
            }
        }
        System.out.println("Linear layer count: " + cfg.getLinearLayers().size());
    }
}
