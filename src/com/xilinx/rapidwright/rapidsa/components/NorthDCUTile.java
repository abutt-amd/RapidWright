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

public class NorthDCUTile implements RapidComponent {
    final private int width;

    public NorthDCUTile(int width) {
        this.width = width;
    }

    @Override
    public String getComponentName() {
        return "NorthDCUTile";
    }

    @Override
    public List<String> getVerilogFiles() {
        String rapidWrightPath = FileTools.getRapidWrightPath();
        String rapidSAVerilogPath = rapidWrightPath + File.separator + "rapidsa-rtl" +
                File.separator + "os-sources" + File.separator;
        List<String> files = new java.util.ArrayList<>();
        files.add(rapidSAVerilogPath + "fifo.sv");
        files.add(rapidSAVerilogPath + "fifo_tile.sv");
        files.add(rapidSAVerilogPath + "skid_buffer.sv");
        files.add(rapidSAVerilogPath + "daisy_chain_loader.sv");
        files.add(rapidSAVerilogPath + "dcu_fifo_tile_north.sv");
        return files;
    }

    @Override
    public String getTopVerilogName() {
        return "dcu_fifo_tile_north";
    }

    @Override
    public Map<String, String> getParameterMap() {
        Map<String, String> parameterMap = new HashMap<>();
        parameterMap.put("NUM_UNITS", String.valueOf(width));
        return parameterMap;
    }

    @Override
    public String getClkName() {
        return "clk";
    }

    @Override
    public String getResetName() {
        return "rst_n";
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
        lines.add("s_.* LEFT");
        lines.add("rst_n LEFT");
        lines.add("m_.* RIGHT");
        lines.add("rd_en LEFT");
        lines.add("dout.* BOTTOM");
        Map<EDIFPort, PBlockSide> map = InlineFlopTools.parseSideMap(d.getNetlist(), lines);
        return map;
    }
}
