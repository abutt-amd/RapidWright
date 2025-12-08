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
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ArrayBuilderSLRCrossingCreator {
    private static final List<String> INPUT_KERNEL_OPTS = Arrays.asList("i", "input");
    private static final List<String> OUTPUT_OPTS = Arrays.asList("o", "output");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");

    public static OptionParser createOptionParser() {
        return new OptionParser() {
            {
                acceptsAll(INPUT_KERNEL_OPTS, "Input Kernel Design (*.dcp)").withRequiredArg();
                acceptsAll(OUTPUT_OPTS, "Output SLR Crossing Design (*.dcp)").withRequiredArg();
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
            }
        };
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

        String outputPath;
        if (options.has(OUTPUT_OPTS.get(0))) {
            outputPath = (String) options.valueOf(OUTPUT_OPTS.get(0));
        } else {
            throw new RuntimeException("No output path provided. "
                    + "Please specify an output path (*.dcp) using options "
                    + OUTPUT_OPTS);
        }

        Path inputFile = Paths.get(kernelDesignPath);
        Design inputDesign = Design.readCheckpoint(inputFile);

        if (!inputDesign.getDevice().getName().equals("xcv80")) {
            System.out.println("SLRCrossing creator currently only tested for xcv80");
        }

        Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(inputDesign);
        if (pblocks.isEmpty()) {
            throw new RuntimeException("Provided kernel design does not contain a PBlock");
        }

        if (pblocks.size() > 1) {
            throw new RuntimeException("Kernel design should only contain 1 PBlock but currently contains "
                    + pblocks.size());
        }

        PBlock pblock = pblocks.values().iterator().next();

        List<String> clockNets = ConstraintTools.getClockNetsFromXDC(inputDesign);

        assert(clockNets.size() == 1);

        ArrayBuilder.removeBUFGs(inputDesign);

        Module module = new Module(inputDesign, false);

        module.getNet(clockNets.get(0)).unroute();
        module.setPBlock(pblock.toString());
        module.calculateAllValidPlacements(inputDesign.getDevice());
        List<List<Site>> validPlacementGrid = ArrayBuilder.getValidPlacementGrid(module);

        Site lastAnchorInSLR = null;
        Site firstAnchorInOtherSLR = null;

        SLR firstSLR = validPlacementGrid.get(0).get(0).getTile().getSLR();

        for (List<Site> sites : validPlacementGrid) {
            Site anchor = sites.get(0);
            if (anchor.getTile().getSLR() != firstSLR) {
                firstAnchorInOtherSLR = anchor;
                break;
            }
            lastAnchorInSLR = anchor;
        }

        Design slrCrossing = new Design("slr_crossing", inputDesign.getPartName());

        // Merge encrypted cells
        List<String> encryptedCells = module.getNetlist().getEncryptedCells();
        if (!encryptedCells.isEmpty()) {
            System.out.println("Encrypted cells merged");
            slrCrossing.getNetlist().addEncryptedCells(encryptedCells);
        }

        ModuleInst topInst = slrCrossing.createModuleInst("top_inst", module);
//        topInst.place(lastAnchorInSLR);

        assert lastAnchorInSLR != null;
        int pBlockHeight = pblock.getBottomLeftTile().getRow() - pblock.getTopLeftTile().getRow();
        int topOffsetY = lastAnchorInSLR.getTile().getRow() - module.getAnchor().getTile().getRow();

        PBlock topPBlock = new PBlock(inputDesign.getDevice(), pblock.getAllSites(null));
        topPBlock.movePBlock(0, topOffsetY);
        topPBlock.setContainRouting(true);
        topPBlock.setIsSoft(false);
        topPBlock.setName("pblock0");
        for (String tclCmd : topPBlock.getTclConstraints()) {
            slrCrossing.addXDCConstraint(ConstraintGroup.LATE, tclCmd);
        }

        assert firstAnchorInOtherSLR != null;
        int bottomOffsetY = firstAnchorInOtherSLR.getTile().getRow() - module.getAnchor().getTile().getRow();

        PBlock bottomPBlock = new PBlock(inputDesign.getDevice(), pblock.getAllSites(null));
        bottomPBlock.movePBlock(0, bottomOffsetY);
        bottomPBlock.setContainRouting(true);
        bottomPBlock.setIsSoft(false);
        bottomPBlock.setName("pblock1");
        for (String tclCmd : bottomPBlock.getTclConstraints()) {
            slrCrossing.addXDCConstraint(ConstraintGroup.LATE, tclCmd);
        }

        slrCrossing.getNetlist().consolidateAllToWorkLibrary();
        slrCrossing.flattenDesign();
        slrCrossing.writeCheckpoint(outputPath);
    }
}
