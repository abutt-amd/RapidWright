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
import com.xilinx.rapidwright.design.tools.RegisterInitTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DrainTile implements RapidComponent {
    private final int numL2Units;
    private final int elemRegWidth;

    private static final String COL_ELEM_REG_BASE_NAME = "col_elem_reg_reg";
    private static final String EXT_UPS_ELEM_REG_BASE_NAME = "ext_ups_elem_reg_reg";

    public DrainTile(int numL2Units, int elemRegWidth) {
        this.numL2Units = numL2Units;
        this.elemRegWidth = elemRegWidth;
    }

    public static void setColumnElements(Design design, String instPrefix, int value, int regWidth) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/" + COL_ELEM_REG_BASE_NAME, value, regWidth);
    }

    public static void setExternalUpstreamElements(Design design, String instPrefix, int value, int regWidth) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/" + EXT_UPS_ELEM_REG_BASE_NAME, value, regWidth);
    }

    @Override
    public String getComponentName() {
        return "DrainTile";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "fifo.sv");
        lines.add("read_verilog -sv " + rtlPath + "skid_buffer.sv");
        lines.add("read_verilog -sv " + rtlPath + "drain_l2_module.sv");
        lines.add("read_verilog -sv " + rtlPath + "drain_l2_tile.sv");
        lines.add("set_property generic {NUM_L2_UNITS=" + numL2Units
                + " ELEM_REG_WIDTH=" + elemRegWidth + " } [current_fileset]");
        lines.add("set_property top drain_l2_tile [current_fileset]");
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
        return new PBlock(device,
                "DSP_X0Y448:DSP_X1Y451 SLICE_X84Y896:SLICE_X99Y903 " +
                        "IRI_QUAD_X58Y3612:IRI_QUAD_X59Y3643 DSP58_CPLX_X0Y448:DSP58_CPLX_X0Y451");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("fifo_wr_en.* TOP");
        lines.add("fifo_din.* TOP");
        lines.add("reset TOP");
        lines.add("m_axis_downstream.* RIGHT");
        lines.add("s_axis_upstream.* LEFT");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
