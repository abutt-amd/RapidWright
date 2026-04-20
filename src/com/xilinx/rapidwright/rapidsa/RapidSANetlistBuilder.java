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
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.EdgeBufferTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;

import java.io.File;
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
 *                                MM2S / SA FSM
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

    public static Design createSystolicArrayNetlist(int nRows, int nCols, String partName,
                                                    String precompileDir) {
        RapidComponent gemmComponent = new GEMMTile(nCols, nRows);
        RapidComponent weightEbComponent = new EdgeBufferTile(nCols, EdgeBufferTile.Type.WEIGHT);
        RapidComponent inputEbComponent = new EdgeBufferTile(nRows, EdgeBufferTile.Type.INPUT);
        RapidComponent mm2sComponent = new MM2SNOCChannel();
        if (nRows <= 0 || nCols <= 0) {
            throw new IllegalArgumentException(
                    "Array dimensions must be positive: nRows=" + nRows + ", nCols=" + nCols);
        }

        // Load component cells from precompiled DCPs
        EDIFCell gemmCell = loadComponentCell(precompileDir, gemmComponent);
        EDIFCell weightEbCell = loadComponentCell(precompileDir, weightEbComponent);
        EDIFCell inputEbCell = loadComponentCell(precompileDir, inputEbComponent);
        EDIFCell mm2sCell = loadComponentCell(precompileDir, mm2sComponent);

        // Get clock and reset port names from component definitions
        String gemmClk = gemmComponent.getClkName();
        String weightEbClk = weightEbComponent.getClkName();
        String weightEbRst = weightEbComponent.getResetName();
        String inputEbClk = inputEbComponent.getClkName();
        String inputEbRst = inputEbComponent.getResetName();
        String mm2sClk = mm2sComponent.getClkName();
        String mm2sRst = mm2sComponent.getResetName();

        // Create top-level design and migrate component cells
        Design design = new Design("systolic_array", partName);
        design.setAutoIOBuffers(false);
        EDIFNetlist netlist = design.getNetlist();

        netlist.migrateCellAndSubCells(gemmCell, true);
        netlist.migrateCellAndSubCells(weightEbCell, true);
        netlist.migrateCellAndSubCells(inputEbCell, true);
        netlist.migrateCellAndSubCells(mm2sCell, true);

        gemmCell.makePrimitive();
        weightEbCell.makePrimitive();
        inputEbCell.makePrimitive();
        mm2sCell.makePrimitive();

        EDIFCell topCell = netlist.getTopCell();

        // Determine bus widths and array sizes from component ports
        int dataBits = getPortWidth(gemmCell, GEMM_NORTH_INPUTS + "[0]");
        int accumBits = getPortWidth(gemmCell, GEMM_ACCUM_INPUTS + "[0]");
        int numUnitsNorth = countArrayPorts(gemmCell, GEMM_NORTH_INPUTS);
        int numUnitsWest = countArrayPorts(gemmCell, GEMM_WEST_INPUTS);
        int eastCount = countArrayPorts(gemmCell, GEMM_EAST_OUTPUTS);
        int southCount = countArrayPorts(gemmCell, GEMM_SOUTH_OUTPUTS);
        int accumCount = countArrayPorts(gemmCell, GEMM_ACCUM_INPUTS);
        int ebDataWidth = getPortWidth(weightEbCell, EB_S_DATA);

        // Create DrainTile component (needs accumCount from GEMM cell)
        RapidComponent drainComponent = new DrainTile(accumCount, 8);
        EDIFCell drainCell = loadComponentCell(precompileDir, drainComponent);
        String drainClk = drainComponent.getClkName();
        String drainRst = drainComponent.getResetName();
        netlist.migrateCellAndSubCells(drainCell, true);
        drainCell.makePrimitive();

        // Create S2MM NOC channel component
        RapidComponent s2mmComponent = new S2MMNOCChannel();
        EDIFCell s2mmCell = loadComponentCell(precompileDir, s2mmComponent);
        String s2mmClk = s2mmComponent.getClkName();
        String s2mmRst = s2mmComponent.getResetName();
        netlist.migrateCellAndSubCells(s2mmCell, true);
        s2mmCell.makePrimitive();

        // Create black box instances
        EDIFCellInst[][] gemmInsts = new EDIFCellInst[nRows][nCols];
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                gemmInsts[r][c] = createBlackBox(topCell, "tile_x" + c + "y" + r, gemmCell);

        EDIFCellInst[] weightEbInsts = new EDIFCellInst[nCols];
        for (int i = 0; i < nCols; i++)
            weightEbInsts[i] = createBlackBox(topCell, "weight_eb_x" + i, weightEbCell);

        EDIFCellInst[] inputEbInsts = new EDIFCellInst[nRows];
        for (int i = 0; i < nRows; i++)
            inputEbInsts[i] = createBlackBox(topCell, "input_eb_y" + i, inputEbCell);

        EDIFCellInst mm2sInst = createBlackBox(topCell, "mm2s", mm2sCell);

        EDIFCellInst[] drainInsts = new EDIFCellInst[nCols];
        for (int i = 0; i < nCols; i++)
            drainInsts[i] = createBlackBox(topCell, "drain_x" + i, drainCell);

        EDIFCellInst s2mmInst = createBlackBox(topCell, "s2mm", s2mmCell);

        // Top-level ports
        EDIFPort topClk = topCell.createPort("clk", EDIFDirection.INPUT, 1);
        EDIFPort topRstN = topCell.createPort("rst_n", EDIFDirection.INPUT, 1);
        EDIFPort topDone = topCell.createPort("done", EDIFDirection.OUTPUT, 1);

        // Clock: fans out to all instances
        EDIFNet clkNet = topCell.createNet("clk");
        clkNet.createPortInst(topClk);
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                clkNet.createPortInst(gemmClk, gemmInsts[r][c]);
        for (int i = 0; i < nCols; i++)
            clkNet.createPortInst(weightEbClk, weightEbInsts[i]);
        for (int i = 0; i < nRows; i++)
            clkNet.createPortInst(inputEbClk, inputEbInsts[i]);
        for (int i = 0; i < nCols; i++)
            clkNet.createPortInst(drainClk, drainInsts[i]);
        clkNet.createPortInst(mm2sClk, mm2sInst);
        clkNet.createPortInst(s2mmClk, s2mmInst);

        // rst_n: fans out to all instances with reset
        EDIFNet rstNNet = topCell.createNet("rst_n");
        rstNNet.createPortInst(topRstN);
        if (weightEbRst != null) {
            for (int i = 0; i < nCols; i++)
                rstNNet.createPortInst(weightEbRst, weightEbInsts[i]);
        }
        if (inputEbRst != null) {
            for (int i = 0; i < nRows; i++)
                rstNNet.createPortInst(inputEbRst, inputEbInsts[i]);
        }
        if (drainRst != null) {
            for (int i = 0; i < nCols; i++)
                rstNNet.createPortInst(drainRst, drainInsts[i]);
        }
        if (mm2sRst != null) {
            rstNNet.createPortInst(mm2sRst, mm2sInst);
        }
        if (s2mmRst != null) {
            rstNNet.createPortInst(s2mmRst, s2mmInst);
        }

        // FSM done → top-level
        connectSingleBit(topCell, "done", topDone, mm2sInst, MM2S_DONE);

        // FSM output_wr_en → all drain tiles' fifo_wr_en (all bits)
        EDIFNet outputWrEnNet = topCell.createNet("output_wr_en");
        outputWrEnNet.createPortInst(MM2S_OUTPUT_WR_EN, mm2sInst);
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < accumCount; k++) {
                outputWrEnNet.createPortInst(DRAIN_FIFO_WR_EN + "[" + k + "]", drainInsts[col]);
            }
        }

        // FSM sa_accum_shift → all GEMM tiles
        EDIFNet accumShiftNet = topCell.createNet("sa_accum_shift");
        accumShiftNet.createPortInst(MM2S_SA_ACCUM_SHIFT, mm2sInst);
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                accumShiftNet.createPortInst(GEMM_ACCUM_SHIFT, gemmInsts[r][c]);

        // FSM b_rd_en → Weight Edge Buffer rd_en forwarding chain
        EDIFNet bRdEnNet = topCell.createNet("b_rd_en");
        bRdEnNet.createPortInst(MM2S_B_RD_EN, mm2sInst);
        bRdEnNet.createPortInst(EB_RD_EN, weightEbInsts[0]);
        for (int i = 0; i < nCols - 1; i++) {
            EDIFNet rdEnChain = topCell.createNet("b_rd_en_chain_" + i + "_to_" + (i + 1));
            rdEnChain.createPortInst(EB_RD_EN_OUT, weightEbInsts[i]);
            rdEnChain.createPortInst(EB_RD_EN, weightEbInsts[i + 1]);
        }

        // FSM a_rd_en → Input Edge Buffer rd_en forwarding chain
        EDIFNet aRdEnNet = topCell.createNet("a_rd_en");
        aRdEnNet.createPortInst(MM2S_A_RD_EN, mm2sInst);
        aRdEnNet.createPortInst(EB_RD_EN, inputEbInsts[0]);
        for (int i = 0; i < nRows - 1; i++) {
            EDIFNet rdEnChain = topCell.createNet("a_rd_en_chain_" + i + "_to_" + (i + 1));
            rdEnChain.createPortInst(EB_RD_EN_OUT, inputEbInsts[i]);
            rdEnChain.createPortInst(EB_RD_EN, inputEbInsts[i + 1]);
        }

        // S2MM start/done wiring
        connectSingleBit(topCell, "s2mm_start",
                mm2sInst, MM2S_S2MM_START, s2mmInst, S2MM_START);
        connectSingleBit(topCell, "s2mm_done",
                s2mmInst, S2MM_DONE, mm2sInst, MM2S_S2MM_DONE);

        // =====================================================================
        // Weight Edge Buffer forwarding chain (B matrix)
        // MM2S m_data_b → EB[0].s_data → EB[0].m_data → EB[1].s_data → ...
        // =====================================================================
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, netlist);

        // MM2S → first weight EB
        connectBusPorts(topCell, "b_eb_mm2s_to_eb0_data",
                mm2sInst, MM2S_M_DATA_B, weightEbInsts[0], EB_S_DATA, ebDataWidth);
        connectSingleBit(topCell, "b_eb_mm2s_to_eb0_valid",
                mm2sInst, MM2S_M_VALID_B, weightEbInsts[0], EB_S_VALID);
        // Tie word_index to 0 for N <= 16
        for (int b = 0; b < 4; b++) {
            gndNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", weightEbInsts[0]);
        }

        // Inter-EB forwarding chain
        for (int i = 0; i < nCols - 1; i++) {
            connectBusPorts(topCell, "b_eb_chain_data_" + i + "_to_" + (i + 1),
                    weightEbInsts[i], EB_M_DATA, weightEbInsts[i + 1], EB_S_DATA, ebDataWidth);
            connectSingleBit(topCell, "b_eb_chain_valid_" + i + "_to_" + (i + 1),
                    weightEbInsts[i], EB_M_VALID, weightEbInsts[i + 1], EB_S_VALID);
            for (int b = 0; b < 4; b++) {
                EDIFNet wiNet = topCell.createNet("b_eb_chain_wi_" + i + "_to_" + (i + 1) + "_" + b);
                wiNet.createPortInst(EB_M_WORD_INDEX + "[" + b + "]", weightEbInsts[i]);
                wiNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", weightEbInsts[i + 1]);
            }
        }

        // =====================================================================
        // Input Edge Buffer forwarding chain (A matrix)
        // =====================================================================
        connectBusPorts(topCell, "a_eb_mm2s_to_eb0_data",
                mm2sInst, MM2S_M_DATA_A, inputEbInsts[0], EB_S_DATA, ebDataWidth);
        connectSingleBit(topCell, "a_eb_mm2s_to_eb0_valid",
                mm2sInst, MM2S_M_VALID_A, inputEbInsts[0], EB_S_VALID);
        for (int b = 0; b < 4; b++) {
            gndNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", inputEbInsts[0]);
        }

        for (int i = 0; i < nRows - 1; i++) {
            connectBusPorts(topCell, "a_eb_chain_data_" + i + "_to_" + (i + 1),
                    inputEbInsts[i], EB_M_DATA, inputEbInsts[i + 1], EB_S_DATA, ebDataWidth);
            connectSingleBit(topCell, "a_eb_chain_valid_" + i + "_to_" + (i + 1),
                    inputEbInsts[i], EB_M_VALID, inputEbInsts[i + 1], EB_S_VALID);
            for (int b = 0; b < 4; b++) {
                EDIFNet wiNet = topCell.createNet("a_eb_chain_wi_" + i + "_to_" + (i + 1) + "_" + b);
                wiNet.createPortInst(EB_M_WORD_INDEX + "[" + b + "]", inputEbInsts[i]);
                wiNet.createPortInst(EB_S_WORD_INDEX + "[" + b + "]", inputEbInsts[i + 1]);
            }
        }

        // =====================================================================
        // Weight Edge Buffers → top row GEMM tiles
        // =====================================================================
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < numUnitsNorth; k++) {
                String prefix = "web_x" + col + "_to_tile_x" + col + "y0";
                connectBusPorts(topCell, prefix + "_data" + k,
                        weightEbInsts[col], EB_DOUT + "[" + k + "]",
                        gemmInsts[0][col], GEMM_NORTH_INPUTS + "[" + k + "]",
                        dataBits);
                connectSingleBit(topCell, prefix + "_valid" + k,
                        weightEbInsts[col], EB_DOUT_VALID + "[" + k + "]",
                        gemmInsts[0][col], GEMM_NORTH_INPUTS_VALID + "[" + k + "]");
            }
        }

        // =====================================================================
        // Input Edge Buffers → left column GEMM tiles
        // =====================================================================
        for (int row = 0; row < nRows; row++) {
            for (int k = 0; k < numUnitsWest; k++) {
                String prefix = "ieb_y" + row + "_to_tile_x0y" + row;
                connectBusPorts(topCell, prefix + "_data" + k,
                        inputEbInsts[row], EB_DOUT + "[" + k + "]",
                        gemmInsts[row][0], GEMM_WEST_INPUTS + "[" + k + "]",
                        dataBits);
                connectSingleBit(topCell, prefix + "_valid" + k,
                        inputEbInsts[row], EB_DOUT_VALID + "[" + k + "]",
                        gemmInsts[row][0], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
            }
        }

        // =====================================================================
        // GEMM horizontal nearest-neighbor (east → west)
        // =====================================================================
        for (int r = 0; r < nRows; r++) {
            for (int c = 0; c < nCols - 1; c++) {
                String prefix = "tile_x" + c + "y" + r + "_to_tile_x" + (c + 1) + "y" + r;
                for (int k = 0; k < eastCount; k++) {
                    connectBusPorts(topCell, prefix + "_east_data" + k,
                            gemmInsts[r][c], GEMM_EAST_OUTPUTS + "[" + k + "]",
                            gemmInsts[r][c + 1], GEMM_WEST_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, prefix + "_east_valid" + k,
                            gemmInsts[r][c], GEMM_EAST_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[r][c + 1], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // =====================================================================
        // GEMM vertical nearest-neighbor (south → north)
        // =====================================================================
        for (int r = 0; r < nRows - 1; r++) {
            for (int c = 0; c < nCols; c++) {
                String prefix = "tile_x" + c + "y" + r + "_to_tile_x" + c + "y" + (r + 1);
                for (int k = 0; k < southCount; k++) {
                    connectBusPorts(topCell, prefix + "_south_data" + k,
                            gemmInsts[r][c], GEMM_SOUTH_OUTPUTS + "[" + k + "]",
                            gemmInsts[r + 1][c], GEMM_NORTH_INPUTS + "[" + k + "]",
                            dataBits);
                    connectSingleBit(topCell, prefix + "_south_valid" + k,
                            gemmInsts[r][c], GEMM_SOUTH_OUTPUTS_VALID + "[" + k + "]",
                            gemmInsts[r + 1][c], GEMM_NORTH_INPUTS_VALID + "[" + k + "]");
                }
            }
        }

        // =====================================================================
        // Accumulator chain (vertical, per column)
        // =====================================================================
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < accumCount; k++) {
                // Tie top-row accumulator inputs to ground
                for (int i = 0; i < accumBits; i++) {
                    gndNet.createPortInst(
                            GEMM_ACCUM_INPUTS + "[" + k + "][" + i + "]", gemmInsts[0][col]);
                }

                for (int r = 0; r < nRows - 1; r++) {
                    connectBusPorts(topCell,
                            "tile_x" + col + "y" + r + "_to_tile_x" + col + "y" + (r + 1) + "_accum" + k,
                            gemmInsts[r][col], GEMM_ACCUM_OUTPUTS + "[" + k + "]",
                            gemmInsts[r + 1][col], GEMM_ACCUM_INPUTS + "[" + k + "]",
                            accumBits);
                }

                // Bottom GEMM tile accum_outputs → drain tile fifo_din
                connectBusPorts(topCell, "accum_to_drain_x" + col + "_k" + k,
                        gemmInsts[nRows - 1][col], GEMM_ACCUM_OUTPUTS + "[" + k + "]",
                        drainInsts[col], DRAIN_FIFO_DIN + "[" + k + "]",
                        accumBits);
            }
        }

        // =====================================================================
        // Drain tile AXI-stream chain
        // =====================================================================
        for (int i = 0; i < nCols - 1; i++) {
            connectBusPorts(topCell, "drain_chain_data_" + (i + 1) + "_to_" + i,
                    drainInsts[i + 1], DRAIN_M_TDATA,
                    drainInsts[i], DRAIN_S_TDATA, accumBits);
            connectSingleBit(topCell, "drain_chain_valid_" + (i + 1) + "_to_" + i,
                    drainInsts[i + 1], DRAIN_M_TVALID, drainInsts[i], DRAIN_S_TVALID);
            connectSingleBit(topCell, "drain_chain_ready_" + (i + 1) + "_to_" + i,
                    drainInsts[i], DRAIN_S_TREADY, drainInsts[i + 1], DRAIN_M_TREADY);
        }

        // Drain output → S2MM channel input
        connectBusPorts(topCell, "drain_to_s2mm_data",
                drainInsts[0], DRAIN_M_TDATA, s2mmInst, S2MM_S_DATA, accumBits);
        connectSingleBit(topCell, "drain_to_s2mm_valid",
                drainInsts[0], DRAIN_M_TVALID, s2mmInst, S2MM_S_VALID);
        connectSingleBit(topCell, "drain_to_s2mm_ready",
                s2mmInst, S2MM_S_READY, drainInsts[0], DRAIN_M_TREADY);

        // Tie rightmost drain tile's upstream inputs to GND
        for (int i = 0; i < accumBits; i++) {
            gndNet.createPortInst(DRAIN_S_TDATA + "[" + i + "]", drainInsts[nCols - 1]);
        }
        gndNet.createPortInst(DRAIN_S_TVALID, drainInsts[nCols - 1]);

        // =====================================================================
        // Right-edge east outputs → top-level
        // =====================================================================
        for (int r = 0; r < nRows; r++) {
            for (int k = 0; k < eastCount; k++) {
                EDIFPort eastPort = createBusPort(topCell,
                        "east_outputs_row" + r + "[" + k + "]", EDIFDirection.OUTPUT, dataBits);
                connectBusPortToTopLevel(topCell, "east_out_y" + r + "_k" + k,
                        eastPort, gemmInsts[r][nCols - 1],
                        GEMM_EAST_OUTPUTS + "[" + k + "]", dataBits);

                EDIFPort eastValidPort = topCell.createPort(
                        "east_outputs_valid_row" + r + "[" + k + "]", EDIFDirection.OUTPUT, 1);
                EDIFNet eastValidNet = topCell.createNet("east_out_valid_y" + r + "_k" + k);
                eastValidNet.createPortInst(eastValidPort);
                eastValidNet.createPortInst(GEMM_EAST_OUTPUTS_VALID + "[" + k + "]", gemmInsts[r][nCols - 1]);
            }
        }

        // Bottom-edge south outputs → top-level
        for (int c = 0; c < nCols; c++) {
            for (int k = 0; k < southCount; k++) {
                EDIFPort southPort = createBusPort(topCell,
                        "south_outputs_col" + c + "[" + k + "]", EDIFDirection.OUTPUT, dataBits);
                connectBusPortToTopLevel(topCell, "south_out_x" + c + "_k" + k,
                        southPort, gemmInsts[nRows - 1][c],
                        GEMM_SOUTH_OUTPUTS + "[" + k + "]", dataBits);

                EDIFPort southValidPort = topCell.createPort(
                        "south_outputs_valid_col" + c + "[" + k + "]", EDIFDirection.OUTPUT, 1);
                EDIFNet southValidNet = topCell.createNet("south_out_valid_x" + c + "_k" + k);
                southValidNet.createPortInst(southValidPort);
                southValidNet.createPortInst(GEMM_SOUTH_OUTPUTS_VALID + "[" + k + "]", gemmInsts[nRows - 1][c]);
            }
        }

        return design;
    }

    private static EDIFCell loadComponentCell(String precompileDir, RapidComponent component) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + SYNTH_DCP;
        return Design.readCheckpoint(dcpPath).getTopEDIFCell();
    }

    private static EDIFCellInst createBlackBox(EDIFCell parent, String name, EDIFCell cellType) {
        EDIFCellInst inst = parent.createChildCellInst(name, cellType);
        inst.addProperty(EDIFCellInst.BLACK_BOX_PROP_VERSAL, "1");
        return inst;
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
