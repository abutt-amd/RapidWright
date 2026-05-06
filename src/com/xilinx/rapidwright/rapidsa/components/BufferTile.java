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

package com.xilinx.rapidwright.rapidsa.components;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.design.tools.InlineFlopTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single-stage vertical pipeline buffer with the same physical footprint as
 * {@link GEMMTile}. Used as an SLR-crossing register: stamp two stacked
 * instances with the top one's {@code bot_out} feeding the bottom one's
 * {@code top_in}, place each in its own SLR, and the precompiled register
 * hop carries the signal across the SLR boundary at high frequency.
 */
public class BufferTile implements RapidComponent {

    private final int width;
    private final int dataBits;

    public BufferTile(int width, int dataBits) {
        this.width = width;
        this.dataBits = dataBits;
    }

    public int getWidth()    { return width; }
    public int getDataBits() { return dataBits; }

    @Override
    public String getComponentName() {
        return "BufferTile";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "buffer_tile.sv");
        lines.add("set_property generic {WIDTH=" + width + " DATA_BITS=" + dataBits + " } [current_fileset]");
        lines.add("set_property top buffer_tile [current_fileset]");
        return lines;
    }

    @Override
    public String getClkName() {
        return "clk";
    }

    @Override
    public String getResetName() {
        return null;
    }

    /** Same per-tile fabric region as {@link GEMMTile} so a BufferTile slots
     *  into the array geometry one-for-one. */
    @Override
    public PBlock getPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        return new PBlock(device,
                "DSP_X2Y398:DSP_X3Y405 SLICE_X112Y796:SLICE_X119Y811 " +
                        "IRI_QUAD_X72Y3212:IRI_QUAD_X73Y3275 DSP58_CPLX_X1Y398:DSP58_CPLX_X1Y405");
    }

    /** Wider PBlock for the SLR-crossing precompile, mirroring
     *  {@link GEMMTile#getSLRCrossingPBlock()} so the crossing artifact has
     *  enough headroom on both sides of the SLR boundary. */
    @Override
    public PBlock getSLRCrossingPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        return new PBlock(device,
                "RAMB18_X3Y400:RAMB18_X3Y407 " +
                        "DSP_X2Y398:DSP_X3Y405 " +
                        "IRI_QUAD_X65Y3212:IRI_QUAD_X78Y3275 " +
                        "DSP58_CPLX_X1Y398:DSP58_CPLX_X1Y405 " +
                        "RAMB36_X3Y200:RAMB36_X3Y203 " +
                        "SLICE_X104Y796:SLICE_X123Y811");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("top_in.* TOP");
        lines.add("top_in_valid.* TOP");
        lines.add("bot_out.* BOTTOM");
        lines.add("bot_out_valid.* BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }

    @Override
    public boolean shouldCompileSLRCrossing() {
        return true;
    }

    private static int countArrayPorts(EDIFPort[] ports, String baseName) {
        TreeMap<Integer, EDIFPort> indexed = new TreeMap<>();
        for (EDIFPort port : ports) {
            String key = port.getBusName();
            if (!key.startsWith(baseName + "[")) continue;
            String remainder = key.substring(baseName.length() + 1);
            int closeBracket = remainder.indexOf(']');
            if (closeBracket <= 0) continue;
            try {
                indexed.put(Integer.parseInt(remainder.substring(0, closeBracket)), port);
            } catch (NumberFormatException e) {
                // Bus range like "7:0" - skip
            }
        }
        return indexed.isEmpty() ? 0 : indexed.lastKey() + 1;
    }

    /** Top-half {@code bot_out[i]} feeds bottom-half {@code top_in[i]} across
     *  the SLR boundary. Same pattern as {@link GEMMTile}'s south->north and
     *  accum_outputs->accum_inputs crossings. */
    @Override
    public List<SLRCrossingConnection> getSLRCrossingConnections(Design d) {
        List<SLRCrossingConnection> connections = new ArrayList<>();
        EDIFPort[] ports = d.getTopEDIFCell().getPorts().toArray(new EDIFPort[0]);

        int dataCount = countArrayPorts(ports, "bot_out");
        for (int i = 0; i < dataCount; i++) {
            connections.add(new SLRCrossingConnection("bot_out[" + i + "]", "top_in[" + i + "]"));
        }
        int validCount = countArrayPorts(ports, "bot_out_valid");
        for (int i = 0; i < validCount; i++) {
            connections.add(new SLRCrossingConnection("bot_out_valid[" + i + "]", "top_in_valid[" + i + "]"));
        }
        return connections;
    }
}
