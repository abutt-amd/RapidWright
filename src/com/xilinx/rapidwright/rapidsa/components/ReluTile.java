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
 * Parallel ReLU tile that sits between two systolic arrays. NUM_LANES
 * signed bytes pass through a per-lane sign mux (negative becomes zero)
 * and a single pipeline register. A shared valid bit is registered alongside.
 *
 * Component name encodes the lane count so multiple variants can coexist
 * in the same precompile output directory.
 */
public class ReluTile implements RapidComponent {

    private final int numLanes;
    private final int dataBits;

    public ReluTile(int numLanes, int dataBits) {
        this.numLanes = numLanes;
        this.dataBits = dataBits;
    }

    public int getNumLanes() { return numLanes; }
    public int getDataBits() { return dataBits; }

    @Override
    public String getComponentName() {
        return "ReluTile_" + numLanes;
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "relu_tile.sv");
        lines.add("set_property generic {NUM_LANES=" + numLanes
                + " DATA_BITS=" + dataBits + " } [current_fileset]");
        lines.add("set_property top relu_tile [current_fileset]");
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
                "DSP_X0Y438:DSP_X1Y441 IRI_QUAD_X58Y3532:IRI_QUAD_X59Y3563 " +
                        "DSP58_CPLX_X0Y438:DSP58_CPLX_X0Y441 SLICE_X84Y876:SLICE_X99Y883");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("data_in.* TOP");
        lines.add("reset TOP");
        lines.add("data_out.* BOTTOM");
        return InlineFlopTools.parseSideMap(d.getNetlist(), lines);
    }
}
