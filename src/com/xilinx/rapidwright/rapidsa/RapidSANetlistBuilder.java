/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.BufferTile;
import com.xilinx.rapidwright.rapidsa.components.EdgeBufferTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.ReluTile;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.util.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles precompiled component tiles into a complete systolic array netlist.
 *
 * Topology (nRows x nCols):
 *
 *                    WeightEB_x0  WeightEB_x1  ...  WeightEB_x{nCols-1}
 *
 *   InputEB_y0      tile_x0y0    tile_x1y0    ...  tile_x{nCols-1}y0
 *   InputEB_y1      tile_x0y1    tile_x1y1    ...  tile_x{nCols-1}y1
 *     ...             ...          ...               ...
 *   InputEB_y{nR-1} tile_x0y{N}  tile_x1y{N}  ...  tile_x{nCols-1}y{nRows-1}
 *
 *
 * Ingress and egress channel black boxes are attached after the base array
 * has been built and passed through ArrayBuilder.
 */
public class RapidSANetlistBuilder {

    private static final String SYNTH_DCP = "synth.dcp";

    // GEMM tile ports
    private static final String GEMM_NORTH_INPUTS       = "north_inputs";
    private static final String GEMM_NORTH_INPUTS_VALID  = "north_inputs_valid";
    private static final String GEMM_WEST_INPUTS         = "west_inputs";
    private static final String GEMM_WEST_INPUTS_VALID   = "west_inputs_valid";
    private static final String GEMM_EAST_OUTPUTS        = "east_outputs";
    private static final String GEMM_EAST_OUTPUTS_VALID  = "east_outputs_valid";
    private static final String GEMM_SOUTH_OUTPUTS       = "south_outputs";
    private static final String GEMM_SOUTH_OUTPUTS_VALID = "south_outputs_valid";
    private static final String GEMM_ACCUM_INPUTS        = "accum_inputs";
    private static final String GEMM_ACCUM_OUTPUTS       = "accum_outputs";
    private static final String GEMM_ACCUM_SHIFT         = "accum_shift";

    // Edge Buffer tile ports
    private static final String EB_S_DATA       = "s_data";
    private static final String EB_S_VALID      = "s_valid";
    private static final String EB_M_DATA       = "m_data";
    private static final String EB_M_VALID      = "m_valid";
    private static final String EB_S_WORD_INDEX = "s_word_index";
    private static final String EB_M_WORD_INDEX = "m_word_index";
    private static final String EB_RD_EN        = "rd_en";
    private static final String EB_RD_EN_OUT    = "rd_en_out";
    private static final String EB_DOUT         = "dout";
    private static final String EB_DOUT_VALID   = "dout_valid";

    // Drain tile ports
    private static final String DRAIN_FIFO_WR_EN = "fifo_wr_en";
    private static final String DRAIN_FIFO_DIN   = "fifo_din";
    private static final String DRAIN_M_TDATA    = "m_axis_downstream_tdata";
    private static final String DRAIN_M_TVALID   = "m_axis_downstream_tvalid";
    private static final String DRAIN_M_TREADY   = "m_axis_downstream_tready";
    private static final String DRAIN_S_TDATA    = "s_axis_upstream_tdata";
    private static final String DRAIN_S_TVALID   = "s_axis_upstream_tvalid";
    private static final String DRAIN_S_TREADY   = "s_axis_upstream_tready";

    // MM2S NOC channel ports (includes FSM)
    private static final String MM2S_M_DATA_A       = "m_data_a";
    private static final String MM2S_M_VALID_A      = "m_valid_a";
    private static final String MM2S_M_DATA_B       = "m_data_b";
    private static final String MM2S_M_VALID_B      = "m_valid_b";
    private static final String MM2S_DONE            = "done";
    private static final String MM2S_A_RD_EN         = "a_rd_en";
    private static final String MM2S_B_RD_EN         = "b_rd_en";
    private static final String MM2S_OUTPUT_WR_EN    = "output_wr_en";
    private static final String MM2S_SA_ACCUM_SHIFT  = "sa_accum_shift";
    private static final String MM2S_S2MM_START      = "s2mm_start";
    private static final String MM2S_S2MM_DONE       = "s2mm_done";

    // S2MM channel ports
    private static final String S2MM_S_DATA        = "s_data";
    private static final String S2MM_S_VALID       = "s_valid";
    private static final String S2MM_S_READY       = "s_ready";
    private static final String S2MM_START         = "s2mm_start";
    private static final String S2MM_DONE          = "s2mm_done";

    /**
     * Per-design state shared across one or more {@link #buildArraySegment}
     * calls: the migrated component cells, their shared clk/rst_n nets, and
     * the per-tile dimensions (numUnitsNorth/West etc.) which come from the
     * precompiled GEMM cell and so are global to the design.
     */
    private static class SegmentBuildContext {
        final Design design;
        final EDIFNetlist netlist;
        final EDIFCell topCell;
        final EDIFCell gemmCell;
        final EDIFCell weightEbCell;
        final EDIFCell inputEbCell;
        final EDIFCell drainCell;
        final RapidComponent gemmComponent;
        final RapidComponent weightEbComponent;
        final RapidComponent inputEbComponent;
        final RapidComponent drainComponent;
        final EDIFNet clkNet;
        final EDIFNet rstNNet;
        // Per-tile dims (constant for the GEMMTile(4,4) precompile).
        final int dataBits;
        final int accumBits;
        final int numUnitsNorth;
        final int numUnitsWest;
        final int eastCount;
        final int southCount;
        final int accumCount;
        final int ebDataWidth;

        SegmentBuildContext(Design design, EDIFCell gemmCell, EDIFCell weightEbCell,
                            EDIFCell inputEbCell, EDIFCell drainCell,
                            RapidComponent gemmComponent, RapidComponent weightEbComponent,
                            RapidComponent inputEbComponent, RapidComponent drainComponent,
                            EDIFNet clkNet, EDIFNet rstNNet) {
            this.design = design;
            this.netlist = design.getNetlist();
            this.topCell = netlist.getTopCell();
            this.gemmCell = gemmCell;
            this.weightEbCell = weightEbCell;
            this.inputEbCell = inputEbCell;
            this.drainCell = drainCell;
            this.gemmComponent = gemmComponent;
            this.weightEbComponent = weightEbComponent;
            this.inputEbComponent = inputEbComponent;
            this.drainComponent = drainComponent;
            this.clkNet = clkNet;
            this.rstNNet = rstNNet;
            this.dataBits = getPortWidth(gemmCell, GEMM_NORTH_INPUTS + "[0]");
            this.accumBits = getPortWidth(gemmCell, GEMM_ACCUM_INPUTS + "[0]");
            this.numUnitsNorth = countArrayPorts(gemmCell, GEMM_NORTH_INPUTS);
            this.numUnitsWest = countArrayPorts(gemmCell, GEMM_WEST_INPUTS);
            this.eastCount = countArrayPorts(gemmCell, GEMM_EAST_OUTPUTS);
            this.southCount = countArrayPorts(gemmCell, GEMM_SOUTH_OUTPUTS);
            this.accumCount = countArrayPorts(gemmCell, GEMM_ACCUM_INPUTS);
            this.ebDataWidth = getPortWidth(weightEbCell, EB_S_DATA);
        }
    }

    /**
     * Creates a fresh Design, migrates the four base component cells (GEMM,
     * WeightEB, InputEB, Drain) into its netlist, creates the global
     * top-level {@code clk}, {@code rst_n}, {@code done} ports, and seeds
     * the {@code clk} and {@code rst_n} nets with their top-level port-insts.
     * The returned context is ready for one or more {@link #buildArraySegment}
     * calls.
     */
    private static SegmentBuildContext prepareDesign(String designName, String partName,
                                                     String precompileDir) {
        // Component objects only carry per-tile dims used at netlist-build time
        // (the precompile artifact name is fixed). The (nCols, nRows) args
        // here pick the constructor signature; the loaded cell type is the
        // precompiled GEMMTile(4,4).
        RapidComponent gemmComponent = new GEMMTile(4, 4);
        RapidComponent weightEbComponent = new EdgeBufferTile(4, EdgeBufferTile.Type.WEIGHT);
        RapidComponent inputEbComponent = new EdgeBufferTile(4, EdgeBufferTile.Type.INPUT);

        EDIFCell gemmCell = loadComponentCell(precompileDir, gemmComponent);
        EDIFCell weightEbCell = loadComponentCell(precompileDir, weightEbComponent);
        EDIFCell inputEbCell = loadComponentCell(precompileDir, inputEbComponent);

        Design design = new Design(designName, partName);
        design.setAutoIOBuffers(false);
        EDIFNetlist netlist = design.getNetlist();

        netlist.migrateCellAndSubCells(gemmCell, true);
        netlist.migrateCellAndSubCells(weightEbCell, true);
        netlist.migrateCellAndSubCells(inputEbCell, true);

        gemmCell.makePrimitive();
        weightEbCell.makePrimitive();
        inputEbCell.makePrimitive();

        int accumCount = countArrayPorts(gemmCell, GEMM_ACCUM_INPUTS);
        RapidComponent drainComponent = new DrainTile(accumCount, 8);
        EDIFCell drainCell = loadComponentCell(precompileDir, drainComponent);
        netlist.migrateCellAndSubCells(drainCell, true);
        drainCell.makePrimitive();

        EDIFCell topCell = netlist.getTopCell();
        EDIFPort topClk = topCell.createPort("clk", EDIFDirection.INPUT, 1);
        EDIFPort topRstN = topCell.createPort("rst_n", EDIFDirection.INPUT, 1);
        topCell.createPort("done", EDIFDirection.OUTPUT, 1);

        EDIFNet clkNet = topCell.createNet("clk");
        clkNet.createPortInst(topClk);
        EDIFNet rstNNet = topCell.createNet("rst_n");
        rstNNet.createPortInst(topRstN);

        return new SegmentBuildContext(design, gemmCell, weightEbCell, inputEbCell, drainCell,
                gemmComponent, weightEbComponent, inputEbComponent, drainComponent,
                clkNet, rstNNet);
    }

