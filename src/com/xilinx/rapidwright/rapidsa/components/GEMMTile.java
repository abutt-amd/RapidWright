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
import java.util.TreeMap;

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
    public PBlock getSLRCrossingPBlock() {
        Device device = Device.getDevice("xcv80-lsva4737-2MHP-e-S");
        return new PBlock(device,
                "RAMB18_X3Y400:RAMB18_X3Y407 " +
                        "DSP_X2Y398:DSP_X3Y405 " +
                        "IRI_QUAD_X65Y3212:IRI_QUAD_X78Y3275 " +
                        "DSP58_CPLX_X1Y398:DSP58_CPLX_X1Y405 " +
                        "RAMB36_X3Y200:RAMB36_X3Y203 " +
                        "SLICE_X104Y796:SLICE_X123Y811");
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

    @Override
    public boolean shouldCompileSLRCrossing() {
        return true;
    }

    private static int countArrayPorts(EDIFPort[] ports, String baseName) {
        TreeMap<Integer, EDIFPort> indexed = new TreeMap<>();
        for (EDIFPort port : ports) {
            String portKey = port.getBusName();
            if (!portKey.startsWith(baseName + "[")) {
                continue;
            }
            String remainder = portKey.substring(baseName.length() + 1);
            int closeBracket = remainder.indexOf(']');
            if (closeBracket <= 0) {
                continue;
            }
            try {
                indexed.put(Integer.parseInt(remainder.substring(0, closeBracket)), port);
            } catch (NumberFormatException e) {
                // Bus range like "15:0" - skip
            }
        }
        if (!indexed.isEmpty()) {
            return indexed.lastKey() + 1;
        }
        return 0;
    }

    @Override
    public List<SLRCrossingConnection> getSLRCrossingConnections(Design d) {
        List<SLRCrossingConnection> connections = new ArrayList<>();
        EDIFPort[] ports = d.getTopEDIFCell().getPorts().toArray(new EDIFPort[0]);

        int southCount = countArrayPorts(ports, "south_outputs");
        for (int i = 0; i < southCount; i++) {
            connections.add(new SLRCrossingConnection("south_outputs[" + i + "]", "north_inputs[" + i + "]"));
        }

        int southValidCount = countArrayPorts(ports, "south_outputs_valid");
        for (int i = 0; i < southValidCount; i++) {
            connections.add(new SLRCrossingConnection("south_outputs_valid[" + i + "]", "north_inputs_valid[" + i + "]"));
        }

        int accumCount = countArrayPorts(ports, "accum_outputs");
        for (int i = 0; i < accumCount; i++) {
            connections.add(new SLRCrossingConnection("accum_outputs[" + i + "]", "accum_inputs[" + i + "]"));
        }

        return connections;
    }
}
