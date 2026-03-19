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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.ArrayBuilderConfig;
import com.xilinx.rapidwright.design.tools.FlopTreeTools;
import com.xilinx.rapidwright.design.tools.RegisterInitTools;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.NorthDCUTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.SAControlFSM;
import com.xilinx.rapidwright.rapidsa.components.WestDCUTile;
import com.xilinx.rapidwright.util.Pair;
import joptsimple.OptionParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

public class RapidSA {
    private static final int ID_WIDTH = 8;

    public static void main(String[] args) {
        String partName = "xcv80-lsva4737-2MHP-e-S";
        Part part = PartNameTools.getPart(partName);

        OptionParser parser = new OptionParser();
        parser.accepts("precompile", "Run precompilation of all RapidSA components and exit");
        parser.accepts("help", "Print help message");
        joptsimple.OptionSet options = parser.parse(args);

        if (options.has("help")) {
            try {
                parser.printHelpOn(System.out);
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
            return;
        }

        if (options.has("precompile")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0);
            return;
        }

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

        // Place NorthDCU tiles above the array
        Module northDcuModule = loadRelocatableModule("RapidSA", new NorthDCUTile(8), ab.getArray());
        placeNorthDCUTiles(ab, 8, northDcuModule);

        // Place WestDCU tiles left of the array
        Module westDcuModule = loadRelocatableModule("RapidSA", new WestDCUTile(8), ab.getArray());
        placeWestDCUTiles(ab, 8, westDcuModule);

        // Place SA FSM near top-left of the design
        Module fsmModule = loadRelocatableModule("RapidSA", new SAControlFSM(), ab.getArray());
        placeFSM(ab, fsmModule);

        Design arrayDesign = ab.getArray();

        // Insert flop tree on the accum_shift net
        arrayDesign.flattenDesign();
        EDIFTools.uniqueifyNetlist(arrayDesign);
        FlopTreeTools.insertFlopTreeForNet(arrayDesign, "sa_accum_shift", "clk", 4, 3);

        // Add clock constraint
        arrayDesign.addXDCConstraint("create_clock -period 2.0 -name clk [get_ports clk]");

        arrayDesign.setDesignOutOfContext(true);
        arrayDesign.writeCheckpoint("systolic_array_8x8.dcp");
    }