    /**
     * Backward-compatible single-SA entry point. Creates a fresh Design and
     * builds one prefix-less SA segment with both an InputEB chain and a
     * Drain chain present. The resulting Design matches the pre-refactor
     * netlist exactly (instance names {@code tile_x{c}y{r}}, {@code
     * weight_eb_x{i}}, {@code input_eb_y{i}}, {@code drain_x{i}}; nets
     * {@code clk}/{@code rst_n}/{@code done}; right-edge east_outputs and
     * bottom-edge south_outputs exposed as top-level ports).
     */
    public static Design createSystolicArrayNetlist(int nRows, int nCols, String partName,
                                                    String precompileDir) {
        if (nRows <= 0 || nCols <= 0) {
            throw new IllegalArgumentException(
                    "Array dimensions must be positive: nRows=" + nRows + ", nCols=" + nCols);
        }
        SegmentBuildContext ctx = prepareDesign("systolic_array", partName, precompileDir);
        buildArraySegment(ctx, "", nRows, nCols, true, true, true);
        return ctx.design;
    }

    /**
     * Creates a multi-SA design by stamping out one SA segment per linear
     * layer in {@code layers}, all sharing one {@code clk}/{@code rst_n}
     * domain. Instance names are prefixed with {@code sa{i}_} to keep
     * segments uniquely addressable. Only SA[0] gets an InputEB chain
     * (its activation source is MM2S); only SA[last] gets a Drain chain
     * (its output goes to S2MM). Intermediate SA outputs (bottom-row
     * accum_outputs) and intermediate SA inputs (column-0 west_inputs)
     * are left dangling for {@link #attachReluBetweenSAs} to claim.
     *
     * Right-edge east_outputs and bottom-edge south_outputs are exposed
     * as top-level ports {@code sa{i}_east_outputs_row{r}[k]} etc. so they
     * remain inspectable per-segment.
     */
    public static Design createMultiSANetlist(java.util.List<AccelConfig.LinearLayer> layers,
                                              String partName, String precompileDir) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException(
                    "createMultiSANetlist requires at least one linear layer");
        }
        SegmentBuildContext ctx = prepareDesign("multi_sa", partName, precompileDir);
        int last = layers.size() - 1;
        for (int i = 0; i < layers.size(); i++) {
            AccelConfig.LinearLayer layer = layers.get(i);
            String prefix = "sa" + i + "_";
            boolean hasInputEB = (i == 0);
            boolean hasDrain = (i == last);
            // SA[0] uses standard orientation (activations from west via InputEB,
            // weights from north via WeightEB). SAs[1..] are "rotated":
            // activations enter via top-row north_inputs (driven by the prior
            // ReluTile), so we swap nRows/nCols so the array's column count
            // matches the upstream output width (paddedTileIn) rather than the
            // downstream output width (paddedTileOut).
            int nRows = (i == 0) ? layer.nRows() : layer.nCols();
            int nCols = (i == 0) ? layer.nCols() : layer.nRows();
            buildArraySegment(ctx, prefix, nRows, nCols,
                    hasInputEB, hasDrain, true);
        }
        return ctx.design;
    }

    /**
     * Returns the effective {@code nRows} for the SA at index {@code saIdx} in
     * a multi-SA chain, applying the SA[1..] dimension swap. Match
     * {@link #createMultiSANetlist} so callers of {@link #attachMM2SForSA}
     * and {@link #attachReluBetweenSAs} pass dims that agree with the built
     * netlist.
     */
    public static int effectiveNRows(AccelConfig.LinearLayer layer, int saIdx) {
        return (saIdx == 0) ? layer.nRows() : layer.nCols();
    }

    /**
     * Returns the effective {@code nCols} for the SA at index {@code saIdx}.
     * See {@link #effectiveNRows}.
     */
    public static int effectiveNCols(AccelConfig.LinearLayer layer, int saIdx) {
        return (saIdx == 0) ? layer.nCols() : layer.nRows();
    }

    /**
     * Builds one nRows x nCols SA segment inside the design held by {@code
     * ctx}. All instance names are prefixed with {@code prefix} so multiple
     * segments can coexist in one Design. When {@code hasInputEB} is false,
     * the InputEB chain is omitted and the column-0 GEMM tiles' west_inputs
     * are left disconnected (caller is expected to drive them). When {@code
     * hasDrain} is false, the Drain chain is omitted and the bottom-row GEMM
     * tiles' accum_outputs are left disconnected (caller is expected to
     * consume them). When {@code exposeEdgeOutputs} is true, the right-edge
     * east_outputs and bottom-edge south_outputs are bubbled up as
     * top-level ports.
     */
    private static void buildArraySegment(SegmentBuildContext ctx, String prefix,
                                          int nRows, int nCols,
                                          boolean hasInputEB, boolean hasDrain,
                                          boolean exposeEdgeOutputs) {
        if (nRows <= 0 || nCols <= 0) {
            throw new IllegalArgumentException(
                    "Segment dims must be positive: nRows=" + nRows + ", nCols=" + nCols);
        }
        EDIFCell topCell = ctx.topCell;
        EDIFNet clkNet = ctx.clkNet;
        EDIFNet rstNNet = ctx.rstNNet;
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, ctx.netlist);

        String gemmClk = ctx.gemmComponent.getClkName();
        String weightEbClk = ctx.weightEbComponent.getClkName();
        String weightEbRst = ctx.weightEbComponent.getResetName();
        String inputEbClk = ctx.inputEbComponent.getClkName();
        String inputEbRst = ctx.inputEbComponent.getResetName();
        String drainClk = ctx.drainComponent.getClkName();
        String drainRst = ctx.drainComponent.getResetName();

        int dataBits = ctx.dataBits;
        int accumBits = ctx.accumBits;
        int numUnitsNorth = ctx.numUnitsNorth;
        int numUnitsWest = ctx.numUnitsWest;
        int eastCount = ctx.eastCount;
        int southCount = ctx.southCount;
        int accumCount = ctx.accumCount;
        int ebDataWidth = ctx.ebDataWidth;

        // Instance creation.
        EDIFCellInst[][] gemmInsts = new EDIFCellInst[nRows][nCols];
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                gemmInsts[r][c] = createBlackBox(topCell,
                        prefix + "tile_x" + c + "y" + r, ctx.gemmCell);

        // WeightEB chain orientation depends on the SA's orientation:
        //   - Standard SA (hasInputEB == true): per-column, sits above the
        //     array, drives top-row tiles' north_inputs. Count = nCols. Cell
        //     type comes from the WeightEdgeBuffer precompile.
        //   - Rotated SA  (hasInputEB == false): per-row, sits to the RIGHT
        //     of the array (since setFlipPlacementHorizontally(true) puts
        //     "west" pins on the right), drives col-0 tiles' west_inputs.
        //     Count = nRows. Naming uses {y} index for grep-ability. Cell
        //     type comes from the InputEdgeBuffer precompile (functionally
        //     identical RTL but a different precompile shape that's better
        //     suited for right-of-array placement).
        int weightEbCount = hasInputEB ? nCols : nRows;
        EDIFCell weightEbCellForThisSegment = hasInputEB ? ctx.weightEbCell : ctx.inputEbCell;
        EDIFCellInst[] weightEbInsts = new EDIFCellInst[weightEbCount];
        for (int i = 0; i < weightEbCount; i++) {
            String ebName = hasInputEB ? ("weight_eb_x" + i) : ("weight_eb_y" + i);
            weightEbInsts[i] = createBlackBox(topCell, prefix + ebName, weightEbCellForThisSegment);
        }

        EDIFCellInst[] inputEbInsts = hasInputEB ? new EDIFCellInst[nRows] : null;
        if (hasInputEB) {
            for (int i = 0; i < nRows; i++)
                inputEbInsts[i] = createBlackBox(topCell,
                        prefix + "input_eb_y" + i, ctx.inputEbCell);
        }

        EDIFCellInst[] drainInsts = hasDrain ? new EDIFCellInst[nCols] : null;
        if (hasDrain) {
            for (int i = 0; i < nCols; i++)
                drainInsts[i] = createBlackBox(topCell,
                        prefix + "drain_x" + i, ctx.drainCell);
        }

        // Clock fan-out (shared net).
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                clkNet.createPortInst(gemmClk, gemmInsts[r][c]);
        for (int i = 0; i < weightEbCount; i++)
            clkNet.createPortInst(weightEbClk, weightEbInsts[i]);
        if (hasInputEB) {
            for (int i = 0; i < nRows; i++)
                clkNet.createPortInst(inputEbClk, inputEbInsts[i]);
        }
        if (hasDrain) {
            for (int i = 0; i < nCols; i++)
                clkNet.createPortInst(drainClk, drainInsts[i]);
        }

        // Reset fan-out (shared net).
        if (weightEbRst != null) {
            for (int i = 0; i < weightEbCount; i++)
                rstNNet.createPortInst(weightEbRst, weightEbInsts[i]);
        }
        if (hasInputEB && inputEbRst != null) {
            for (int i = 0; i < nRows; i++)
                rstNNet.createPortInst(inputEbRst, inputEbInsts[i]);
        }
        if (hasDrain && drainRst != null) {
            for (int i = 0; i < nCols; i++)
                rstNNet.createPortInst(drainRst, drainInsts[i]);
        }

        // Inter-EB rd_en chains.
        for (int i = 0; i < weightEbCount - 1; i++) {
            EDIFNet rdEnChain = topCell.createNet(
                    prefix + "b_rd_en_chain_" + i + "_to_" + (i + 1));
            rdEnChain.createPortInst(EB_RD_EN_OUT, weightEbInsts[i]);
            rdEnChain.createPortInst(EB_RD_EN, weightEbInsts[i + 1]);
        }
        if (hasInputEB) {
            for (int i = 0; i < nRows - 1; i++) {
                EDIFNet rdEnChain = topCell.createNet(
                        prefix + "a_rd_en_chain_" + i + "_to_" + (i + 1));
                rdEnChain.createPortInst(EB_RD_EN_OUT, inputEbInsts[i]);
                rdEnChain.createPortInst(EB_RD_EN, inputEbInsts[i + 1]);
            }
        }

        // Weight EB forwarding chain.
        for (int i = 0; i < weightEbCount - 1; i++) {
            connectBusPorts(topCell, prefix + "b_eb_chain_data_" + i + "_to_" + (i + 1),
                    weightEbInsts[i], EB_M_DATA, weightEbInsts[i + 1], EB_S_DATA, ebDataWidth);
            connectSingleBit(topCell, prefix + "b_eb_chain_valid_" + i + "_to_" + (i + 1),
                    weightEbInsts[i], EB_M_VALID, weightEbInsts[i + 1], EB_S_VALID);
            for (int b = 0; b < 4; b++) {
                EDIFNet wiNet = topCell.createNet(
                        prefix + "b_eb_chain_wi_" + i + "_to_" + (i + 1) + "_" + b);
                wiNet.createPortInst(EB_M_WORD_INDEX + "[" + b + "]", weightEbInsts[i]);
                wiNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", weightEbInsts[i + 1]);
            }
        }

        // Input EB forwarding chain (only if InputEB chain exists).
        if (hasInputEB) {
            for (int i = 0; i < nRows - 1; i++) {
                connectBusPorts(topCell, prefix + "a_eb_chain_data_" + i + "_to_" + (i + 1),
                        inputEbInsts[i], EB_M_DATA, inputEbInsts[i + 1], EB_S_DATA, ebDataWidth);
                connectSingleBit(topCell, prefix + "a_eb_chain_valid_" + i + "_to_" + (i + 1),
                        inputEbInsts[i], EB_M_VALID, inputEbInsts[i + 1], EB_S_VALID);
                for (int b = 0; b < 4; b++) {
                    EDIFNet wiNet = topCell.createNet(
                            prefix + "a_eb_chain_wi_" + i + "_to_" + (i + 1) + "_" + b);
                    wiNet.createPortInst(EB_M_WORD_INDEX + "[" + b + "]", inputEbInsts[i]);
                    wiNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", inputEbInsts[i + 1]);
                }
            }
        }

        // Weight EB -> GEMM input wiring depends on orientation:
        //   - Standard SA (hasInputEB): WeightEB[c].dout[k] -> tile_x{c}y0
        //     (top-row) .north_inputs[k]. WeightEB sits above the array.
        //   - Rotated SA (!hasInputEB): WeightEB[r].dout[k] -> tile_x0y{r}
        //     (col-0)   .west_inputs[k].  WeightEB sits to the LEFT of the
        //     array; the top-row north_inputs are reserved for the inter-SA
        //     ReluTile to drive (see attachReluBetweenSAs).
        if (hasInputEB) {
            for (int col = 0; col < nCols; col++) {
                for (int k = 0; k < numUnitsNorth; k++) {
                    String netPrefix = prefix + "web_x" + col + "_to_tile_x" + col + "y0";
                    connectBusPorts(topCell, netPrefix + "_data" + k,
                            weightEbInsts[col], EB_DOUT + "[" + k + "]",
                            gemmInsts[0][col], GEMM_NORTH_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, netPrefix + "_valid" + k,
                            weightEbInsts[col], EB_DOUT_VALID + "[" + k + "]",
                            gemmInsts[0][col], GEMM_NORTH_INPUTS_VALID + "[" + k + "]");
                }
            }
        } else {
            for (int row = 0; row < nRows; row++) {
                for (int k = 0; k < numUnitsWest; k++) {
                    String netPrefix = prefix + "web_y" + row + "_to_tile_x0y" + row;
                    connectBusPorts(topCell, netPrefix + "_data" + k,
                            weightEbInsts[row], EB_DOUT + "[" + k + "]",
                            gemmInsts[row][0], GEMM_WEST_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, netPrefix + "_valid" + k,
                            weightEbInsts[row], EB_DOUT_VALID + "[" + k + "]",
                            gemmInsts[row][0], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // Input EB -> column-0 GEMM west_inputs (only if InputEB chain exists).
        if (hasInputEB) {
            for (int row = 0; row < nRows; row++) {
                for (int k = 0; k < numUnitsWest; k++) {
                    String netPrefix = prefix + "ieb_y" + row + "_to_tile_x0y" + row;
                    connectBusPorts(topCell, netPrefix + "_data" + k,
                            inputEbInsts[row], EB_DOUT + "[" + k + "]",
                            gemmInsts[row][0], GEMM_WEST_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, netPrefix + "_valid" + k,
                            inputEbInsts[row], EB_DOUT_VALID + "[" + k + "]",
                            gemmInsts[row][0], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // GEMM east -> west horizontal chain.
        for (int r = 0; r < nRows; r++) {
            for (int c = 0; c < nCols - 1; c++) {
                String netPrefix = prefix + "tile_x" + c + "y" + r
                        + "_to_tile_x" + (c + 1) + "y" + r;
                for (int k = 0; k < eastCount; k++) {
                    connectBusPorts(topCell, netPrefix + "_east_data" + k,
                            gemmInsts[r][c], GEMM_EAST_OUTPUTS + "[" + k + "]",
                            gemmInsts[r][c + 1], GEMM_WEST_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, netPrefix + "_east_valid" + k,
                            gemmInsts[r][c], GEMM_EAST_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[r][c + 1], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // GEMM south -> north vertical chain.
        for (int r = 0; r < nRows - 1; r++) {
            for (int c = 0; c < nCols; c++) {
                String netPrefix = prefix + "tile_x" + c + "y" + r
                        + "_to_tile_x" + c + "y" + (r + 1);
                for (int k = 0; k < southCount; k++) {
                    connectBusPorts(topCell, netPrefix + "_south_data" + k,
                            gemmInsts[r][c], GEMM_SOUTH_OUTPUTS + "[" + k + "]",
                            gemmInsts[r + 1][c], GEMM_NORTH_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, netPrefix + "_south_valid" + k,
                            gemmInsts[r][c], GEMM_SOUTH_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[r + 1][c], GEMM_NORTH_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // Accumulator chain (vertical, per column). Top-row accum_inputs tied
        // to GND. Bottom-row accum_outputs go to drain when present; otherwise
        // left disconnected for the caller to claim (e.g. attachReluBetweenSAs).
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < accumCount; k++) {
                for (int i = 0; i < accumBits; i++) {
                    gndNet.createPortInst(
                            GEMM_ACCUM_INPUTS + "[" + k + "][" + i + "]", gemmInsts[0][col]);
                }
                for (int r = 0; r < nRows - 1; r++) {
                    connectBusPorts(topCell,
                            prefix + "tile_x" + col + "y" + r + "_to_tile_x" + col + "y"
                                    + (r + 1) + "_accum" + k,
                            gemmInsts[r][col], GEMM_ACCUM_OUTPUTS + "[" + k + "]",
                            gemmInsts[r + 1][col], GEMM_ACCUM_INPUTS + "[" + k + "]",
                            accumBits);
                }
                if (hasDrain) {
                    connectBusPorts(topCell, prefix + "accum_to_drain_x" + col + "_k" + k,
                            gemmInsts[nRows - 1][col], GEMM_ACCUM_OUTPUTS + "[" + k + "]",
                            drainInsts[col], DRAIN_FIFO_DIN + "[" + k + "]",
                            accumBits);
                }
            }
        }

        // Drain AXI-stream chain (only if Drain chain exists).
        if (hasDrain) {
            for (int i = 0; i < nCols - 1; i++) {
                connectBusPorts(topCell, prefix + "drain_chain_data_" + (i + 1) + "_to_" + i,
                        drainInsts[i + 1], DRAIN_M_TDATA,
                        drainInsts[i], DRAIN_S_TDATA, accumBits);
                connectSingleBit(topCell, prefix + "drain_chain_valid_" + (i + 1) + "_to_" + i,
                        drainInsts[i + 1], DRAIN_M_TVALID, drainInsts[i], DRAIN_S_TVALID);
                connectSingleBit(topCell, prefix + "drain_chain_ready_" + (i + 1) + "_to_" + i,
                        drainInsts[i], DRAIN_S_TREADY, drainInsts[i + 1], DRAIN_M_TREADY);
            }
            for (int i = 0; i < accumBits; i++) {
                gndNet.createPortInst(DRAIN_S_TDATA + "[" + i + "]", drainInsts[nCols - 1]);
            }
            gndNet.createPortInst(DRAIN_S_TVALID, drainInsts[nCols - 1]);
        }

        // Right-edge east_outputs and bottom-edge south_outputs as top-level ports.
        if (exposeEdgeOutputs) {
            for (int r = 0; r < nRows; r++) {
                for (int k = 0; k < eastCount; k++) {
                    EDIFPort eastPort = createBusPort(topCell,
                            prefix + "east_outputs_row" + r + "[" + k + "]",
                            EDIFDirection.OUTPUT, dataBits);
                    connectBusPortToTopLevel(topCell, prefix + "east_out_y" + r + "_k" + k,
                            eastPort, gemmInsts[r][nCols - 1],
                            GEMM_EAST_OUTPUTS + "[" + k + "]", dataBits);

                    EDIFPort eastValidPort = topCell.createPort(
                            prefix + "east_outputs_valid_row" + r + "[" + k + "]",
                            EDIFDirection.OUTPUT, 1);
                    EDIFNet eastValidNet = topCell.createNet(
                            prefix + "east_out_valid_y" + r + "_k" + k);
                    eastValidNet.createPortInst(eastValidPort);
                    eastValidNet.createPortInst(GEMM_EAST_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[r][nCols - 1]);
                }
            }
            for (int c = 0; c < nCols; c++) {
                for (int k = 0; k < southCount; k++) {
                    EDIFPort southPort = createBusPort(topCell,
                            prefix + "south_outputs_col" + c + "[" + k + "]",
                            EDIFDirection.OUTPUT, dataBits);
                    connectBusPortToTopLevel(topCell, prefix + "south_out_x" + c + "_k" + k,
                            southPort, gemmInsts[nRows - 1][c],
                            GEMM_SOUTH_OUTPUTS + "[" + k + "]", dataBits);

                    EDIFPort southValidPort = topCell.createPort(
                            prefix + "south_outputs_valid_col" + c + "[" + k + "]",
                            EDIFDirection.OUTPUT, 1);
                    EDIFNet southValidNet = topCell.createNet(
                            prefix + "south_out_valid_x" + c + "_k" + k);
                    southValidNet.createPortInst(southValidPort);
                    southValidNet.createPortInst(GEMM_SOUTH_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[nRows - 1][c]);
                }
            }
        }
    }

    public static EDIFCellInst attachMM2SNOCChannel(Design design, String precompileDir, String instanceName,
                                                    String[][] gemmInstanceNames,
                                                    String[] weightEbRootInstanceNames,
                                                    String[] inputEbRootInstanceNames,
                                                    String[] drainInstanceNames,
                                                    String topDonePortName) {
        return attachMM2SNOCChannel(design, precompileDir, instanceName, gemmInstanceNames,
                weightEbRootInstanceNames, inputEbRootInstanceNames, drainInstanceNames, topDonePortName,
                Collections.emptyMap());
    }

    /**
     * Variant that takes a map of original GEMM tile instance names that were
     * merged into SLR-crossing modules (typically from {@code ArrayBuilder.getMergedTileMap()}).
     * For each merged tile, the per-tile fan-out is wired to the merged cell instance
     * with the corresponding port-name prefix instead of a direct lookup of the
     * (no-longer-existing) original instance.
     */
    public static EDIFCellInst attachMM2SNOCChannel(Design design, String precompileDir, String instanceName,
                                                    String[][] gemmInstanceNames,
                                                    String[] weightEbRootInstanceNames,
                                                    String[] inputEbRootInstanceNames,
                                                    String[] drainInstanceNames,
                                                    String topDonePortName,
                                                    Map<String, Pair<EDIFCellInst, String>> mergedTileMap) {
        if (gemmInstanceNames.length == 0 || gemmInstanceNames[0].length == 0) {
            throw new IllegalArgumentException("At least one GEMM instance is required to attach MM2S");
        }
        if (weightEbRootInstanceNames.length == 0) {
            throw new IllegalArgumentException("At least one weight edge buffer root is required to attach MM2S");
        }
        if (inputEbRootInstanceNames.length == 0) {
            throw new IllegalArgumentException("At least one input edge buffer root is required to attach MM2S");
        }
        if (drainInstanceNames.length == 0) {
            throw new IllegalArgumentException("At least one drain instance is required to attach MM2S");
        }

        EDIFCellInst mm2sInst = attachChannelBlackBox(design, precompileDir, new MM2SNOCChannel(), instanceName);
        EDIFCell topCell = design.getNetlist().getTopCell();

        if (topDonePortName != null) {
            connectSingleBit(topCell, "done", requirePort(topCell, topDonePortName), mm2sInst, MM2S_DONE);
        }

        EDIFCellInst firstDrainInst = requireCellInst(topCell, drainInstanceNames[0]);
        int accumCount = getPortWidth(firstDrainInst.getCellType(), DRAIN_FIFO_WR_EN);
        EDIFNet outputWrEnNet = topCell.createNet("output_wr_en");
        outputWrEnNet.createPortInst(MM2S_OUTPUT_WR_EN, mm2sInst);
        for (String drainInstanceName : drainInstanceNames) {
            EDIFCellInst drainInst = requireCellInst(topCell, drainInstanceName);
            for (int k = 0; k < accumCount; k++) {
                outputWrEnNet.createPortInst(DRAIN_FIFO_WR_EN + "[" + k + "]", drainInst);
            }
        }

        EDIFNet accumShiftNet = topCell.createNet("sa_accum_shift");
        accumShiftNet.createPortInst(MM2S_SA_ACCUM_SHIFT, mm2sInst);
        for (String[] rowInstanceNames : gemmInstanceNames) {
            for (String gemmInstanceName : rowInstanceNames) {
                Pair<EDIFCellInst, String> merged = mergedTileMap.get(gemmInstanceName);
                if (merged != null) {
                    accumShiftNet.createPortInst(merged.getSecond() + GEMM_ACCUM_SHIFT, merged.getFirst());
                } else {
                    accumShiftNet.createPortInst(GEMM_ACCUM_SHIFT, requireCellInst(topCell, gemmInstanceName));
                }
            }
        }

        // Per-SLR roots may already be wired into the inter-EB chain by createSystolicArrayNetlist;
        // claimPortInst detaches them from the chain net before reattaching to the MM2S-driven net.
        EDIFNet bRdEnNet = topCell.createNet("b_rd_en");
        bRdEnNet.createPortInst(MM2S_B_RD_EN, mm2sInst);
        for (String weightEbRootInstanceName : weightEbRootInstanceNames) {
            claimPortInst(bRdEnNet, EB_RD_EN, requireCellInst(topCell, weightEbRootInstanceName));
        }

        EDIFNet aRdEnNet = topCell.createNet("a_rd_en");
        aRdEnNet.createPortInst(MM2S_A_RD_EN, mm2sInst);
        for (String inputEbRootInstanceName : inputEbRootInstanceNames) {
            claimPortInst(aRdEnNet, EB_RD_EN, requireCellInst(topCell, inputEbRootInstanceName));
        }

        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, design.getNetlist());
        EDIFCellInst firstWeightEbInst = requireCellInst(topCell, weightEbRootInstanceNames[0]);
        int weightEbDataWidth = getPortWidth(firstWeightEbInst.getCellType(), EB_S_DATA);
        // One data net per bit, one valid net: MM2S source fans out to every weight EB root.
        EDIFNet[] bDataNets = new EDIFNet[weightEbDataWidth];
        for (int i = 0; i < weightEbDataWidth; i++) {
            bDataNets[i] = topCell.createNet("b_eb_" + instanceName + "_data_" + i);
            bDataNets[i].createPortInst(MM2S_M_DATA_B + "[" + i + "]", mm2sInst);
        }
        EDIFNet bValidNet = topCell.createNet("b_eb_" + instanceName + "_valid");
        bValidNet.createPortInst(MM2S_M_VALID_B, mm2sInst);
        for (String weightEbRootInstanceName : weightEbRootInstanceNames) {
            EDIFCellInst weightEbInst = requireCellInst(topCell, weightEbRootInstanceName);
            for (int i = 0; i < weightEbDataWidth; i++) {
                claimPortInst(bDataNets[i], EB_S_DATA + "[" + i + "]", weightEbInst);
            }
            claimPortInst(bValidNet, EB_S_VALID, weightEbInst);
            for (int b = 0; b < 4; b++) {
                claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", weightEbInst);
            }
        }

        EDIFCellInst firstInputEbInst = requireCellInst(topCell, inputEbRootInstanceNames[0]);
        int inputEbDataWidth = getPortWidth(firstInputEbInst.getCellType(), EB_S_DATA);
        EDIFNet[] aDataNets = new EDIFNet[inputEbDataWidth];
        for (int i = 0; i < inputEbDataWidth; i++) {
            aDataNets[i] = topCell.createNet("a_eb_" + instanceName + "_data_" + i);
            aDataNets[i].createPortInst(MM2S_M_DATA_A + "[" + i + "]", mm2sInst);
        }
        EDIFNet aValidNet = topCell.createNet("a_eb_" + instanceName + "_valid");
        aValidNet.createPortInst(MM2S_M_VALID_A, mm2sInst);
        for (String inputEbRootInstanceName : inputEbRootInstanceNames) {
            EDIFCellInst inputEbInst = requireCellInst(topCell, inputEbRootInstanceName);
            for (int i = 0; i < inputEbDataWidth; i++) {
                claimPortInst(aDataNets[i], EB_S_DATA + "[" + i + "]", inputEbInst);
            }
            claimPortInst(aValidNet, EB_S_VALID, inputEbInst);
            for (int b = 0; b < 4; b++) {
                claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", inputEbInst);
            }
        }

        return mm2sInst;
    }

    /**
     * Adds an additional MM2S NOC channel that drives only the input EB
     * (a_data / a_valid / a_rd_en, plus tying word_index to GND) for one
     * per-SLR root. Use this for SLRs other than the primary, where the
     * primary {@link #attachMM2SNOCChannel} drives weight EBs, accum_shift,
     * output_wr_en, done, and the s2mm handshake. All other input ports of
     * this secondary MM2S are tied to GND; outputs other than the input-EB
     * fan-out are left unconnected.
     */
    public static EDIFCellInst attachSecondaryInputMM2SNOCChannel(Design design, String precompileDir,
                                                                  String instanceName,
                                                                  String inputEbRootInstanceName) {
        EDIFCellInst mm2sInst = attachChannelBlackBox(design, precompileDir, new MM2SNOCChannel(), instanceName);
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFCellInst inputEbInst = requireCellInst(topCell, inputEbRootInstanceName);
        int inputEbDataWidth = getPortWidth(inputEbInst.getCellType(), EB_S_DATA);

        // Data fan-out (one net per bit) from this secondary MM2S to its SLR's input EB root.
        for (int i = 0; i < inputEbDataWidth; i++) {
            EDIFNet net = topCell.createNet("a_eb_" + instanceName + "_data_" + i);
            net.createPortInst(MM2S_M_DATA_A + "[" + i + "]", mm2sInst);
            claimPortInst(net, EB_S_DATA + "[" + i + "]", inputEbInst);
        }
        EDIFNet validNet = topCell.createNet("a_eb_" + instanceName + "_valid");
        validNet.createPortInst(MM2S_M_VALID_A, mm2sInst);
        claimPortInst(validNet, EB_S_VALID, inputEbInst);

        EDIFNet rdEnNet = topCell.createNet(instanceName + "_a_rd_en");
        rdEnNet.createPortInst(MM2S_A_RD_EN, mm2sInst);
        claimPortInst(rdEnNet, EB_RD_EN, inputEbInst);

        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, design.getNetlist());
        for (int b = 0; b < 4; b++) {
            claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", inputEbInst);
        }

        // Tie any remaining unconnected MM2S input ports to GND so they don't dangle.
        // (clk/rst_n were already connected by attachChannelBlackBox; the input-EB
        // fan-out above wired m_data_a/m_valid_a/a_rd_en outputs.)
        EDIFCell mm2sCell = mm2sInst.getCellType();
        for (EDIFPort port : mm2sCell.getPorts()) {
            if (!port.isInput()) continue;
            for (int idx : port.getBitBlastedIndices()) {
                String pname = port.getPortInstNameFromPort(idx);
                EDIFPortInst existing = mm2sInst.getPortInst(pname);
                if (existing == null || existing.getNet() == null) {
                    gndNet.createPortInst(pname, mm2sInst);
                }
            }
        }

        return mm2sInst;
    }

    public static EDIFCellInst attachS2MMNOCChannel(Design design, String precompileDir, String instanceName,
                                                    String drainHeadInstanceName) {
        if (drainHeadInstanceName == null) {
            throw new IllegalArgumentException("A drain head instance is required to attach S2MM");
        }

        EDIFCellInst s2mmInst = attachChannelBlackBox(design, precompileDir, new S2MMNOCChannel(), instanceName);
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFCellInst drainHeadInst = requireCellInst(topCell, drainHeadInstanceName);
        int accumBits = getPortWidth(drainHeadInst.getCellType(), DRAIN_M_TDATA);

        connectBusPorts(topCell, "drain_to_" + instanceName + "_data",
                drainHeadInst, DRAIN_M_TDATA, s2mmInst, S2MM_S_DATA, accumBits);
        connectSingleBit(topCell, "drain_to_" + instanceName + "_valid",
                drainHeadInst, DRAIN_M_TVALID, s2mmInst, S2MM_S_VALID);
        connectSingleBit(topCell, "drain_to_" + instanceName + "_ready",
                s2mmInst, S2MM_S_READY, drainHeadInst, DRAIN_M_TREADY);

        return s2mmInst;
    }

    /**
     * Stamps a row of {@link ReluTile} instances between two SA segments
     * built by {@link #createMultiSANetlist} (or {@link #buildArraySegment}
     * with prefixes). For each lane, claims:
     *   - source: prev SA's bottom-row tile
     *     {@code <prevPrefix>tile_x{c}y{nRowsPrev-1}.accum_outputs[k]}
     *   - sink:   next SA's top-row tile
     *     {@code <nextPrefix>tile_x{c}y0.north_inputs[k]}
     * and routes them through a ReluTile lane. The next SA is assumed to
     * have its top-row north_inputs left undriven by {@link #buildArraySegment}
     * (i.e. {@code hasInputEB == false}, which suppresses the WeightEB ->
     * north_inputs wiring).
     *
     * Lane count constraints:
     *   prevSaNCols * accumCount  ==  nextSaNCols * numUnitsNorth  (=== totalLanes)
     *   totalLanes % ReluTile.numLanes == 0
     *
     * Bring-up wiring choices (revisit later):
     *   - ReluTile {@code data_in_valid} is tied to VCC (always valid).
     *     A cleaner design gates this off the prev SA's MM2S
     *     {@code output_wr_en}.
     *   - ReluTile {@code data_out_valid} drives the next SA's
     *     north_inputs_valid lanes for this ReluTile's slice.
     *
     * Returns the list of created ReluTile instances (one per lane group).
     */
    public static List<EDIFCellInst> attachReluBetweenSAs(Design design, String precompileDir,
                                                          String prevSaPrefix, int prevSaNRows,
                                                          int prevSaNCols,
                                                          String nextSaPrefix, int nextSaNRows,
                                                          int nextSaNCols) {
        return attachReluBetweenSAs(design, precompileDir, prevSaPrefix, prevSaNRows,
                prevSaNCols, nextSaPrefix, nextSaNRows, nextSaNCols, false);
    }

    public static List<EDIFCellInst> attachReluBetweenSAsWithBufferCrossings(Design design,
                                                                             String precompileDir,
                                                                             String prevSaPrefix,
                                                                             int prevSaNRows,
                                                                             int prevSaNCols,
                                                                             String nextSaPrefix,
                                                                             int nextSaNRows,
                                                                             int nextSaNCols) {
        return attachReluBetweenSAs(design, precompileDir, prevSaPrefix, prevSaNRows,
                prevSaNCols, nextSaPrefix, nextSaNRows, nextSaNCols, true);
    }

    private static List<EDIFCellInst> attachReluBetweenSAs(Design design, String precompileDir,
                                                           String prevSaPrefix, int prevSaNRows,
                                                           int prevSaNCols,
                                                           String nextSaPrefix, int nextSaNRows,
                                                           int nextSaNCols,
                                                           boolean insertBufferCrossings) {
        ReluTile reluComponent = new ReluTile(4, 8);
        BufferTile bufferComponent = new BufferTile(4, 8);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        // Migrate ReluTile cell once across the design — subsequent calls
        // find it in an existing library instead of re-migrating.
        EDIFCell reluCell = findCellInNetlist(netlist, reluComponent.getComponentName());
        if (reluCell == null) {
            reluCell = loadComponentCell(precompileDir, reluComponent);
            netlist.migrateCellAndSubCells(reluCell, true);
            reluCell.makePrimitive();
        }

        EDIFCell bufferCrossingCell = null;
        if (insertBufferCrossings) {
            String bufferCrossingDcp = precompileDir + File.separator
                    + bufferComponent.getComponentName() + File.separator
                    + RapidSAPrecompile.SLR_CROSSING_RUN_DIR + File.separator
                    + RapidSAPrecompile.SLR_CROSSING_SYNTH_DCP_NAME;
            bufferCrossingCell = Design.readCheckpoint(bufferCrossingDcp).getTopEDIFCell();
            netlist.migrateCellAndSubCells(bufferCrossingCell, true);
            bufferCrossingCell = findCellInNetlist(netlist, bufferCrossingCell.getName());
            bufferCrossingCell.makePrimitive();
        }

        EDIFNet clkNet = requireNet(topCell, "clk");
        EDIFNet rstNNet = requireNet(topCell, "rst_n");
        EDIFNet vccNet = EDIFTools.getStaticNet(NetType.VCC, topCell, netlist);

        // Per-tile dimensions are constant across the precompile and can be
        // read from any existing GEMM tile instance.
        EDIFCellInst sampleGemm = requireCellInst(topCell, prevSaPrefix + "tile_x0y0");
        EDIFCell gemmCellType = sampleGemm.getCellType();
        int accumCount = countArrayPorts(gemmCellType, GEMM_ACCUM_OUTPUTS);
        int accumBits = getPortWidth(gemmCellType, GEMM_ACCUM_OUTPUTS + "[0]");
        int numUnitsNorth = countArrayPorts(gemmCellType, GEMM_NORTH_INPUTS);
        int dataBits = getPortWidth(gemmCellType, GEMM_NORTH_INPUTS + "[0]");

        if (accumBits != dataBits) {
            throw new IllegalArgumentException(
                    "Width mismatch between prev accum_outputs (" + accumBits
                            + " bits) and next north_inputs (" + dataBits + " bits)");
        }

        int totalLanes = prevSaNCols * accumCount;
        int requiredNextLanes = nextSaNCols * numUnitsNorth;
        if (totalLanes != requiredNextLanes) {
            throw new IllegalArgumentException(
                    "Lane count mismatch: prev SA produces " + totalLanes
                            + " lanes (" + prevSaNCols + " cols x " + accumCount
                            + " accum), next SA expects " + requiredNextLanes
                            + " (" + nextSaNCols + " cols x " + numUnitsNorth + " north)");
        }

        int reluLanes = reluComponent.getNumLanes();
        if (totalLanes % reluLanes != 0) {
            throw new IllegalArgumentException(
                    "Lane count " + totalLanes + " between " + prevSaPrefix + " and "
                            + nextSaPrefix + " is not divisible by ReluTile lane count "
                            + reluLanes);
        }
        int numReluInsts = totalLanes / reluLanes;

        // Strip the trailing underscore from the segment prefixes for the relu
        // instance naming — sa0_/sa1_ -> sa0_to_sa1_relu_x{n}.
        String prevName = stripTrailingUnderscore(prevSaPrefix);
        String nextName = stripTrailingUnderscore(nextSaPrefix);
        String namePrefix = prevName + "_to_" + nextName + "_relu_x";

        List<EDIFCellInst> reluInsts = new ArrayList<>();
        for (int rIdx = 0; rIdx < numReluInsts; rIdx++) {
            String reluInstName = namePrefix + rIdx;
            EDIFCellInst reluInst = createBlackBox(topCell, reluInstName, reluCell);
            reluInsts.add(reluInst);
            EDIFCellInst bufferInst = null;
            String bufferInstName = prevName + "_to_" + nextName + "_buf_x" + rIdx;
            if (insertBufferCrossings) {
                bufferInst = createBlackBox(topCell, bufferInstName, bufferCrossingCell);
                clkNet.createPortInst(bufferComponent.getClkName(), bufferInst);
            }

            clkNet.createPortInst(reluComponent.getClkName(), reluInst);
            rstNNet.createPortInst(reluComponent.getResetName(), reluInst);
            // TODO: replace VCC tie with prev SA's MM2S output_wr_en for proper
            // valid-window gating once per-SA control nets are scoped.
            vccNet.createPortInst("data_in_valid", reluInst);

            // Per-lane data wiring.
            for (int k = 0; k < reluLanes; k++) {
                int globalLane = rIdx * reluLanes + k;
                int prevC = globalLane / accumCount;
                int prevAccumK = globalLane % accumCount;
                int nextC = globalLane / numUnitsNorth;
                int nextNorthK = globalLane % numUnitsNorth;

                EDIFCellInst prevTile = requireCellInst(topCell,
                        prevSaPrefix + "tile_x" + prevC + "y" + (prevSaNRows - 1));
                EDIFCellInst nextTile = requireCellInst(topCell,
                        nextSaPrefix + "tile_x" + nextC + "y0");

                // Source bus: prev accum_outputs[prevAccumK] -> ReluTile.data_in[k]
                for (int b = 0; b < accumBits; b++) {
                    EDIFNet net = topCell.createNet(
                            reluInstName + "_data_in_" + k + "_" + b);
                    claimPortInst(net,
                            GEMM_ACCUM_OUTPUTS + "[" + prevAccumK + "][" + b + "]",
                            prevTile);
                    net.createPortInst("data_in[" + k + "][" + b + "]", reluInst);
                }

                // Destination bus: ReluTile.data_out[k] -> next north_inputs[nextNorthK]
                for (int b = 0; b < dataBits; b++) {
                    if (insertBufferCrossings) {
                        EDIFNet topNet = topCell.createNet(
                                bufferInstName + "_top_in_" + k + "_" + b);
                        topNet.createPortInst("data_out[" + k + "][" + b + "]", reluInst);
                        topNet.createPortInst(RapidSAPrecompile.SLR_CROSSING_TOP_INST_NAME
                                + "_top_in[" + k + "][" + b + "]", bufferInst);

                        EDIFNet bottomNet = topCell.createNet(
                                bufferInstName + "_bot_out_" + k + "_" + b);
                        bottomNet.createPortInst(RapidSAPrecompile.SLR_CROSSING_BOTTOM_INST_NAME
                                + "_bot_out[" + k + "][" + b + "]", bufferInst);
                        claimPortInst(bottomNet,
                                GEMM_NORTH_INPUTS + "[" + nextNorthK + "][" + b + "]",
                                nextTile);
                    } else {
                        EDIFNet net = topCell.createNet(
                                reluInstName + "_data_out_" + k + "_" + b);
                        net.createPortInst("data_out[" + k + "][" + b + "]", reluInst);
                        claimPortInst(net,
                                GEMM_NORTH_INPUTS + "[" + nextNorthK + "][" + b + "]",
                                nextTile);
                    }
                }
            }

            // ReluTile data_out_valid drives all lanes in this slice. When a
            // BufferTile crossing is inserted, each lane's registered valid
            // output drives the corresponding next-tile valid input.
            EDIFNet validNet = topCell.createNet(reluInstName + "_data_out_valid");
            validNet.createPortInst("data_out_valid", reluInst);
            for (int k = 0; k < reluLanes; k++) {
                int globalLane = rIdx * reluLanes + k;
                int nextC = globalLane / numUnitsNorth;
                int nextNorthK = globalLane % numUnitsNorth;
                EDIFCellInst nextTile = requireCellInst(topCell,
                        nextSaPrefix + "tile_x" + nextC + "y0");
                if (insertBufferCrossings) {
                    validNet.createPortInst(RapidSAPrecompile.SLR_CROSSING_TOP_INST_NAME
                            + "_top_in_valid[" + k + "]", bufferInst);
                    EDIFNet bottomValidNet = topCell.createNet(
                            bufferInstName + "_bot_out_valid_" + k);
                    bottomValidNet.createPortInst(RapidSAPrecompile.SLR_CROSSING_BOTTOM_INST_NAME
                            + "_bot_out_valid[" + k + "]", bufferInst);
                    claimPortInst(bottomValidNet,
                            GEMM_NORTH_INPUTS_VALID + "[" + nextNorthK + "]", nextTile);
                } else {
                    claimPortInst(validNet,
                            GEMM_NORTH_INPUTS_VALID + "[" + nextNorthK + "]", nextTile);
                }
            }
        }

        return reluInsts;
    }

    /**
     * Multi-SA-aware MM2S attachment. Each SA segment in a multi-SA design
     * gets its own MM2S NOC channel that drives only its own scoped control
     * nets. Conditional wiring based on the segment's role in the chain:
     *
     * - WeightEB chain root and {@code sa_accum_shift} are wired for every SA.
     * - InputEB chain root and the activation data path (m_data_a / m_valid_a /
     *   a_rd_en) are wired only when {@code hasInputEB} is true (typically
     *   only SA[0]; intermediate SAs receive activations through
     *   {@link #attachReluBetweenSAs}).
     * - {@code output_wr_en} is broadcast to all this segment's drains only
     *   when {@code hasDrain} is true (typically only SA[last]).
     *
     * Top-level net names are prefixed with {@code saPrefix} so multiple SAs'
     * control nets don't collide.
     *
     * Unused MM2S input pins are tied to GND so the synthesized MM2S black
     * box has all inputs driven (clk/reset already wired by
     * {@code attachChannelBlackBox}).
     */
    public static EDIFCellInst attachMM2SForSA(Design design, String precompileDir,
                                               String mm2sInstanceName, String saPrefix,
                                               int nRows, int nCols,
                                               boolean hasInputEB, boolean hasDrain) {
        EDIFCellInst mm2sInst = attachChannelBlackBox(design, precompileDir,
                new MM2SNOCChannel(), mm2sInstanceName);
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, design.getNetlist());

        // sa_accum_shift broadcast to all GEMMs in this SA segment.
        EDIFNet accumShiftNet = topCell.createNet(saPrefix + "sa_accum_shift");
        accumShiftNet.createPortInst(MM2S_SA_ACCUM_SHIFT, mm2sInst);
        for (int r = 0; r < nRows; r++) {
            for (int c = 0; c < nCols; c++) {
                accumShiftNet.createPortInst(GEMM_ACCUM_SHIFT,
                        requireCellInst(topCell, saPrefix + "tile_x" + c + "y" + r));
            }
        }

        // WeightEB chain root: b_rd_en + m_data_b + m_valid_b + word_index GND.
        // Standard orientation names the chain weight_eb_x{i}; rotated SAs
        // (no InputEB) name it weight_eb_y{i} (per-row, sits left of array).
        String weightEbRootName = hasInputEB
                ? (saPrefix + "weight_eb_x0")
                : (saPrefix + "weight_eb_y0");
        EDIFCellInst weightEbRoot = requireCellInst(topCell, weightEbRootName);
        EDIFNet bRdEnNet = topCell.createNet(saPrefix + "b_rd_en");
        bRdEnNet.createPortInst(MM2S_B_RD_EN, mm2sInst);
        claimPortInst(bRdEnNet, EB_RD_EN, weightEbRoot);

        int weightEbDataWidth = getPortWidth(weightEbRoot.getCellType(), EB_S_DATA);
        for (int i = 0; i < weightEbDataWidth; i++) {
            EDIFNet bDataNet = topCell.createNet(
                    saPrefix + "b_eb_" + mm2sInstanceName + "_data_" + i);
            bDataNet.createPortInst(MM2S_M_DATA_B + "[" + i + "]", mm2sInst);
            claimPortInst(bDataNet, EB_S_DATA + "[" + i + "]", weightEbRoot);
        }
        EDIFNet bValidNet = topCell.createNet(saPrefix + "b_eb_" + mm2sInstanceName + "_valid");
        bValidNet.createPortInst(MM2S_M_VALID_B, mm2sInst);
        claimPortInst(bValidNet, EB_S_VALID, weightEbRoot);
        for (int b = 0; b < 4; b++) {
            claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", weightEbRoot);
        }

        // InputEB chain root (only when present in this SA).
        if (hasInputEB) {
            EDIFCellInst inputEbRoot = requireCellInst(topCell, saPrefix + "input_eb_y0");
            EDIFNet aRdEnNet = topCell.createNet(saPrefix + "a_rd_en");
            aRdEnNet.createPortInst(MM2S_A_RD_EN, mm2sInst);
            claimPortInst(aRdEnNet, EB_RD_EN, inputEbRoot);

            int inputEbDataWidth = getPortWidth(inputEbRoot.getCellType(), EB_S_DATA);
            for (int i = 0; i < inputEbDataWidth; i++) {
                EDIFNet aDataNet = topCell.createNet(
                        saPrefix + "a_eb_" + mm2sInstanceName + "_data_" + i);
                aDataNet.createPortInst(MM2S_M_DATA_A + "[" + i + "]", mm2sInst);
                claimPortInst(aDataNet, EB_S_DATA + "[" + i + "]", inputEbRoot);
            }
            EDIFNet aValidNet = topCell.createNet(
                    saPrefix + "a_eb_" + mm2sInstanceName + "_valid");
            aValidNet.createPortInst(MM2S_M_VALID_A, mm2sInst);
            claimPortInst(aValidNet, EB_S_VALID, inputEbRoot);
            for (int b = 0; b < 4; b++) {
                claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", inputEbRoot);
            }
        }

        // output_wr_en broadcast to all drains in this SA segment (last SA only).
        if (hasDrain) {
            EDIFCellInst firstDrainInst = requireCellInst(topCell, saPrefix + "drain_x0");
            int accumCount = getPortWidth(firstDrainInst.getCellType(), DRAIN_FIFO_WR_EN);
            EDIFNet outputWrEnNet = topCell.createNet(saPrefix + "output_wr_en");
            outputWrEnNet.createPortInst(MM2S_OUTPUT_WR_EN, mm2sInst);
            for (int c = 0; c < nCols; c++) {
                EDIFCellInst drainInst = requireCellInst(topCell, saPrefix + "drain_x" + c);
                for (int k = 0; k < accumCount; k++) {
                    outputWrEnNet.createPortInst(DRAIN_FIFO_WR_EN + "[" + k + "]", drainInst);
                }
            }
        }

        // Tie any remaining unconnected MM2S input pins to GND. When this SA
        // has a drain, the s2mm_done input will be driven by the S2MM handshake
        // wired by a later call to connectIngressEgressChannelHandshake, so
        // skip it here to avoid double-driving the pin.
        EDIFCell mm2sCell = mm2sInst.getCellType();
        for (EDIFPort port : mm2sCell.getPorts()) {
            if (!port.isInput()) continue;
            for (int idx : port.getBitBlastedIndices()) {
                String pname = port.getPortInstNameFromPort(idx);
                if (hasDrain && MM2S_S2MM_DONE.equals(pname)) continue;
                EDIFPortInst existing = mm2sInst.getPortInst(pname);
                if (existing == null || existing.getNet() == null) {
                    gndNet.createPortInst(pname, mm2sInst);
                }
            }
        }

        return mm2sInst;
    }

    private static EDIFCell findCellInNetlist(EDIFNetlist netlist, String cellName) {
        for (EDIFLibrary lib : netlist.getLibraries()) {
            EDIFCell c = lib.getCell(cellName);
            if (c != null) return c;
        }
        return null;
    }

    private static String stripTrailingUnderscore(String s) {
        if (s != null && s.endsWith("_")) return s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Test-mode helper: ties to GND every input port that would normally be
     * driven by this SA's MM2S (and, when {@code hasDrain}, its drains'
     * fifo_wr_en plus s2mm.s2mm_start). Use after building a multi-SA
     * netlist with one SA's MM2S omitted, so the design passes Vivado's
     * driverless-net DRC.
     */
    public static void tieOffSAControlPins(Design design, String saPrefix,
                                           int nRows, int nCols,
                                           boolean hasInputEB, boolean hasDrain) {
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, design.getNetlist());

        // accum_shift on every GEMM in this SA.
        for (int r = 0; r < nRows; r++) {
            for (int c = 0; c < nCols; c++) {
                EDIFCellInst tile = requireCellInst(topCell, saPrefix + "tile_x" + c + "y" + r);
                claimPortInst(gndNet, GEMM_ACCUM_SHIFT, tile);
            }
        }

        // Weight EB chain root: s_data, s_valid, s_word_index, rd_en.
        String weightEbRootName = hasInputEB
                ? (saPrefix + "weight_eb_x0")
                : (saPrefix + "weight_eb_y0");
        EDIFCellInst weightEbRoot = requireCellInst(topCell, weightEbRootName);
        int weightEbDataWidth = getPortWidth(weightEbRoot.getCellType(), EB_S_DATA);
        for (int i = 0; i < weightEbDataWidth; i++) {
            claimPortInst(gndNet, EB_S_DATA + "[" + i + "]", weightEbRoot);
        }
        claimPortInst(gndNet, EB_S_VALID, weightEbRoot);
        for (int b = 0; b < 4; b++) {
            claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", weightEbRoot);
        }
        claimPortInst(gndNet, EB_RD_EN, weightEbRoot);

        // Input EB chain root (if present).
        if (hasInputEB) {
            EDIFCellInst inputEbRoot = requireCellInst(topCell, saPrefix + "input_eb_y0");
            int inputEbDataWidth = getPortWidth(inputEbRoot.getCellType(), EB_S_DATA);
            for (int i = 0; i < inputEbDataWidth; i++) {
                claimPortInst(gndNet, EB_S_DATA + "[" + i + "]", inputEbRoot);
            }
            claimPortInst(gndNet, EB_S_VALID, inputEbRoot);
            for (int b = 0; b < 4; b++) {
                claimPortInst(gndNet, EB_S_WORD_INDEX + "[" + b + "]", inputEbRoot);
            }
            claimPortInst(gndNet, EB_RD_EN, inputEbRoot);
        }

        // Drain fifo_wr_en on every drain (last SA only).
        if (hasDrain) {
            EDIFCellInst firstDrain = requireCellInst(topCell, saPrefix + "drain_x0");
            int accumCount = getPortWidth(firstDrain.getCellType(), DRAIN_FIFO_WR_EN);
            for (int c = 0; c < nCols; c++) {
                EDIFCellInst drain = requireCellInst(topCell, saPrefix + "drain_x" + c);
                for (int k = 0; k < accumCount; k++) {
                    claimPortInst(gndNet, DRAIN_FIFO_WR_EN + "[" + k + "]", drain);
                }
            }
        }
    }

    /**
     * Test-mode helper: ties s2mm's s2mm_start input to GND when no MM2S
     * is wired to it. (s2mm.s2mm_done is an output and can be left
     * dangling.)
     */
    public static void tieOffS2MMHandshake(Design design, String s2mmInstanceName) {
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, design.getNetlist());
        EDIFCellInst s2mmInst = requireCellInst(topCell, s2mmInstanceName);
        claimPortInst(gndNet, S2MM_START, s2mmInst);
    }

    public static void connectIngressEgressChannelHandshake(Design design, String ingressInstanceName,
                                                            String egressInstanceName) {
        EDIFCell topCell = design.getNetlist().getTopCell();
        EDIFCellInst ingressInst = requireCellInst(topCell, ingressInstanceName);
        EDIFCellInst egressInst = requireCellInst(topCell, egressInstanceName);

        connectSingleBit(topCell, "s2mm_start",
                ingressInst, MM2S_S2MM_START, egressInst, S2MM_START);
        connectSingleBit(topCell, "s2mm_done",
                egressInst, S2MM_DONE, ingressInst, MM2S_S2MM_DONE);
    }

    private static EDIFCell loadComponentCell(String precompileDir, RapidComponent component) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + SYNTH_DCP;
        return Design.readCheckpoint(dcpPath).getTopEDIFCell();
    }

    private static EDIFCellInst attachChannelBlackBox(Design design, String precompileDir,
                                                      RapidComponent component, String instanceName) {
        EDIFCell channelCell = loadComponentCell(precompileDir, component);
        EDIFNetlist netlist = design.getNetlist();
        netlist.migrateCellAndSubCells(channelCell, true);
        channelCell.makePrimitive();

        EDIFCell topCell = netlist.getTopCell();
        if (topCell.getCellInst(instanceName) != null) {
            throw new RuntimeException("Channel instance already exists: " + instanceName);
        }

        EDIFCellInst channelInst = createBlackBox(topCell, instanceName, channelCell);
        requireNet(topCell, "clk").createPortInst(component.getClkName(), channelInst);

        String resetName = component.getResetName();
        if (resetName != null) {
            requireNet(topCell, "rst_n").createPortInst(resetName, channelInst);
        }

        return channelInst;
    }

    private static EDIFCellInst createBlackBox(EDIFCell parent, String name, EDIFCell cellType) {
        EDIFCellInst inst = parent.createChildCellInst(name, cellType);
        inst.addProperty(EDIFCellInst.BLACK_BOX_PROP_VERSAL, "1");
        return inst;
    }

    private static EDIFCellInst requireCellInst(EDIFCell topCell, String instanceName) {
        EDIFCellInst inst = topCell.getCellInst(instanceName);
        if (inst == null) {
            throw new RuntimeException("Instance '" + instanceName + "' not found in top-level netlist");
        }
        return inst;
    }

    private static EDIFPort requirePort(EDIFCell topCell, String portName) {
        EDIFPort port = topCell.getPort(portName);
        if (port == null) {
            throw new RuntimeException("Top-level port '" + portName + "' not found");
        }
        return port;
    }

    private static EDIFNet requireNet(EDIFCell topCell, String netName) {
        EDIFNet net = topCell.getNet(netName);
        if (net == null) {
            throw new RuntimeException("Top-level net '" + netName + "' not found");
        }
        return net;
    }

    /**
     * Attaches an instance port to {@code newNet}, first detaching it from any
     * other net it may already be on. Used when the netlist build phase already
     * pre-wired a port (e.g. an inter-EB chain net) but a later pass needs to
     * re-claim it (e.g. so MM2S can drive a per-SLR root EB instead).
     */
    private static EDIFPortInst claimPortInst(EDIFNet newNet, String portInstName, EDIFCellInst inst) {
        EDIFPortInst existing = inst.getPortInst(portInstName);
        if (existing != null && existing.getNet() != null) {
            existing.getNet().removePortInst(existing);
        }
        return newNet.createPortInst(portInstName, inst);
    }

    private static void connectSingleBit(EDIFCell topCell, String netName,
                                         EDIFPort topPort, EDIFCellInst inst, String instPort) {
        EDIFNet net = topCell.createNet(netName);
        net.createPortInst(topPort);
        net.createPortInst(instPort, inst);
    }

    private static void connectSingleBit(EDIFCell topCell, String netName,
                                         EDIFCellInst srcInst, String srcPort,
                                         EDIFCellInst dstInst, String dstPort) {
        EDIFNet net = topCell.createNet(netName);
        net.createPortInst(srcPort, srcInst);
        net.createPortInst(dstPort, dstInst);
    }

    private static void connectBusPorts(EDIFCell topCell, String netPrefix,
                                        EDIFCellInst srcInst, String srcPort,
                                        EDIFCellInst dstInst, String dstPort,
                                        int width) {
        if (width == 1) {
            connectSingleBit(topCell, netPrefix, srcInst, srcPort, dstInst, dstPort);
        } else {
            for (int i = 0; i < width; i++) {
                EDIFNet net = topCell.createNet(netPrefix + "_" + i);
                net.createPortInst(srcPort + "[" + i + "]", srcInst);
                net.createPortInst(dstPort + "[" + i + "]", dstInst);
            }
        }
    }

    private static void connectBusPortToTopLevel(EDIFCell topCell, String netPrefix,
                                                 EDIFPort topPort, EDIFCellInst inst,
                                                 String instPort, int width) {
        if (width == 1) {
            connectSingleBit(topCell, netPrefix, topPort, inst, instPort);
        } else {
            int[] indices = topPort.getBitBlastedIndices();
            for (int i = 0; i < width; i++) {
                EDIFNet net = topCell.createNet(netPrefix + "_" + i);
                net.createPortInst(topPort, indices[i]);
                net.createPortInst(instPort + "[" + i + "]", inst);
            }
        }
    }

    private static EDIFPort createBusPort(EDIFCell cell, String name,
                                          EDIFDirection direction, int width) {
        if (width > 1) {
            return cell.createPort(name + "[" + (width - 1) + ":0]", direction, width);
        }
        return cell.createPort(name, direction, 1);
    }

    private static int getPortWidth(EDIFCell cell, String portName) {
        EDIFPort port = cell.getPort(portName);
        if (port == null) {
            throw new RuntimeException("Port '" + portName + "' not found on cell '" + cell.getName() + "'");
        }
        return port.getWidth();
    }

    private static int countArrayPorts(EDIFCell cell, String baseName) {
        TreeMap<Integer, EDIFPort> indexed = new TreeMap<>();
        for (Map.Entry<String, EDIFPort> entry : cell.getPortMap().entrySet()) {
            String portKey = entry.getKey();
            if (portKey.startsWith(baseName + "[")) {
                String remainder = portKey.substring(baseName.length() + 1);
                int closeBracket = remainder.indexOf(']');
                if (closeBracket > 0) {
                    try {
                        indexed.put(Integer.parseInt(remainder.substring(0, closeBracket)), entry.getValue());
                    } catch (NumberFormatException e) {
                        // Bus range like "7:0" - skip
                    }
                }
            }
        }
        if (!indexed.isEmpty()) {
            return indexed.lastKey() + 1;
        }

        int count = 0;
        while (cell.getPort(baseName + "[" + count + "]") != null ||
               cell.getPort(baseName + "[" + count + "][") != null) {
            count++;
        }
        return count;
    }
}
