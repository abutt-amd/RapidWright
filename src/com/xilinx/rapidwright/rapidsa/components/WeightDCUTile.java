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

public class WeightDCUTile implements RapidComponent {
    final private int width;
    private static final int ID_WIDTH = 8;
    private static final String ID_REG_BASE_NAME = "id_reg_reg";

    public WeightDCUTile(int width) {
        this.width = width;
    }

    /**
     * Updates the id_reg initialization value in a placed-and-routed design.
     *
     * @param design The pnr Design containing the id_reg flip-flops
     * @param idValue The new ID value to initialize the register with
     */
    public static void setIdRegValue(Design design, int idValue) {
        RegisterInitTools.setRegisterValue(design, ID_REG_BASE_NAME, idValue, ID_WIDTH);
    }

    @Override
    public String getComponentName() {
        return "WeightDCUTile";
    }

    @Override
    public List<String> getDesignTclLines() {
        String rtlPath = FileTools.getRapidWrightPath() + File.separator + "rapidsa-rtl"
                + File.separator + "os-sources" + File.separator;
        List<String> lines = new ArrayList<>();
        lines.add("read_verilog -sv " + rtlPath + "fifo.sv");
        lines.add("read_verilog -sv " + rtlPath + "fifo_tile.sv");
        lines.add("read_verilog -sv " + rtlPath + "skid_buffer.sv");
        lines.add("read_verilog -sv " + rtlPath + "dcu_fifo_tile_weight.sv");
        lines.add("set_property generic {NUM_UNITS=" + width + " } [current_fileset]");
        lines.add("set_property top dcu_fifo_tile_weight [current_fileset]");
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
                "DSP_X0Y444:DSP_X1Y447 SLICE_X84Y888:SLICE_X103Y895 " +
                        "IRI_QUAD_X58Y3580:IRI_QUAD_X59Y3611 DSP58_CPLX_X0Y444:DSP58_CPLX_X0Y447");
    }

    @Override
    public Map<EDIFPort, PBlockSide> getSideMap(Design d) {
        List<String> lines = new ArrayList<>();
        lines.add("s_.* RIGHT");
        lines.add("reset RIGHT");
        lines.add("m_.* LEFT");
        lines.add("rd_en RIGHT");
        lines.add("dout.* BOTTOM");
        Map<EDIFPort, PBlockSide> map = InlineFlopTools.parseSideMap(d.getNetlist(), lines);
        return map;
    }
}
