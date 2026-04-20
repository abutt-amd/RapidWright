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

/**
 * RapidComponent for an Edge Buffer tile that receives K-major broadcast data
 * and captures per-unit bytes via static byte lane selection.
 *
 * Replaces the old DCU FIFO tiles. Two variants:
 * - WEIGHT: placed above the array, feeds GEMM north_inputs
 * - INPUT: placed to the right of the array, feeds GEMM west_inputs
 */
public class EdgeBufferTile implements RapidComponent {

    public enum Type { WEIGHT, INPUT }

    private final int numUnits;
    private final Type type;

    public EdgeBufferTile(int numUnits, Type type) {
        this.numUnits = numUnits;
        this.type = type;
    }

    /**
     * Sets the byte lane register for a specific unit within the tile.
     */
    public static void setByteLane(Design design, String instPrefix, int unit, int lane) {
//        RegisterInitTools.setRegisterValue(design, instPrefix + "/byte_lane_reg[" + unit + "]", lane, 4);
    }

    /**
     * Sets the word index register for a specific unit within the tile (for N > 16).
     */
    public static void setWordIndex(Design design, String instPrefix, int unit, int wordIdx) {
//        RegisterInitTools.setRegisterValue(design, instPrefix + "/word_index_reg[" + unit + "]", wordIdx, 4);
    }

    @Override
    public String getComponentName() {
        return type == Type.WEIGHT ? "WeightEdgeBuffer" : "InputEdgeBuffer";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        String moduleName = (type == Type.WEIGHT) ? "weight_edge_buffer_tile" : "input_edge_buffer_tile";
        lines.add("read_verilog -sv " + rtlPath + "fifo.sv");
        lines.add("read_verilog -sv " + rtlPath + "fifo_tile.sv");
        lines.add("read_verilog -sv " + rtlPath + moduleName + ".sv");
        lines.add("set_property generic {NUM_UNITS=" + numUnits + " } [current_fileset]");
        lines.add("set_property top " + moduleName + " [current_fileset]");
        return lines;
    }

    @Override
    public String getClkName() {
        return "clk";
    }

    @Override
    public String getResetName() {
        return "reset";
    }

    @Override
    public PBlock getPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        if (type == Type.WEIGHT) {
            return new PBlock(device,
                    "DSP_X0Y444:DSP_X1Y447 SLICE_X84Y888:SLICE_X103Y895 "
                    + "IRI_QUAD_X58Y3580:IRI_QUAD_X59Y3611 DSP58_CPLX_X0Y444:DSP58_CPLX_X0Y447");
        } else {
            return new PBlock(device,
                    "SLICE_X72Y880:SLICE_X83Y895");
        }
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        if (type == Type.WEIGHT) {
            // Weight EB: data flows right-to-left along top edge, outputs go down
            lines.add("s_.* RIGHT");
            lines.add("reset RIGHT");
            lines.add("m_.* LEFT");
            lines.add("rd_en RIGHT");
            lines.add("dout.* BOTTOM");
        } else {
            // Input EB: data flows top-to-bottom along right edge, outputs go left
            lines.add("s_.* TOP");
            lines.add("reset TOP");
            lines.add("m_.* BOTTOM");
            lines.add("rd_en TOP");
            lines.add("dout.* LEFT");
        }
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
