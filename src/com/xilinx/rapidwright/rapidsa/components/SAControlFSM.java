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

public class SAControlFSM implements RapidComponent {

    private static final int SIZE_REG_WIDTH = 32;
    private static final int LATENCY_REG_WIDTH = 4;

    public static void setSAWidth(Design design, String instPrefix, int width) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/sa_width_reg", width, SIZE_REG_WIDTH);
    }

    public static void setSAHeight(Design design, String instPrefix, int height) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/sa_height_reg", height, SIZE_REG_WIDTH);
    }

    public static void setKDim(Design design, String instPrefix, int kDim) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/k_dim_reg", kDim, SIZE_REG_WIDTH);
    }

    public static void setAccumShiftPipelineLatency(Design design, String instPrefix, int latency) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/accum_shift_pipeline_latency_reg", latency, LATENCY_REG_WIDTH);
    }

    public static void setOutputWrPipelineLatency(Design design, String instPrefix, int latency) {
        RegisterInitTools.setRegisterValue(design, instPrefix + "/output_wr_pipeline_latency_reg", latency, LATENCY_REG_WIDTH);
    }


    @Override
    public String getComponentName() {
        return "SAControlFSM";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "sa_fsm.sv");
        lines.add("set_property top sa_fsm [current_fileset]");
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
                "SLICE_X72Y704:SLICE_X83Y715");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("start RIGHT");
        lines.add("a_rd_en BOTTOM");
        lines.add("b_rd_en LEFT");
        lines.add("output_wr_en BOTTOM");
        lines.add("done BOTTOM");
        lines.add("sa_accum_shift BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
