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
import com.xilinx.rapidwright.rapidsa.components.NorthDCUTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.SAControlFSM;
import com.xilinx.rapidwright.rapidsa.components.WestDCUTile;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * Assembles precompiled component tiles into a complete systolic array netlist.
 *
 * Topology (nRows x nCols):
 *
 *                    NorthDCU_x0  NorthDCU_x1  ...  NorthDCU_x{nCols-1}
 *
 *   WestDCU_y0      tile_x0y0    tile_x1y0    ...  tile_x{nCols-1}y0
 *   WestDCU_y1      tile_x0y1    tile_x1y1    ...  tile_x{nCols-1}y1
 *     ...             ...          ...               ...
 *   WestDCU_y{nR-1} tile_x0y{N}  tile_x1y{N}  ...  tile_x{nCols-1}y{nRows-1}
 *
 *                                SAControlFSM
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

    // DCU tile ports
    private static final String DCU_S_DATA     = "s_data";
    private static final String DCU_S_TAG      = "s_tag";
    private static final String DCU_S_VALID    = "s_valid";
    private static final String DCU_S_READY    = "s_ready";
    private static final String DCU_M_DATA     = "m_data";
    private static final String DCU_M_TAG      = "m_tag";
    private static final String DCU_M_VALID    = "m_valid";
    private static final String DCU_M_READY    = "m_ready";
    private static final String DCU_RD_EN      = "rd_en";
    private static final String DCU_RD_EN_OUT  = "rd_en_out";
    private static final String DCU_DOUT       = "dout";
    private static final String DCU_DOUT_VALID = "dout_valid";

    // Drain tile ports
    private static final String DRAIN_FIFO_WR_EN = "fifo_wr_en";
    private static final String DRAIN_FIFO_DIN   = "fifo_din";
    private static final String DRAIN_M_TDATA    = "m_axis_downstream_tdata";
    private static final String DRAIN_M_TVALID   = "m_axis_downstream_tvalid";
    private static final String DRAIN_M_TREADY   = "m_axis_downstream_tready";
    private static final String DRAIN_S_TDATA    = "s_axis_upstream_tdata";
    private static final String DRAIN_S_TVALID   = "s_axis_upstream_tvalid";
    private static final String DRAIN_S_TREADY   = "s_axis_upstream_tready";

    // FSM ports
    private static final String FSM_START          = "start";
    private static final String FSM_A_RD_EN        = "a_rd_en";
    private static final String FSM_B_RD_EN        = "b_rd_en";
    private static final String FSM_OUTPUT_WR_EN   = "output_wr_en";
    private static final String FSM_DONE           = "done";
    private static final String FSM_SA_ACCUM_SHIFT = "sa_accum_shift";

    /**
     * Creates a complete systolic array netlist by assembling precompiled component tiles.
     *
     * @param nRows          Number of rows in the systolic array
     * @param nCols          Number of columns in the systolic array
     * @param partName       The target part name (e.g. "xcv80-lsva4737-2MHP-e-S")
     * @param precompileDir  Directory containing precompiled component DCPs
     * @return A Design containing the assembled systolic array netlist
     */
    public static Design createSystolicArrayNetlist(int nRows, int nCols, String partName,
                                                    String precompileDir) {
        RapidComponent gemmComponent = new GEMMTile(nCols, nRows);
        RapidComponent northDcuComponent = new NorthDCUTile(nCols);
        RapidComponent westDcuComponent = new WestDCUTile(nRows);
        RapidComponent fsmComponent = new SAControlFSM();
        if (nRows <= 0 || nCols <= 0) {
            throw new IllegalArgumentException(
                    "Array dimensions must be positive: nRows=" + nRows + ", nCols=" + nCols);
        }

        // Load component cells from precompiled DCPs
        EDIFCell gemmCell = loadComponentCell(precompileDir, gemmComponent);
        EDIFCell northDcuCell = loadComponentCell(precompileDir, northDcuComponent);
        EDIFCell westDcuCell = loadComponentCell(precompileDir, westDcuComponent);
        EDIFCell fsmCell = loadComponentCell(precompileDir, fsmComponent);

        // Get clock and reset port names from component definitions
        String gemmClk = gemmComponent.getClkName();
        String northDcuClk = northDcuComponent.getClkName();
        String northDcuRst = northDcuComponent.getResetName();
        String westDcuClk = westDcuComponent.getClkName();
        String westDcuRst = westDcuComponent.getResetName();
        String fsmClk = fsmComponent.getClkName();
        String fsmRst = fsmComponent.getResetName();

        // Create top-level design and migrate component cells (collision handling for shared dcu_fifo_tile)
        Design design = new Design("systolic_array", partName);
        design.setAutoIOBuffers(false);
        EDIFNetlist netlist = design.getNetlist();

        netlist.migrateCellAndSubCells(gemmCell, true);
        netlist.migrateCellAndSubCells(northDcuCell, true);
        netlist.migrateCellAndSubCells(westDcuCell, true);
        netlist.migrateCellAndSubCells(fsmCell, true);

        // Strip internals to make each cell a black box
        gemmCell.makePrimitive();
        northDcuCell.makePrimitive();
        westDcuCell.makePrimitive();
        fsmCell.makePrimitive();

        EDIFCell topCell = netlist.getTopCell();

        // Determine bus widths and array sizes from component ports
        int dataWidth = getPortWidth(northDcuCell, DCU_S_DATA);
        int tagWidth = getPortWidth(northDcuCell, DCU_S_TAG);
        int dataBits = getPortWidth(gemmCell, GEMM_NORTH_INPUTS + "[0]");
        int accumBits = getPortWidth(gemmCell, GEMM_ACCUM_INPUTS + "[0]");
        int numUnitsNorth = countArrayPorts(northDcuCell, DCU_DOUT);
        int numUnitsWest = countArrayPorts(westDcuCell, DCU_DOUT);
        int eastCount = countArrayPorts(gemmCell, GEMM_EAST_OUTPUTS);
        int southCount = countArrayPorts(gemmCell, GEMM_SOUTH_OUTPUTS);
        int accumCount = countArrayPorts(gemmCell, GEMM_ACCUM_INPUTS);

        // Create DrainTile component (needs accumCount from GEMM cell)
        RapidComponent drainComponent = new DrainTile(accumCount, 8);
        EDIFCell drainCell = loadComponentCell(precompileDir, drainComponent);
        String drainClk = drainComponent.getClkName();
        String drainRst = drainComponent.getResetName();
        netlist.migrateCellAndSubCells(drainCell, true);
        drainCell.makePrimitive();

        int dcuDoutWidth = getPortWidth(northDcuCell, DCU_DOUT + "[0]");
        if (dcuDoutWidth != dataBits) {
            throw new RuntimeException(
                    "DCU dout width (" + dcuDoutWidth + ") does not match GEMM tile input width (" + dataBits + ")");
        }

        // Create black box instances (x = column left-to-right, y = row top-to-bottom)
        EDIFCellInst[][] gemmInsts = new EDIFCellInst[nRows][nCols];
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                gemmInsts[r][c] = createBlackBox(topCell, "tile_x" + c + "y" + r, gemmCell);

        EDIFCellInst[] northDcuInsts = new EDIFCellInst[nCols];
        for (int i = 0; i < nCols; i++)
            northDcuInsts[i] = createBlackBox(topCell, "north_dcu_x" + i, northDcuCell);

        EDIFCellInst[] westDcuInsts = new EDIFCellInst[nRows];
        for (int i = 0; i < nRows; i++)
            westDcuInsts[i] = createBlackBox(topCell, "west_dcu_y" + i, westDcuCell);

        EDIFCellInst fsmInst = createBlackBox(topCell, "sa_fsm", fsmCell);

        EDIFCellInst[] drainInsts = new EDIFCellInst[nCols];
        for (int i = 0; i < nCols; i++)
            drainInsts[i] = createBlackBox(topCell, "drain_x" + i, drainCell);

        // Top-level ports
        EDIFPort topClk = topCell.createPort("clk", EDIFDirection.INPUT, 1);
        EDIFPort topRstN = topCell.createPort("rst_n", EDIFDirection.INPUT, 1);
        EDIFPort topStart = topCell.createPort("start", EDIFDirection.INPUT, 1);
        EDIFPort topDone = topCell.createPort("done", EDIFDirection.OUTPUT, 1);

        EDIFPort topBSData = createBusPort(topCell, "b_s_data", EDIFDirection.INPUT, dataWidth);
        EDIFPort topBSTag = createBusPort(topCell, "b_s_tag", EDIFDirection.INPUT, tagWidth);
        EDIFPort topBSValid = topCell.createPort("b_s_valid", EDIFDirection.INPUT, 1);
        EDIFPort topBSReady = topCell.createPort("b_s_ready", EDIFDirection.OUTPUT, 1);
        EDIFPort topBMData = createBusPort(topCell, "b_m_data", EDIFDirection.OUTPUT, dataWidth);
        EDIFPort topBMTag = createBusPort(topCell, "b_m_tag", EDIFDirection.OUTPUT, tagWidth);
        EDIFPort topBMValid = topCell.createPort("b_m_valid", EDIFDirection.OUTPUT, 1);
        EDIFPort topBMReady = topCell.createPort("b_m_ready", EDIFDirection.INPUT, 1);

        EDIFPort topASData = createBusPort(topCell, "a_s_data", EDIFDirection.INPUT, dataWidth);
        EDIFPort topASTag = createBusPort(topCell, "a_s_tag", EDIFDirection.INPUT, tagWidth);
        EDIFPort topASValid = topCell.createPort("a_s_valid", EDIFDirection.INPUT, 1);
        EDIFPort topASReady = topCell.createPort("a_s_ready", EDIFDirection.OUTPUT, 1);
        EDIFPort topAMData = createBusPort(topCell, "a_m_data", EDIFDirection.OUTPUT, dataWidth);
        EDIFPort topAMTag = createBusPort(topCell, "a_m_tag", EDIFDirection.OUTPUT, tagWidth);
        EDIFPort topAMValid = topCell.createPort("a_m_valid", EDIFDirection.OUTPUT, 1);
        EDIFPort topAMReady = topCell.createPort("a_m_ready", EDIFDirection.INPUT, 1);

        // Clock: fans out to all instances
        EDIFNet clkNet = topCell.createNet("clk");
        clkNet.createPortInst(topClk);
        clkNet.createPortInst(fsmClk, fsmInst);
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                clkNet.createPortInst(gemmClk, gemmInsts[r][c]);
        for (int i = 0; i < nCols; i++)
            clkNet.createPortInst(northDcuClk, northDcuInsts[i]);
        for (int i = 0; i < nRows; i++)
            clkNet.createPortInst(westDcuClk, westDcuInsts[i]);
        for (int i = 0; i < nCols; i++)
            clkNet.createPortInst(drainClk, drainInsts[i]);

        // rst_n: fans out to all DCU instances
        EDIFNet rstNNet = topCell.createNet("rst_n");
        rstNNet.createPortInst(topRstN);
        if (northDcuRst != null) {
            for (int i = 0; i < nCols; i++)
                rstNNet.createPortInst(northDcuRst, northDcuInsts[i]);
        }
        if (westDcuRst != null) {
            for (int i = 0; i < nRows; i++)
                rstNNet.createPortInst(westDcuRst, westDcuInsts[i]);
        }
        if (drainRst != null) {
            for (int i = 0; i < nCols; i++)
                rstNNet.createPortInst(drainRst, drainInsts[i]);
        }

        // FSM control connections
        connectSingleBit(topCell, "start", topStart, fsmInst, FSM_START);
        connectSingleBit(topCell, "done", topDone, fsmInst, FSM_DONE);

        // FSM output_wr_en -> all drain tiles' fifo_wr_en (all bits)
        EDIFNet outputWrEnNet = topCell.createNet("output_wr_en");
        outputWrEnNet.createPortInst(FSM_OUTPUT_WR_EN, fsmInst);
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < accumCount; k++) {
                outputWrEnNet.createPortInst(DRAIN_FIFO_WR_EN + "[" + k + "]", drainInsts[col]);
            }
        }

        if (fsmRst != null) {
            EDIFPort topReset = topCell.createPort("reset", EDIFDirection.INPUT, 1);
            connectSingleBit(topCell, "reset", topReset, fsmInst, fsmRst);
        }

        // FSM sa_accum_shift -> all GEMM tiles
        EDIFNet accumShiftNet = topCell.createNet("sa_accum_shift");
        accumShiftNet.createPortInst(FSM_SA_ACCUM_SHIFT, fsmInst);
        for (int r = 0; r < nRows; r++)
            for (int c = 0; c < nCols; c++)
                accumShiftNet.createPortInst(GEMM_ACCUM_SHIFT, gemmInsts[r][c]);

        // FSM b_rd_en -> NorthDCU rd_en daisy chain
        EDIFNet bRdEnNet = topCell.createNet("b_rd_en");
        bRdEnNet.createPortInst(FSM_B_RD_EN, fsmInst);
        bRdEnNet.createPortInst(DCU_RD_EN, northDcuInsts[0]);
        for (int i = 0; i < nCols - 1; i++) {
            EDIFNet rdEnChain = topCell.createNet("b_rd_en_chain_" + i + "_to_" + (i + 1));
            rdEnChain.createPortInst(DCU_RD_EN_OUT, northDcuInsts[i]);
            rdEnChain.createPortInst(DCU_RD_EN, northDcuInsts[i + 1]);
        }

        // FSM a_rd_en -> WestDCU rd_en daisy chain
        EDIFNet aRdEnNet = topCell.createNet("a_rd_en");
        aRdEnNet.createPortInst(FSM_A_RD_EN, fsmInst);
        aRdEnNet.createPortInst(DCU_RD_EN, westDcuInsts[0]);
        for (int i = 0; i < nRows - 1; i++) {
            EDIFNet rdEnChain = topCell.createNet("a_rd_en_chain_" + i + "_to_" + (i + 1));
            rdEnChain.createPortInst(DCU_RD_EN_OUT, westDcuInsts[i]);
            rdEnChain.createPortInst(DCU_RD_EN, westDcuInsts[i + 1]);
        }

        // NorthDCU daisy chain (B matrix)
        connectDaisyChain(topCell, northDcuInsts,
                topBSData, topBSTag, topBSValid, topBSReady,
                topBMData, topBMTag, topBMValid, topBMReady,
                "b", dataWidth, tagWidth);

        // WestDCU daisy chain (A matrix)
        connectDaisyChain(topCell, westDcuInsts,
                topASData, topASTag, topASValid, topASReady,
                topAMData, topAMTag, topAMValid, topAMReady,
                "a", dataWidth, tagWidth);

        // NorthDCU -> top row GEMM tiles
        for (int col = 0; col < nCols; col++) {
            for (int k = 0; k < numUnitsNorth; k++) {
                String prefix = "ndcu_x" + col + "_to_tile_x" + col + "y0";
                connectBusPorts(topCell, prefix + "_data" + k,
                        northDcuInsts[col], DCU_DOUT + "[" + k + "]",
                        gemmInsts[0][col], GEMM_NORTH_INPUTS + "[" + k + "]",
                        dataBits);
                connectSingleBit(topCell, prefix + "_valid" + k,
                        northDcuInsts[col], DCU_DOUT_VALID + "[" + k + "]",
                        gemmInsts[0][col], GEMM_NORTH_INPUTS_VALID + "[" + k + "]");
            }
        }

        // WestDCU -> left column GEMM tiles
        for (int row = 0; row < nRows; row++) {
            for (int k = 0; k < numUnitsWest; k++) {
                String prefix = "wdcu_y" + row + "_to_tile_x0y" + row;
                connectBusPorts(topCell, prefix + "_data" + k,
                        westDcuInsts[row], DCU_DOUT + "[" + k + "]",
                        gemmInsts[row][0], GEMM_WEST_INPUTS + "[" + k + "]",
                        dataBits);
                connectSingleBit(topCell, prefix + "_valid" + k,
                        westDcuInsts[row], DCU_DOUT_VALID + "[" + k + "]",
                        gemmInsts[row][0], GEMM_WEST_INPUTS_VALID + "[" + k + "]");
            }
        }

        // GEMM horizontal nearest-neighbor (east -> west)
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

        // GEMM vertical nearest-neighbor (south -> north)
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

        // Accumulator chain (vertical, per column)
        EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, topCell, netlist);
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

                // Bottom GEMM tile accum_outputs -> drain tile fifo_din
                connectBusPorts(topCell, "accum_to_drain_x" + col + "_k" + k,
                        gemmInsts[nRows - 1][col], GEMM_ACCUM_OUTPUTS + "[" + k + "]",
                        drainInsts[col], DRAIN_FIFO_DIN + "[" + k + "]",
                        accumBits);
            }
        }

        // Drain tile AXI-stream chain (drain_x[i+1].m_downstream -> drain_x[i].s_upstream)
        for (int i = 0; i < nCols - 1; i++) {
            connectBusPorts(topCell, "drain_chain_data_" + (i + 1) + "_to_" + i,
                    drainInsts[i + 1], DRAIN_M_TDATA,
                    drainInsts[i], DRAIN_S_TDATA, accumBits);
            connectSingleBit(topCell, "drain_chain_valid_" + (i + 1) + "_to_" + i,
                    drainInsts[i + 1], DRAIN_M_TVALID, drainInsts[i], DRAIN_S_TVALID);
            connectSingleBit(topCell, "drain_chain_ready_" + (i + 1) + "_to_" + i,
                    drainInsts[i], DRAIN_S_TREADY, drainInsts[i + 1], DRAIN_M_TREADY);
        }

        // Top-level drain output ports (drain_x0's downstream)
        EDIFPort topDrainTdata = createBusPort(topCell, "drain_tdata", EDIFDirection.OUTPUT, accumBits);
        EDIFPort topDrainTvalid = topCell.createPort("drain_tvalid", EDIFDirection.OUTPUT, 1);
        EDIFPort topDrainTready = topCell.createPort("drain_tready", EDIFDirection.INPUT, 1);
        connectBusPortToTopLevel(topCell, "drain_out_tdata", topDrainTdata, drainInsts[0], DRAIN_M_TDATA, accumBits);
        connectSingleBit(topCell, "drain_out_tvalid", topDrainTvalid, drainInsts[0], DRAIN_M_TVALID);
        connectSingleBit(topCell, "drain_out_tready", topDrainTready, drainInsts[0], DRAIN_M_TREADY);

        // Tie rightmost drain tile's upstream inputs to GND (no external upstream)
        for (int i = 0; i < accumBits; i++) {
            gndNet.createPortInst(DRAIN_S_TDATA + "[" + i + "]", drainInsts[nCols - 1]);
        }
        gndNet.createPortInst(DRAIN_S_TVALID, drainInsts[nCols - 1]);

        // Right-edge east outputs -> top-level
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

        // Bottom-edge south outputs -> top-level
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

    /**
     * Connects a daisy chain of DCU instances with top-level AXI-stream ports.
     * Chain: top s_* -> DCU[0] ... DCU[i].m_* -> DCU[i+1].s_* ... DCU[n-1] -> top m_*
     */
    private static void connectDaisyChain(EDIFCell topCell, EDIFCellInst[] dcuInsts,
                                          EDIFPort topSData, EDIFPort topSTag,
                                          EDIFPort topSValid, EDIFPort topSReady,
                                          EDIFPort topMData, EDIFPort topMTag,
                                          EDIFPort topMValid, EDIFPort topMReady,
                                          String prefix, int dataWidth, int tagWidth) {
        int n = dcuInsts.length;

        connectBusPortToTopLevel(topCell, prefix + "_s_data", topSData, dcuInsts[0], DCU_S_DATA, dataWidth);
        connectBusPortToTopLevel(topCell, prefix + "_s_tag", topSTag, dcuInsts[0], DCU_S_TAG, tagWidth);
        connectSingleBit(topCell, prefix + "_s_valid", topSValid, dcuInsts[0], DCU_S_VALID);
        connectSingleBit(topCell, prefix + "_s_ready", topSReady, dcuInsts[0], DCU_S_READY);

        for (int i = 0; i < n - 1; i++) {
            connectBusPorts(topCell, prefix + "_chain_data_" + i + "_to_" + (i + 1),
                    dcuInsts[i], DCU_M_DATA, dcuInsts[i + 1], DCU_S_DATA, dataWidth);
            connectBusPorts(topCell, prefix + "_chain_tag_" + i + "_to_" + (i + 1),
                    dcuInsts[i], DCU_M_TAG, dcuInsts[i + 1], DCU_S_TAG, tagWidth);
            connectSingleBit(topCell, prefix + "_chain_valid_" + i + "_to_" + (i + 1),
                    dcuInsts[i], DCU_M_VALID, dcuInsts[i + 1], DCU_S_VALID);
            connectSingleBit(topCell, prefix + "_chain_ready_" + i + "_to_" + (i + 1),
                    dcuInsts[i], DCU_M_READY, dcuInsts[i + 1], DCU_S_READY);
        }

        connectBusPortToTopLevel(topCell, prefix + "_m_data", topMData, dcuInsts[n - 1], DCU_M_DATA, dataWidth);
        connectBusPortToTopLevel(topCell, prefix + "_m_tag", topMTag, dcuInsts[n - 1], DCU_M_TAG, tagWidth);
        connectSingleBit(topCell, prefix + "_m_valid", topMValid, dcuInsts[n - 1], DCU_M_VALID);
        connectSingleBit(topCell, prefix + "_m_ready", topMReady, dcuInsts[n - 1], DCU_M_READY);
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

    /**
     * Counts array elements for ports with the given base name.
     * E.g., for ports dout[0][7:0] through dout[3][7:0], returns 4.
     */
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
