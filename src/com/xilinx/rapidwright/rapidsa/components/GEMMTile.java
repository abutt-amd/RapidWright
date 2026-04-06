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
import java.util.List;
import java.util.Map;

public class GEMMTile implements RapidComponent {
    private final int width;
    private final int height;

    public GEMMTile(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public String getComponentName() {
        return "GEMMTile";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rapidWrightPath = FileTools.getRapidWrightPath();
        if (rapidWrightPath == null) {
            throw new RuntimeException("RAPIDWRIGHT_PATH is null");
        }
        String rtlPath = rapidWrightPath + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "pe.sv");
        lines.add("read_verilog -sv " + rtlPath + "tile.sv");
        lines.add("set_property generic {WIDTH=" + width + " HEIGHT=" + height + " } [current_fileset]");
        lines.add("set_property top tile [current_fileset]");
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

    @Override
    public PBlock getPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        return new PBlock(device,
                "DSP_X2Y398:DSP_X3Y405 SLICE_X112Y796:SLICE_X119Y811 " +
                        "IRI_QUAD_X72Y3212:IRI_QUAD_X73Y3275 DSP58_CPLX_X1Y398:DSP58_CPLX_X1Y405");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("north_inputs.* TOP");
        lines.add("accum_shift RIGHT");
        lines.add("south_outputs.* BOTTOM");
        lines.add("east_outputs.* LEFT");
        lines.add("west_inputs.* RIGHT");
        lines.add("accum_inputs.* TOP");
        lines.add("accum_outputs.* BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
