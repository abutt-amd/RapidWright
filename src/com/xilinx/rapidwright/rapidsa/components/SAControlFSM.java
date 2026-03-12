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
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SAControlFSM implements RapidComponent {
    @Override
    public String getComponentName() {
        return "SAControlFSM";
    }

    @Override
    public List<String> getVerilogFiles() {
        String rapidWrightPath = FileTools.getRapidWrightPath();
        String rapidSAVerilogPath = rapidWrightPath + File.separator + "rapidsa-rtl" +
                File.separator + "os-sources" + File.separator;
        List<String> files = new java.util.ArrayList<>();
        files.add(rapidSAVerilogPath + "sa_fsm.sv");
        return files;
    }

    @Override
    public String getTopVerilogName() {
        return "sa_fsm";
    }

    @Override
    public Map<String, String> getParameterMap() {
        Map<String, String> parameterMap = new HashMap<>();
        return parameterMap;
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
                "SLICE_X104Y808:SLICE_X111Y811");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("start LEFT");
        lines.add("a_rd_en BOTTOM");
        lines.add("b_rd_en RIGHT");
        lines.add("output_wr_en BOTTOM");
        lines.add("done BOTTOM");
        lines.add("sa_accum_shift BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
