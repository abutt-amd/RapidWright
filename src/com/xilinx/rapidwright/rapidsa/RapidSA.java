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
import com.xilinx.rapidwright.design.tools.RegisterInitTools;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.InputDCUTile;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.WeightDCUTile;
import com.xilinx.rapidwright.util.Pair;
import joptsimple.OptionParser;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RapidSA {
    private static final int ID_WIDTH = 8;
    private static final int DCU_UNITS_PER_TILE = 4;

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

//        Design d = Design.readCheckpoint("/group/zircon2/abutt/RapidSA/MM2SNOCChannel/PerformanceExplorer/Explore_Explore_0.25_pblock0_SLICE_X322Y868-/routed.dcp");
//        d.getNOCDesign().clearSolution();
//        d.writeCheckpoint("/group/zircon2/abutt/RapidSA/MM2SNOCChannel/pnr.dcp");
//        if (true)
//        return;

        if (options.has("precompile")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 1.667);
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
        config.setFlipPlacementHorizontally(true);
        config.setRowOffset(5);
        config.setColumnOffset(1);

        // Create array builder with config
        ArrayBuilder ab = new ArrayBuilder(config);
        ab.initializeArrayBuilder();

        ab.createArray();

        // Place WeightDCU tiles above the array
        Module weightDcuModule = loadRelocatableModule("RapidSA", new WeightDCUTile(8), ab.getArray());
        placeWeightDCUTiles(ab, 8, weightDcuModule);

        // Place InputDCU tiles right of the array
        Module inputDcuModule = loadRelocatableModule("RapidSA", new InputDCUTile(8), ab.getArray());
        placeInputDCUTiles(ab, 8, inputDcuModule);

        // Place DrainTiles below the array
        int accumCount = 4 * 4; // nCols * nRows for GEMM tile accumulator count
        Module drainModule = loadRelocatableModule("RapidSA", new DrainTile(accumCount, 8), ab.getArray());
        placeDrainTiles(ab, 8, drainModule, accumCount);

        // Place MM2S NOC channel at the top-right of the array
        Module mm2sModule = loadRelocatableModule("RapidSA", new MM2SNOCChannel(), ab.getArray());
        placeMM2SNOCChannel(ab, mm2sModule);

        // Place S2MM channel at the top-right of the array
        Module s2mmModule = loadRelocatableModule("RapidSA", new S2MMNOCChannel(), ab.getArray());
        placeS2MMChannel(ab, s2mmModule);

        Design arrayDesign = ab.getArray();

        // Update MM2S registers before flattening (deep hierarchy won't survive flatten)
        MM2SNOCChannel.setMatrixHeight(arrayDesign, "mm2s", 8);
        MM2SNOCChannel.setMatrixWidth(arrayDesign, "mm2s", 8);

        arrayDesign.flattenDesign();
        EDIFTools.uniqueifyNetlist(arrayDesign);
        int flopTreeDepth = 6;
//        FlopTreeTools.insertFlopTreeForNet(arrayDesign, "sa_accum_shift", "clk", flopTreeDepth, 3);
//        FlopTreeTools.insertFlopTreeForNet(arrayDesign, "output_wr_en", "clk", flopTreeDepth, 3);

        // Update FSM registers
//        SAControlFSM.setSAWidth(arrayDesign, "sa_fsm", 8);
//        SAControlFSM.setSAHeight(arrayDesign, "sa_fsm", 8);
//        SAControlFSM.setKDim(arrayDesign, "sa_fsm", 8);
//        SAControlFSM.setAccumShiftPipelineLatency(arrayDesign, "sa_fsm", flopTreeDepth);
//        SAControlFSM.setOutputWrPipelineLatency(arrayDesign, "sa_fsm", flopTreeDepth);

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
     * Places WeightDCU module instances physically above the array,
     * aligned with each column's top-row centroid.
     */
    private static void placeWeightDCUTiles(ArrayBuilder ab, int nCols, Module dcuModule) {
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
            String instName = "weight_dcu_x" + col;

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

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile()));

            // Set id_reg init value so unit IDs don't overlap across tiles
            int idValue = col * DCU_UNITS_PER_TILE;
            RegisterInitTools.setRegisterValue(arrayDesign, instName + "/id_reg_reg", idValue, ID_WIDTH);

            System.out.println("  ** PLACED WeightDCU: " + instName + " at " + bestAnchor
                    + " (id_reg=" + idValue + ")");
        }
    }

    /**
     * Places DrainTile module instances physically below the array,
     * aligned with each column's bottom-row centroid.
     */
    private static void placeDrainTiles(ArrayBuilder ab, int nCols, Module drainModule, int accumCount) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = drainModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Compute the array's bottom row from all placed sites
        int arrayBottomRow = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayBottomRow = Math.max(arrayBottomRow, si.getTile().getRow());
        }

        // Find the number of rows from the centroid map
        int nRows = 0;
        for (Pair<Integer, Integer> key : centroidMap.keySet()) {
            nRows = Math.max(nRows, key.getSecond() + 1);
        }

        for (int col = 0; col < nCols; col++) {
            Site targetCentroid = centroidMap.get(new Pair<>(col, nRows - 1));
            String instName = "drain_x" + col;

            ModuleInst mi = arrayDesign.createModuleInst(instName, drainModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : drainModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), drainModule.getAnchor().getTile());

                // Top of drain bounding box must be below bottom of array
                if (candidateBB.getMinRow() <= arrayBottomRow) {
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

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), drainModule.getAnchor().getTile()));

            // Set column element count (number of accumulators from this column's GEMM tiles)
            DrainTile.setColumnElements(arrayDesign, instName, accumCount, 8);

            // Set external upstream element count (accumulator elements from upstream drain tiles)
            // drain_x0 is the output end, drain_x{nCols-1} has no upstream
            int upstreamElements = (nCols - 1 - col) * accumCount;
            DrainTile.setExternalUpstreamElements(arrayDesign, instName, upstreamElements, 8);

            System.out.println("  ** PLACED DrainTile: " + instName + " at " + bestAnchor
                    + " (col_elem=" + accumCount + ", ext_ups=" + upstreamElements + ")");
        }
    }

    /**
     * Places InputDCU module instances physically right of the array,
     * aligned with each row's last-column centroid.
     */
    private static void placeInputDCUTiles(ArrayBuilder ab, int nRows, Module dcuModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = dcuModule.getBoundingBox();
        List<RelocatableTileRectangle> placedBoundingBoxes = new ArrayList<>();

        // Find number of columns from centroid map
        int nCols = 0;
        for (Pair<Integer, Integer> key : centroidMap.keySet()) {
            nCols = Math.max(nCols, key.getFirst() + 1);
        }

        // Compute the array's rightmost column from all placed sites
        int arrayRightCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            arrayRightCol = Math.max(arrayRightCol, si.getTile().getColumn());
        }

        for (int row = 0; row < nRows; row++) {
            Site targetCentroid = centroidMap.get(new Pair<>(nCols - 1, row));
            String instName = "input_dcu_y" + row;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Left edge of DCU bounding box must be right of array
                if (candidateBB.getMinColumn() <= arrayRightCol) {
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

            if (!mi.place(bestAnchor, false, false)) {
                throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
            }

            placedBoundingBoxes.add(moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile()));

            // Set id_reg init value so unit IDs don't overlap across tiles
            int idValue = row * DCU_UNITS_PER_TILE;
            RegisterInitTools.setRegisterValue(arrayDesign, instName + "/id_reg_reg", idValue, ID_WIDTH);

            System.out.println("  ** PLACED InputDCU: " + instName + " at " + bestAnchor
                    + " (id_reg=" + idValue + ")");
        }
    }

    /**
     * Places the MM2S NOC channel at the top-right of the array.
     */
    private static void placeMM2SNOCChannel(ArrayBuilder ab, Module mm2sModule) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = mm2sModule.getBoundingBox();

        // Collect all existing placed bounding boxes
        List<RelocatableTileRectangle> existingBBs = new ArrayList<>();
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        // Build occupied tiles set
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Target: top-right corner of the array
        int targetRow = Integer.MAX_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            targetRow = Math.min(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst("mm2s", mm2sModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : mm2sModule.getAllValidPlacements()) {
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), mm2sModule.getAnchor().getTile());

            // No overlap with existing bounding boxes
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

            // No site-level conflicts
            boolean siteConflict = false;
            for (SiteInst modSi : mm2sModule.getSiteInsts()) {
                Site newSite = mm2sModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true; break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for mm2s");
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place mm2s at " + bestAnchor);
        }

        System.out.println("  ** PLACED MM2S NOC: mm2s at " + bestAnchor);
    }

    /**
     * Places the S2MM channel at the bottom-right side of the array.
     */
    private static void placeS2MMChannel(ArrayBuilder ab, Module s2mmModule) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = s2mmModule.getBoundingBox();

        // Collect all existing placed bounding boxes
        List<RelocatableTileRectangle> existingBBs = new ArrayList<>();
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        // Build occupied tiles set
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Target: bottom-right corner of the array
        int targetRow = Integer.MIN_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            targetRow = Math.max(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst("s2mm", s2mmModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : s2mmModule.getAllValidPlacements()) {
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), s2mmModule.getAnchor().getTile());

            // No overlap with existing bounding boxes
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

            // No site-level conflicts
            boolean siteConflict = false;
            for (SiteInst modSi : s2mmModule.getSiteInsts()) {
                Site newSite = s2mmModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true; break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for s2mm");
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place s2mm at " + bestAnchor);
        }

        System.out.println("  ** PLACED S2MM: s2mm at " + bestAnchor);
    }

    /**
     * Places the SA FSM module instance near the top-left of the design,
     * avoiding bounding box overlap with all existing placed module instances.
     */
    private static void placeFSM(ArrayBuilder ab, Module fsmModule) {
        Design arrayDesign = ab.getArray();
        Map<Pair<Integer, Integer>, Site> centroidMap = ab.getLogicalToCentroidMap();

        RelocatableTileRectangle moduleBoundingBox = fsmModule.getBoundingBox();

        // Collect bounding boxes from all already-placed ModuleInsts
        List<RelocatableTileRectangle> existingBoundingBoxes = new ArrayList<>();
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBoundingBoxes.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        // Use the GEMM array's top-left centroid (col 0, row 0) as placement target
        Site topLeftCentroid = centroidMap.get(new Pair<>(0, 0));
        int targetRow = topLeftCentroid.getTile().getRow();
        int targetCol = topLeftCentroid.getTile().getColumn();

        // Build set of occupied tiles
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Debug: print FSM module info and valid anchors above the array
        System.out.println("  FSM anchor site: " + fsmModule.getAnchor());
        System.out.println("  FSM bounding box: " + moduleBoundingBox);
        System.out.println("  FSM site types used:");
        for (SiteInst si : fsmModule.getSiteInsts()) {
            System.out.println("    " + si.getName() + " -> " + si.getSiteTypeEnum() + " @ " + si.getSite());
        }
        int arrayTopRow = targetRow;
        System.out.println("  Total valid placements: " + fsmModule.getAllValidPlacements().size());
        System.out.println("  Valid FSM anchors above array (row < " + arrayTopRow + "):");
        for (Site anchor : fsmModule.getAllValidPlacements()) {
            if (anchor.getTile().getRow() < arrayTopRow) {
                System.out.println("    " + anchor + " (row=" + anchor.getTile().getRow() + ", col=" + anchor.getTile().getColumn() + ")");
            }
        }

        ModuleInst mi = arrayDesign.createModuleInst("sa_fsm", fsmModule);

        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : fsmModule.getAllValidPlacements()) {
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), fsmModule.getAnchor().getTile());

            // Check bounding box overlap with existing ModuleInsts
            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBoundingBoxes) {
                if (existing.overlaps(candidateBB)) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;

            // Check site-level conflicts with occupied tiles (e.g. GEMM tiles)
            boolean siteConflict = false;
            for (SiteInst modSi : fsmModule.getSiteInsts()) {
                Site newSite = fsmModule.getCorrespondingSite(modSi, anchor);
                if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                    siteConflict = true;
                    break;
                }
            }
            if (siteConflict) continue;

            int dist = Math.abs(anchor.getTile().getColumn() - targetCol)
                     + Math.abs(anchor.getTile().getRow() - targetRow);
            if (dist < bestDist) {
                bestDist = dist;
                bestAnchor = anchor;
            }
        }

        if (bestAnchor == null) {
            throw new RuntimeException("Could not find valid placement for sa_fsm");
        }

        if (!mi.place(bestAnchor, false, false)) {
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
