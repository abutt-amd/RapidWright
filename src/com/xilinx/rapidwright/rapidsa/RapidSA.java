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
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.ArrayBuilderConfig;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.NorthDCUTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.SAControlFSM;
import com.xilinx.rapidwright.rapidsa.components.WestDCUTile;

import java.io.File;

public class RapidSA {
    public static void main(String[] args) {
        String partName = "xcv80-lsva4737-2MHP-e-S";
        Part part = PartNameTools.getPart(partName);
        //RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0);

        Design sa = RapidSANetlistBuilder.createSystolicArrayNetlist(8, 8, partName, "RapidSA");

        sa.getNetlist().exportEDIF("test.edf");

        GEMMTile tile = new GEMMTile(4, 4);

        String compOutputDir = "RapidSA" + File.separator + tile.getComponentName();
        Design kernel = Design.readCheckpoint(compOutputDir + File.separator + "pnr.dcp");
        EDIFTools.removeVivadoBusPreventionAnnotations(kernel.getNetlist());

        ArrayBuilderConfig config = new ArrayBuilderConfig(kernel, sa);
        config.setTopClockName("clk");
        config.setKernelClockName("clk");
        config.setOutOfContext(false);
        config.setRouteClock(false);

        // Create array builder with config
        ArrayBuilder ab = new ArrayBuilder(config);
        ab.initializeArrayBuilder();

        ab.createArray();

        // Fill remaining black boxes with their synthesized netlists
        Design arrayDesign = ab.getArray();
        fillBlackBox(arrayDesign, "RapidSA", new NorthDCUTile(8), "north_dcu_x0");
        fillBlackBox(arrayDesign, "RapidSA", new WestDCUTile(8), "west_dcu_y0");
        fillBlackBox(arrayDesign, "RapidSA", new SAControlFSM(), "sa_fsm");

        arrayDesign.setDesignOutOfContext(true);
        arrayDesign.writeCheckpoint("systolic_array_8x8.dcp");
    }

    /**
     * Replaces a black-box cell in the design with the full synthesized netlist
     * from the component's synth.dcp.
     *
     * @param design        The design containing black-box instances to fill
     * @param precompileDir Directory containing precompiled component DCPs
     * @param component     The RapidComponent whose synth.dcp to load
     * @param sampleInstName Name of one instance using the black-box cell (used to find the cell type)
     */
    private static void fillBlackBox(Design design, String precompileDir,
                                     RapidComponent component, String sampleInstName) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + "synth.dcp";
        Design synthDesign = Design.readCheckpoint(dcpPath);
        EDIFTools.removeVivadoBusPreventionAnnotations(synthDesign.getNetlist());

        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        // Find the black-box cell via a known instance
        EDIFCellInst sampleInst = topCell.getCellInst(sampleInstName);
        EDIFCell blackBoxCell = sampleInst.getCellType();

        // Rename the black box to avoid name collision during migration
        blackBoxCell.rename(blackBoxCell.getName() + EDIFTools.getUniqueSuffix());

        // Migrate the full synthesized cell and its sub-cells
        EDIFCell synthCell = synthDesign.getTopEDIFCell();
        netlist.migrateCellAndSubCells(synthCell, true);

        // Update all instances using the old black-box cell to the real cell
        for (EDIFCellInst inst : topCell.getCellInsts()) {
            if (inst.getCellType() == blackBoxCell) {
                inst.setCellType(synthCell);
                inst.removeBlackBoxProperty();
            }
        }

        // Remove the old black-box cell
        blackBoxCell.getLibrary().removeCell(blackBoxCell);
    }
}