    /**
     * Loads a component's pnr.dcp and creates a relocatable Module, removing
     * clock routing, BUFGs, and orphaned static tie-off cells that inflate
     * the bounding box.
     */
    private static Module loadRelocatableModule(String precompileDir, RapidComponent component,
                                                 Design targetDesign) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + "pnr.dcp";
        Design pnrDesign = Design.readCheckpoint(dcpPath);
        EDIFTools.removeVivadoBusPreventionAnnotations(pnrDesign.getNetlist());
        ArrayBuilder.removeBUFGs(pnrDesign);
        Net clkNet = pnrDesign.getNet(component.getClkName());
        if (clkNet != null) {
            clkNet.unroute();
        }
        // Remove SiteInsts that only contain <LOCKED> cells or are static sources
        List<SiteInst> toRemove = new ArrayList<>();
        for (SiteInst si : pnrDesign.getSiteInsts()) {
            if (si.getName().startsWith(SiteInst.STATIC_SOURCE)) {
                toRemove.add(si);
                continue;
            }
            boolean allLocked = !si.getCells().isEmpty();
            for (Cell c : si.getCells()) {
                if (!c.getName().equals("<LOCKED>")) {
                    allLocked = false;
                    break;
                }
            }
            if (allLocked) {
                toRemove.add(si);
            }
        }
        for (SiteInst si : toRemove) {
            pnrDesign.removeSiteInst(si);
        }
        Module module = new Module(pnrDesign);
        module.calculateAllValidPlacements(targetDesign.getDevice());
        return module;
    }

    /**
     * Places NorthDCU module instances physically above the array,
     * aligned with each column's top-row centroid.
     */
    private static void placeNorthDCUTiles(ArrayBuilder ab, int nCols, Module dcuModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = dcuModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Compute the array's top row from all placed sites
        int arrayTopRow = Integer.MAX_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayTopRow = Math.min(arrayTopRow, si.getTile().getRow());
        }

        for (int col = 0; col < nCols; col++) {
            Site targetCentroid = centroidMap.get(new Pair<>(col, 0));
            String instName = "north_dcu_x" + col;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Bottom of DCU bounding box must be above top of array
                if (candidateBB.getMaxRow() >= arrayTopRow) {
                    continue;
                }

                // Check no overlap with any placed bounding box
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : placedBoundingBoxes) {
                    if (existing.overlaps(candidateBB)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(anchor.getTile().getRow() - targetCentroid.getTile().getRow());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAnchor = anchor;
                }
            }

            if (bestAnchor == null) {
                throw new RuntimeException("Could not find valid placement for " + instName);
            }

            if (!mi.place(bestAnchor, true, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile()));

            // Set id_reg init value to column index
            RegisterInitTools.setRegisterValue(arrayDesign, instName + "/id_reg_reg", col, ID_WIDTH);

            System.out.println("  ** PLACED NorthDCU: " + instName + " at " + bestAnchor
                    + " (id_reg=" + col + ")");
        }
    }

    /**
     * Places WestDCU module instances physically left of the array,
     * aligned with each row's first-column centroid.
     */
    private static void placeWestDCUTiles(ArrayBuilder ab, int nRows, Module dcuModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = dcuModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Compute the array's leftmost column from all placed sites
        int arrayLeftCol = Integer.MAX_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayLeftCol = Math.min(arrayLeftCol, si.getTile().getColumn());
        }

        for (int row = 0; row < nRows; row++) {
            Site targetCentroid = centroidMap.get(new Pair<>(0, row));
            String instName = "west_dcu_y" + row;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Right edge of DCU bounding box must be left of array
                if (candidateBB.getMaxColumn() >= arrayLeftCol) {
                    continue;
                }

                // Check no overlap with any placed bounding box
                boolean overlaps = false;
                for (RelocatableTileRectangle existing : placedBoundingBoxes) {
                    if (existing.overlaps(candidateBB)) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(anchor.getTile().getRow() - targetCentroid.getTile().getRow());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestAnchor = anchor;
                }
            }

            if (bestAnchor == null) {
                throw new RuntimeException("Could not find valid placement for " + instName);
            }

            if (!mi.place(bestAnchor, true, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile()));

            // Set id_reg init value to row index
            RegisterInitTools.setRegisterValue(arrayDesign, instName + "/id_reg_reg", row, ID_WIDTH);

            System.out.println("  ** PLACED WestDCU: " + instName + " at " + bestAnchor
                    + " (id_reg=" + row + ")");
        }
    }

    /**
     * Places the SA FSM module instance near the top-left of the design,
     * avoiding overlap with all existing placed sites.
     */
    private static void placeFSM(ArrayBuilder ab, Module fsmModule) {
        Design arrayDesign = ab.getArray();

        RelocatableTileRectangle moduleBoundingBox = fsmModule.getBoundingBox();

        // Build a set of all occupied tiles from placed SiteInsts
        Set<Tile> occupiedTiles = new HashSet<>();
        int topLeftRow = Integer.MAX_VALUE;
        int topLeftCol = Integer.MAX_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            Tile t = si.getTile();
            occupiedTiles.add(t);
            topLeftRow = Math.min(topLeftRow, t.getRow());
            topLeftCol = Math.min(topLeftCol, t.getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst("sa_fsm", fsmModule);

        // Find the valid placement closest to the top-left corner
        // where none of the FSM's SiteInsts would land on an occupied tile
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : fsmModule.getAllValidPlacements()) {
            // Check if any of the module's sites would collide with occupied tiles
            boolean conflicts = false;
            for (SiteInst modSi : fsmModule.getSiteInsts()) {
                Site newSite = fsmModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    conflicts = true;
                    break;
                }
            }
            if (conflicts) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - topLeftCol)
                     + Math.abs(anchor.getTile().getRow() - topLeftRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for sa_fsm");
        }

        if (!mi.place(bestAnchor, true, false)) {
            throw new RuntimeException("Failed to place sa_fsm at " + bestAnchor);
        }

        System.out.println("  ** PLACED FSM: sa_fsm at " + bestAnchor);
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
