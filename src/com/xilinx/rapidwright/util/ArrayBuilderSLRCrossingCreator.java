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
package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.InlineFlopTools;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFCell;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArrayBuilderSLRCrossingCreator {
    private static final List<String> INPUT_KERNEL_OPTS = Arrays.asList("i", "input");
    private static final List<String> TOP_DESIGN_OPTS = Arrays.asList("t", "top");
    private static final List<String> OUTPUT_OPTS = Arrays.asList("o", "output");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");

    private static OptionParser createOptionParser() {
        return new OptionParser() {
            {
                acceptsAll(INPUT_KERNEL_OPTS, "Input Kernel Design (*.dcp)").withRequiredArg();
                acceptsAll(TOP_DESIGN_OPTS, "Top Design of SLR Crossing (*.dcp)").withRequiredArg();
                acceptsAll(OUTPUT_OPTS, "Output SLR Crossing Design (*.dcp)").withRequiredArg();
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
            }
        };
    }

    private static List<PBlock> getCandidateBottomPBlocks(Module module, PBlock topPBlock, int topAnchorIndex) {
        List<PBlock> candidates = new ArrayList<>();
        List<List<Site>> validPlacementGrid = ArrayBuilder.getValidPlacementGrid(module);
        int pBlockHeight = topPBlock.getBottomLeftTile().getRow() - topPBlock.getTopLeftTile().getRow();
        Site topAnchor = validPlacementGrid.get(topAnchorIndex).get(0);
        Site lastAnchor = topAnchor;
        for (int i = topAnchorIndex; i < validPlacementGrid.size(); i++) {
            Site candidateAnchor = validPlacementGrid.get(i).get(0);
            int yOffsetFromLast = candidateAnchor.getTile().getRow() - lastAnchor.getTile().getRow();
            if (yOffsetFromLast < pBlockHeight) {
                // Would create overlap with other pblock
                continue;
            }
            int yOffsetFromTop = candidateAnchor.getTile().getRow() - topAnchor.getTile().getRow();
            if (yOffsetFromTop > 200) {
                // Past reasonable range of SLL connections
                break;
            }
            PBlock bottomPBlock = new PBlock(module.getDevice(), topPBlock.getAllSites(null));
            boolean wasMoved = bottomPBlock.movePBlock(0, yOffsetFromTop);
            if (!wasMoved) {
//                throw new RuntimeException("Failed to move pblock");
                continue;
            }
            candidates.add(bottomPBlock);
            lastAnchor = candidateAnchor;
        }
        return candidates;
    }

    private static void addPBlockToDesign(Design design, PBlock pblock) {
        pblock.setContainRouting(true);
        pblock.setIsSoft(false);
        for (String tclCmd : pblock.getTclConstraints()) {
            design.addXDCConstraint(ConstraintGroup.LATE, tclCmd);
        }
    }

    private static List<Node> getSLLsStartingInTile(Tile tile) {
        List<Node> sllNodes = new ArrayList<>();
        for (int i = 0; i < tile.getWireCount(); i++) {
            Node node = Node.getNode(tile, i);
            if (node == null) {
                continue;
            }
            IntentCode ic = node.getIntentCode();
            if (ic == IntentCode.NODE_SLL_DATA) {
                sllNodes.add(node);
            }
        }
        return sllNodes;
    }

    private static List<Node> getAllSLLsStartingInPBlock(PBlock pblock) {
        List<Node> sllNodes = new ArrayList<>();
        for (Tile t : pblock.getAllTiles()) {
            if (!Utils.isInterConnect(t.getTileTypeEnum())) {
                continue;
            }
            List<Node> tileSllNodes = getSLLsStartingInTile(t);
            sllNodes.addAll(tileSllNodes);
        }
        for (Node sll : sllNodes) {
            System.out.print(sll + " ");
        }
        System.out.println();
        return sllNodes;
    }

    private static int countNumSLLsEndingInPBlock(PBlock pblock, List<Node> sllNodes) {
        Set<Tile> tiles = pblock.getAllTiles();
        int i = 0;
        for (Node sll : sllNodes) {
            boolean downHillNodeInPBlock = false;
            for (Node downhillNode : sll.getAllDownhillNodes()) {
                if (tiles.contains(downhillNode.getTile())) {
                    downHillNodeInPBlock = true;
                }
            }
            if (tiles.contains(sll.getTile()) || downHillNodeInPBlock) {
                i++;
            }
        }
        return i;
    }

    private static PBlock chooseBestCandidate(PBlock topPBlock, List<PBlock> candidates) {
        List<Node> sllNodesStartingInTopPBlock = getAllSLLsStartingInPBlock(topPBlock);
        int bestSLLCount = 0;
        PBlock bestCandidate = null;
        for (PBlock candidate : candidates) {
            int numSLLs = countNumSLLsEndingInPBlock(candidate, sllNodesStartingInTopPBlock);
            if (numSLLs > bestSLLCount) {
                bestSLLCount = numSLLs;
                bestCandidate = candidate;
            }
        }
        if (bestSLLCount < 64) {
            throw new RuntimeException("Only found " + bestSLLCount + " SLLs between pblocks");
        }
        return bestCandidate;
    }

    private static void explorePerformance(Design design, boolean reuse) {
        PerformanceExplorer pe = new PerformanceExplorer(design, "SLRCrossingCreator", "clk", 2.0);
        pe.setGetBestPerPBlock(true);
        pe.setReusePreviousResults(reuse);
        pe.setLockPlacement(true);
        pe.explorePerformance();
        pe.getBestDesignPerPBlock();
    }

    public static void main(String[] args) {
        OptionParser p = createOptionParser();
        OptionSet options = p.parse(args);

        if (options.has(HELP_OPTS.get(0))) {
            MessageGenerator.printHeader("ArrayBuilderSLRCrossingCreator");
            System.out.println("Generates an optimized SLR crossing of a given kernel to be used in ArrayBuilder.");
            try {
                p.printHelpOn(System.out);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String kernelDesignPath;
        if (options.has(INPUT_KERNEL_OPTS.get(0))) {
            kernelDesignPath = (String) options.valueOf(INPUT_KERNEL_OPTS.get(0));
        } else {
            throw new RuntimeException("No input design found. "
                    + "Please specify an input kernel (*.dcp) using options "
                    + INPUT_KERNEL_OPTS);
        }

        String topDesignPath;
        if (options.has(TOP_DESIGN_OPTS.get(0))) {
            topDesignPath = (String) options.valueOf(TOP_DESIGN_OPTS.get(0));
        } else {
            throw new RuntimeException("No top design found. "
                    + "Please specify a top design (*.dcp) using options "
                    + TOP_DESIGN_OPTS);
        }

        String outputPath;
        if (options.has(OUTPUT_OPTS.get(0))) {
            outputPath = (String) options.valueOf(OUTPUT_OPTS.get(0));
        } else {
            throw new RuntimeException("No output path provided. "
                    + "Please specify an output path (*.dcp) using options "
                    + OUTPUT_OPTS);
        }

        Path kernelFile = Paths.get(kernelDesignPath);
        Design kernelDesign = Design.readCheckpoint(kernelFile);

        Path topFile = Paths.get(topDesignPath);
        Design topDesign = Design.readCheckpoint(topFile);

        if (!kernelDesign.getDevice().getName().equals("xcv80")) {
            System.out.println("SLRCrossing creator currently only tested for xcv80");
        }

        Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(kernelDesign);
        if (pblocks.isEmpty()) {
            throw new RuntimeException("Provided kernel design does not contain a PBlock");
        }

        if (pblocks.size() > 1) {
            throw new RuntimeException("Kernel design should only contain 1 PBlock but currently contains "
                    + pblocks.size());
        }

        PBlock pblock = pblocks.values().iterator().next();

        List<String> clockNets = ConstraintTools.getClockNetsFromXDC(kernelDesign);

        assert(clockNets.size() == 1);

        ArrayBuilder.removeBUFGs(kernelDesign);

        Module module = new Module(kernelDesign, false);

        module.getNet(clockNets.get(0)).unroute();
        module.setPBlock(pblock.toString());
        module.calculateAllValidPlacements(kernelDesign.getDevice());
        List<List<Site>> validPlacementGrid = ArrayBuilder.getValidPlacementGrid(module);

        int lastAnchorInSLRIndex = -1;
        int firstAnchorInOtherSLRIndex = -1;

        SLR firstSLR = validPlacementGrid.get(0).get(0).getTile().getSLR();

        int i = 0;
        for (List<Site> sites : validPlacementGrid) {
            Site anchor = sites.get(0);
            if (anchor.getTile().getSLR() != firstSLR) {
                firstAnchorInOtherSLRIndex = i;
                break;
            }
            lastAnchorInSLRIndex = i;
            i++;
        }

        // Merge encrypted cells
        List<String> encryptedCells = module.getNetlist().getEncryptedCells();
        if (!encryptedCells.isEmpty()) {
            System.out.println("Encrypted cells merged");
            topDesign.getNetlist().addEncryptedCells(encryptedCells);
        }

        List<String> moduleInstNames = ArrayBuilder.getMatchingModuleInstanceNames(module, topDesign);

        String topInstName = "x[0].y[0].u_tile";
        EDIFHierCellInst topHierInst = topDesign.getNetlist().getHierCellInstFromName(topInstName);
        if (topHierInst == null) {
            throw new RuntimeException("Instance name " + topInstName + " is invalid");
        }
        EDIFTools.removeVivadoBusPreventionAnnotations(kernelDesign.getNetlist());
        ModuleInst topInst = topDesign.createModuleInst(topInstName, module);

        String bottomInstName = "x[0].y[1].u_tile";
        EDIFHierCellInst bottomHierInst = topDesign.getNetlist().getHierCellInstFromName(bottomInstName);
        if (bottomHierInst == null) {
            throw new RuntimeException("Instance name " + bottomInstName + " is invalid");
        }
        ModuleInst bottomInst = topDesign.createModuleInst(bottomInstName, module);

        Site lastAnchorInSLR = validPlacementGrid.get(lastAnchorInSLRIndex).get(0);
        assert lastAnchorInSLR != null;
        int topOffsetY = lastAnchorInSLR.getTile().getRow() - module.getAnchor().getTile().getRow();

        PBlock topPBlock = new PBlock(kernelDesign.getDevice(), pblock.getAllSites(null));
        topPBlock.setName("pblock_0");
        topPBlock.movePBlock(0, topOffsetY);

        List<PBlock> candidates = getCandidateBottomPBlocks(module, topPBlock, lastAnchorInSLRIndex);
        PBlock bottomPBlock = chooseBestCandidate(topPBlock, candidates);
        bottomPBlock.setName("pblock_1");

        Set<Site> allSites = new HashSet<>(topPBlock.getAllSites(null));
        allSites.addAll(bottomPBlock.getAllSites(null));
        PBlock overallPBlock = new PBlock(kernelDesign.getDevice(), topPBlock + " " + bottomPBlock);
        overallPBlock.setName("pblock_2");

        addPBlockToDesign(topDesign, overallPBlock);
        addPBlockToDesign(topDesign, topPBlock);
        addPBlockToDesign(topDesign, bottomPBlock);

        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_2 -top");
        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_0 [get_cells " + topInstName + "]");
        topDesign.addXDCConstraint(ConstraintGroup.LATE, "add_cells_to_pblock pblock_1 [get_cells " + bottomInstName + "]");

        topDesign.getNetlist().consolidateAllToWorkLibrary();
        topDesign.flattenDesign();
        topDesign.setDesignOutOfContext(true);
        topDesign.setAutoIOBuffers(false);

        EDIFNetlist netlist = topDesign.getNetlist();
        EDIFCell topCell = netlist.getTopCell();
        Map<EDIFPort, PBlockSide> topSideMap = new HashMap<>();
        topSideMap.put(topCell.getPort("accum_shift_in[0]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("accum_shift_in[1]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("accum_shift_in[2]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("accum_shift_in[3]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("accum_shift_out[0]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("accum_shift_out[1]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("accum_shift_out[2]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("accum_shift_out[3]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("accum_inputs[0]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("accum_inputs[1]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("accum_inputs[2]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("accum_inputs[3]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("north_inputs[0]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("north_inputs[1]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("north_inputs[2]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("north_inputs[3]"), PBlockSide.TOP);
        topSideMap.put(topCell.getPort("west_inputs[0]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("west_inputs[1]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("west_inputs[2]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("west_inputs[3]"), PBlockSide.LEFT);
        topSideMap.put(topCell.getPort("east_outputs[0]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("east_outputs[1]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("east_outputs[2]"), PBlockSide.RIGHT);
        topSideMap.put(topCell.getPort("east_outputs[3]"), PBlockSide.RIGHT);

        InlineFlopTools.createAndPlacePortFlopsOnSide(topDesign, "clk", topPBlock, topSideMap);
        topDesign.getNetlist().resetParentNetMap();

        Map<EDIFPort, PBlockSide> bottomSideMap = new HashMap<>();
        bottomSideMap.put(topCell.getPort("accum_shift_in[4]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("accum_shift_in[5]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("accum_shift_in[6]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("accum_shift_in[7]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("accum_shift_out[4]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("accum_shift_out[5]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("accum_shift_out[6]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("accum_shift_out[7]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("west_inputs[4]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("west_inputs[5]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("west_inputs[6]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("west_inputs[7]"), PBlockSide.LEFT);
        bottomSideMap.put(topCell.getPort("east_outputs[4]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("east_outputs[5]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("east_outputs[6]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("east_outputs[7]"), PBlockSide.RIGHT);
        bottomSideMap.put(topCell.getPort("south_outputs[0]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("south_outputs[1]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("south_outputs[2]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("south_outputs[3]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("accum_outputs[0]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("accum_outputs[1]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("accum_outputs[2]"), PBlockSide.TOP);
        bottomSideMap.put(topCell.getPort("accum_outputs[3]"), PBlockSide.TOP);

        InlineFlopTools.createAndPlacePortFlopsOnSide(topDesign, "clk", bottomPBlock, bottomSideMap);

        explorePerformance(topDesign, true);
        Design bestDesign = Design.readCheckpoint("SLRCrossingCreator/pblock0_best.dcp");
        InlineFlopTools.removeInlineFlops(bestDesign);
        bestDesign.writeCheckpoint(outputPath);
    }
}
