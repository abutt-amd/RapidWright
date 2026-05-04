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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.ArrayBuilder;
import com.xilinx.rapidwright.design.tools.ArrayBuilderConfig;
import com.xilinx.rapidwright.design.tools.FlopTreeTools;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.EdgeBufferTile;
import com.xilinx.rapidwright.rapidsa.components.GEMMTile;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;
import com.xilinx.rapidwright.rwroute.GlobalSignalRouting;
import com.xilinx.rapidwright.rwroute.NodeStatus;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.VivadoTools;
import joptsimple.OptionParser;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class RapidSA {
    private static final int ID_WIDTH = 8;
    private static final int DCU_UNITS_PER_TILE = 4;

    public static void main(String[] args) {
        String partName = "xcv80-lsva4737-2MHP-e-S";
        Part part = PartNameTools.getPart(partName);

        OptionParser parser = new OptionParser();
        parser.accepts("precompile", "Run precompilation of all RapidSA components and exit");
        parser.accepts("precompile-slr-crossings-only", "Generate only SLR crossing artifacts for applicable precompiled RapidSA components and exit");
        parser.accepts("rows", "Number of GEMM tile rows in the systolic array").withRequiredArg().ofType(Integer.class).defaultsTo(8);
        parser.accepts("cols", "Number of GEMM tile columns in the systolic array").withRequiredArg().ofType(Integer.class).defaultsTo(8);
        parser.accepts("route", "After building the array, run RWRoute partial route + HoldFixer in-process and write a routed DCP");
        parser.accepts("vivado-route", "After building the array, shell out to Vivado to route the design and write a routed DCP");
        parser.accepts("lsf-route", "After building the array, submit RWRoute partial route + HoldFixer as an LSF (bsub) job and wait for it to complete; useful when the local host doesn't have enough RAM");
        parser.accepts("lsf-bsub-opts", "Override the default bsub options used by --lsf-route (defaults to '-n 4 -R \"rusage[mem=64000]\" -W 6:00')").withRequiredArg().ofType(String.class);
        parser.accepts("report-timing", "After routing, open the routed DCP in Vivado and run report_timing_summary + report_timing -nworst 10. Combined with --vivado-route in a single Vivado session.");
        parser.accepts("flop-tree-depth", "Flop tree depth for control fanout (default: scales with array size)").withRequiredArg().ofType(Integer.class);
        parser.accepts("max-depth-per-slr", "Max flop tree depth within a single SLR (default: scales with flop-tree-depth)").withRequiredArg().ofType(Integer.class);
        parser.accepts("handshake-chain-depth", "Flop chain depth for s2mm_start/s2mm_done handshake nets that span the full array (default: scales with nRows)").withRequiredArg().ofType(Integer.class);
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

        if (options.has("precompile-slr-crossings-only")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0, true);
            return;
        }

        if (options.has("precompile")) {
            RapidSAPrecompile.precompileRapidSAComponents("RapidSA", part, 2.0);
            return;
        }

        int nRows = (Integer) options.valueOf("rows");
        int nCols = (Integer) options.valueOf("cols");

        Design sa = RapidSANetlistBuilder.createSystolicArrayNetlist(nRows, nCols, partName, "RapidSA");

//        sa.getNetlist().exportEDIF("test.edf");
//        sa.writeCheckpoint("blackbox_netlist.dcp");

        GEMMTile tile = new GEMMTile(4, 4);

        String compOutputDir = "RapidSA" + File.separator + tile.getComponentName();
        String kernelDcpPath = compOutputDir + File.separator + "pnr.dcp";
        String slrCrossingDir = compOutputDir + File.separator + RapidSAPrecompile.SLR_CROSSING_RUN_DIR;
        File slrCrossingDcp = new File(slrCrossingDir + File.separator + RapidSAPrecompile.SLR_CROSSING_DCP_NAME);
        File slrCrossingSynthDcp = new File(slrCrossingDir + File.separator + RapidSAPrecompile.SLR_CROSSING_SYNTH_DCP_NAME);
        boolean haveSlrCrossing = slrCrossingDcp.exists() && slrCrossingSynthDcp.exists();

        // Batch 1: read kernel + (optional) SLR-crossing pair in parallel. Each
        // readCheckpoint produces an isolated Design, so they are safe to run
        // concurrently. The kernel is the "first" task so it executes on the
        // calling thread (we need it first to construct ArrayBuilderConfig).
        long batch1Start = System.nanoTime();
        Deque<Future<Design>> designFutures = haveSlrCrossing
                ? ParallelismTools.invokeFirstSubmitRest(
                        () -> Design.readCheckpoint(kernelDcpPath),
                        () -> Design.readCheckpoint(slrCrossingDcp.getPath()),
                        () -> Design.readCheckpoint(slrCrossingSynthDcp.getPath()))
                : ParallelismTools.invokeFirstSubmitRest(
                        () -> Design.readCheckpoint(kernelDcpPath));
        Design kernel = awaitFuture(designFutures);
        EDIFTools.removeVivadoBusPreventionAnnotations(kernel.getNetlist());
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(kernel);

        ArrayBuilderConfig config = new ArrayBuilderConfig(kernel, sa);
        config.setTopClockName("clk");
        config.setKernelClockName("clk");
        config.setOutOfContext(false);
        config.setRouteClock(false);
        config.setFlipPlacementHorizontally(true);
        config.setRowOffset(5);
        config.setColumnOffset(1);
        config.setUnrouteStaticNets(false);

        if (haveSlrCrossing) {
            Design slrCrossing = awaitFuture(designFutures);
            EDIFTools.removeVivadoBusPreventionAnnotations(slrCrossing.getNetlist());
            Design slrCrossingSynth = awaitFuture(designFutures);
            EDIFTools.removeVivadoBusPreventionAnnotations(slrCrossingSynth.getNetlist());
            config.setSlrCrossing(slrCrossing);
            config.setSlrCrossingSynth(slrCrossingSynth);
            config.setSlrCrossingTopInstName(RapidSAPrecompile.SLR_CROSSING_TOP_INST_NAME);
            config.setSlrCrossingBottomInstName(RapidSAPrecompile.SLR_CROSSING_BOTTOM_INST_NAME);
            System.out.println("  ** Using precompiled GEMM SLR crossing from " + slrCrossingDir);
        } else {
            System.out.println("  ** No precompiled GEMM SLR crossing found at " + slrCrossingDir
                    + " (array will fail if it crosses SLRs)");
        }
        System.out.println("** Batch 1 DCP load (kernel"
                + (haveSlrCrossing ? " + SLR crossing pair" : "") + ") in "
                + ((System.nanoTime() - batch1Start) / 1_000_000) + " ms");

        // Create array builder with config
        ArrayBuilder ab = new ArrayBuilder(config);
        ab.initializeArrayBuilder();

        System.out.println("[diag] PRE-createArray  GND PIPs=" + ab.getKernelDesign().getGndNet().getPIPs().size());
        dumpUnroutedStaticPins(ab.getKernelDesign(), "place_before_createArray_unrouted_static_pins.txt");
        ab.createArray();
        System.out.println("[diag] POST-createArray  GND PIPs=" + ab.getArray().getGndNet().getPIPs().size());

        ab.getArray().getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(ab.getArray());
//        dumpUnroutedStaticPins(ab.getArray(), "place_after_createArray_unrouted_static_pins.txt");

        // Pre-load all 5 component module DCPs in parallel. Each loadRelocatableModule
        // call is fully isolated (its own Design + Module), so they can run concurrently
        // on the ParallelismTools pool. Placement (which mutates ab.getArray()) stays
        // sequential below.
        com.xilinx.rapidwright.device.Device targetDevice = ab.getArray().getDevice();
        int accumCount = 4 * 4; // nCols * nRows for GEMM tile accumulator count
        long batch2Start = System.nanoTime();
        Deque<Future<Module>> moduleFutures = ParallelismTools.invokeFirstSubmitRest(
                () -> loadRelocatableModule("RapidSA", new EdgeBufferTile(8, EdgeBufferTile.Type.WEIGHT), targetDevice),
                () -> loadRelocatableModule("RapidSA", new EdgeBufferTile(8, EdgeBufferTile.Type.INPUT), targetDevice),
                () -> loadRelocatableModule("RapidSA", new DrainTile(accumCount, 8), targetDevice),
                () -> loadRelocatableModule("RapidSA", new MM2SNOCChannel(), targetDevice),
                () -> loadRelocatableModule("RapidSA", new S2MMNOCChannel(), targetDevice));
        Module weightEbModule = awaitModule(moduleFutures);
        Module inputEbModule  = awaitModule(moduleFutures);
        Module drainModule    = awaitModule(moduleFutures);
        Module mm2sModule     = awaitModule(moduleFutures);
        Module s2mmModule     = awaitModule(moduleFutures);
        System.out.println("** Pre-loaded 5 component modules in "
                + ((System.nanoTime() - batch2Start) / 1_000_000) + " ms");

        // [probe-orphan] For each unrouted GND sink that survived
        // dumpUnroutedStaticPins, report the BEL pin / cell / logical pin /
        // INIT so we can tell whether the SitePinInst landed on (a) an unused
        // LUT input, (b) an input the LUT's INIT doesn't depend on, or
        // (c) a real flop input that genuinely needs a GND tie. The first two
        // categories indicate spurious SitePinInsts created by
        // RWRoute.preprocess on EDIF cell pins exposed by flatten/consolidate.
        {
            Design arr = ab.getArray();
            DesignTools.updatePinsIsRouted(arr);
            int probed = 0;
            for (SitePinInst spi : arr.getGndNet().getPins()) {
                if (spi.isOutPin() || spi.isRouted()) continue;
                SiteInst si = spi.getSiteInst();
                StringBuilder sb = new StringBuilder("[probe-orphan] " + spi);
                for (com.xilinx.rapidwright.device.BELPin belPin : DesignTools.getConnectedBELPins(spi)) {
                    Cell cell = si.getCell(belPin.getBEL());
                    sb.append("  bel=").append(belPin.getBEL().getName())
                      .append("/").append(belPin.getName());
                    if (cell != null) {
                        String logical = cell.getLogicalPinMapping(belPin.getName());
                        String init = cell.getProperty("INIT") != null
                                ? cell.getProperty("INIT").getValue() : "<none>";
                        sb.append(" cell=").append(cell.getName())
                          .append(" type=").append(cell.getType())
                          .append(" logicalPin=").append(logical)
                          .append(" INIT=").append(init);
                        // What EDIF net is the logical pin actually wired to?
                        if (logical != null) {
                            com.xilinx.rapidwright.edif.EDIFCellInst eci =
                                    cell.getEDIFCellInst();
                            String edifNetName = "<none>";
                            if (eci != null) {
                                com.xilinx.rapidwright.edif.EDIFPortInst epi =
                                        eci.getPortInst(logical);
                                if (epi != null && epi.getNet() != null) {
                                    edifNetName = epi.getNet().getName();
                                }
                            }
                            sb.append(" edifNet=").append(edifNetName);
                        }
                        // Does the same BEL pin appear in the kernel design with
                        // a SitePinInst already covering it (i.e. relocation
                        // dropped it)? Find the kernel SiteInst hosting the same
                        // logical cell name (strip the "tile_x0y0/" prefix).
                        Design kernelD = ab.getKernelDesign();
                        String cellName = cell.getName();
                        int slash = cellName.indexOf('/');
                        String kernelCellName = slash > 0 ? cellName.substring(slash + 1) : cellName;
                        Cell kernelCell = kernelD.getCell(kernelCellName);
                        if (kernelCell != null) {
                            SiteInst kernelSi = kernelCell.getSiteInst();
                            String kernelBel = kernelCell.getBELName();
                            sb.append(" kernelSite=").append(kernelSi.getSiteName())
                              .append("/").append(kernelBel);
                            // Is this BEL pin covered by a SitePinInst on
                            // GND/VCC in the kernel?
                            for (SitePinInst kspi : kernelSi.getSitePinInsts()) {
                                if (kspi.getName().equals(spi.getName())) {
                                    Net knet = kspi.getNet();
                                    sb.append(" kernelSpiNet=").append(knet == null ? "<null>" : knet.getName());
                                    break;
                                }
                            }
                        } else {
                            sb.append(" kernelCell=<not found>");
                        }
                    } else {
                        sb.append(" cell=<none>");
                    }
                }
                System.out.println(sb);
                if (++probed >= 10) break;
            }
        }

        // [diag] check whether per-module static-net PIPs survived relocation
        // into the array's global GND/VCC.
        {
            Design arr = ab.getArray();
            System.out.println("[diag] array GND pins=" + arr.getGndNet().getPins().size()
                    + " PIPs=" + arr.getGndNet().getPIPs().size());
            System.out.println("[diag] array VCC pins=" + arr.getVccNet().getPins().size()
                    + " PIPs=" + arr.getVccNet().getPIPs().size());
            if (!arr.getModuleInsts().isEmpty()) {
                ModuleInst sample = arr.getModuleInsts().iterator().next();
                System.out.println("[diag] sample MI " + sample.getName()
                        + " gndPIPs=" + sample.getUsedStaticPIPs(arr.getGndNet()).size()
                        + " vccPIPs=" + sample.getUsedStaticPIPs(arr.getVccNet()).size());
            }
        }

//        ab.getArray().writeCheckpoint("systolic_array_1x1.dcp");

        // Place Weight Edge Buffer tiles above the array (module pre-loaded above)
        placeWeightDCUTiles(ab, nCols, weightEbModule);
        ab.getArray().getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(ab.getArray());
//        dumpUnroutedStaticPins(ab.getArray(), "place_after_placeWeightDCUTiles_unrouted_static_pins.txt");


        // Place Input Edge Buffer tiles right of the array (module pre-loaded above)
        placeInputDCUTiles(ab, nRows, inputEbModule);
        ab.getArray().getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(ab.getArray());
//        dumpUnroutedStaticPins(ab.getArray(), "place_after_placeInputDCUTiles_unrouted_static_pins.txt");

        // Place DrainTiles below the array (module pre-loaded above; accumCount declared with batch 2)
        placeDrainTiles(ab, nCols, drainModule, accumCount);
//        countOrphanStaticTieNets(ab.getArray(), "after placeDrainTiles");
//        countSourceUnroutedSignalNets(ab.getArray(), "after placeDrainTiles");
        ab.getArray().getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(ab.getArray());
//        dumpUnroutedStaticPins(ab.getArray(), "place_after_placeDrainTiles_unrouted_static_pins.txt");

        Design arrayDesign = ab.getArray();

        String[] inputEbRoots = computeFirstEbPerSLR(arrayDesign, "input_eb_y", nRows);
        String[] weightEbRoots = computeFirstEbPerSLR(arrayDesign, "weight_eb_x", nCols);

        RapidSANetlistBuilder.attachS2MMNOCChannel(arrayDesign, "RapidSA", "s2mm", "drain_x0");
        // Primary MM2S in SLR 0 drives weight EBs, accum_shift, output_wr_en, done,
        // s2mm handshake, AND only the SLR 0 input EB root.
        RapidSANetlistBuilder.attachMM2SNOCChannel(arrayDesign, "RapidSA", "mm2s",
                createTileInstanceNames(nRows, nCols),
                weightEbRoots,
                new String[]{inputEbRoots[0]},
                createIndexedInstanceNames("drain_x", nCols),
                "done",
                ab.getMergedTileMap());
        RapidSANetlistBuilder.connectIngressEgressChannelHandshake(arrayDesign, "mm2s", "s2mm");
        // One additional input-EB-only MM2S per remaining SLR root.
        for (int i = 1; i < inputEbRoots.length; i++) {
            RapidSANetlistBuilder.attachSecondaryInputMM2SNOCChannel(arrayDesign, "RapidSA",
                    "mm2s_" + i, inputEbRoots[i]);
        }

        // Snapshot the fully wired black-box netlist (GEMM tiles + EBs + drains
        // are placed by createArray, but MM2S/S2MM/secondary MM2S have just been
        // added as wired black boxes and aren't yet placed). Useful for inspecting
        // the netlist topology before peripheral placement / flatten / flop-tree.
//        arrayDesign.writeCheckpoint("wired_blackbox_netlist.dcp");
//        System.out.println("** Wrote wired_blackbox_netlist.dcp (post-attach, pre-placement)");

        // Look up the SLR each input EB root landed in, so we can place the
        // secondary MM2S(es) in the same SLR as the EB they drive.
        SLR[] inputEbRootSLRs = new SLR[inputEbRoots.length];
        for (int i = 0; i < inputEbRoots.length; i++) {
            for (ModuleInst mi : arrayDesign.getModuleInsts()) {
                if (mi.getName().equals(inputEbRoots[i]) && mi.isPlaced()) {
                    inputEbRootSLRs[i] = mi.getPlacement().getTile().getSLR();
                    break;
                }
            }
            System.out.println("[MM2S-PLACE] inputEbRoots[" + i + "]=" + inputEbRoots[i]
                    + " landed in SLR id=" + (inputEbRootSLRs[i] == null ? "NULL" : inputEbRootSLRs[i].getId()));
        }

        // Place primary MM2S NOC channel at the top-right of the array (module pre-loaded above)
        placeMM2SNOCChannel(ab, mm2sModule);
        // Place each secondary MM2S at the top-right of its SLR
        for (int i = 1; i < inputEbRoots.length; i++) {
            placeMM2SNOCChannelInSLR(ab, mm2sModule, "mm2s_" + i, inputEbRootSLRs[i]);
        }

        // Place S2MM channel at the top-right of the array (module pre-loaded above)
        placeS2MMChannel(ab, s2mmModule);

        arrayDesign.getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(arrayDesign);
//        dumpUnroutedStaticPins(ab.getArray(), "place_after_nocchannels_unrouted_static_pins.txt");

        // Update registers
        // Set byte lane registers for Edge Buffer tiles
        int unitsPerTile = 4;
        for (int col = 0; col < nCols; col++) {
            int colOffset = col * unitsPerTile;
            for (int u = 0; u < unitsPerTile; u++) {
                EdgeBufferTile.setByteLane(arrayDesign, "weight_eb_x" + col, u, (colOffset + u) % 16);
                EdgeBufferTile.setWordIndex(arrayDesign, "weight_eb_x" + col, u, (colOffset + u) / 16);
            }
        }
        for (int row = 0; row < nRows; row++) {
            int rowOffset = row * unitsPerTile;
            for (int u = 0; u < unitsPerTile; u++) {
                EdgeBufferTile.setByteLane(arrayDesign, "input_eb_y" + row, u, (rowOffset + u) % 16);
                EdgeBufferTile.setWordIndex(arrayDesign, "input_eb_y" + row, u, (rowOffset + u) / 16);
            }
        }

        // Update FSM registers
        // TODO: uncomment after verifying hierarchy paths in Vivado
//        String fsmPrefix = "mm2s/mm2s_channel_i/sa_fsm_0/inst/inst";
//        SAControlFSM.setSAWidth(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setSAHeight(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setKDim(arrayDesign, fsmPrefix, 8);
//        SAControlFSM.setAccumShiftPipelineLatency(arrayDesign, fsmPrefix, flopTreeDepth);
//        SAControlFSM.setOutputWrPipelineLatency(arrayDesign, fsmPrefix, flopTreeDepth);
//        SAControlFSM.setDcuChainLatency(arrayDesign, fsmPrefix, dcuChainLatency);
        // Defaults derived from array size:
        //   maxDepthPerSLR  = ceil(log4(totalSinks))         (logical fanout per SLR)
        //   chainSlack      = max(3, ceil(nRows / 3))        (pacing for source<->boundary
        //                                                     and boundary<->dest hops; one
        //                                                     stage per ~3 array rows, so on
        //                                                     xcv80 the source-to-boundary
        //                                                     hop fits a per-flop span of
        //                                                     ~40-50 tile rows).
        //   flopTreeDepth   = maxDepthPerSLR + 2 + chainSlack (2 reserved for SLR crossing)
        // Picking maxDepthPerSLR independently of flopTreeDepth ensures there is
        // always a positive chain budget (flopTreeDepth - 2 - maxDepthPerSLR) that
        // insertFlopTreeForNet can split between source-side and dest-side pacing.
        int totalSinks = nRows * nCols;
        int defaultMaxDepthPerSLR = Math.max(2,
                (int) Math.ceil(Math.log(totalSinks) / Math.log(4)));
        int chainSlack = Math.max(3, (nRows + 2) / 3);
        int defaultFlopTreeDepth = defaultMaxDepthPerSLR + 2 + chainSlack;

        int flopTreeDepth = options.has("flop-tree-depth")
                ? (Integer) options.valueOf("flop-tree-depth")
                : defaultFlopTreeDepth;
        int maxDepthPerSLR = options.has("max-depth-per-slr")
                ? (Integer) options.valueOf("max-depth-per-slr")
                : defaultMaxDepthPerSLR;
        int chainBudget = Math.max(0, flopTreeDepth - 2 - maxDepthPerSLR);
        // Handshake chain (s2mm_start / s2mm_done) spans roughly the full array
        // height (MM2S near top of array, S2MM near bottom), so it needs roughly
        // 2x the per-half pacing. Default ~1 stage per 2 array rows, floor 5.
        int defaultHandshakeChainDepth = Math.max(5, (nRows + 1) / 2);
        int handshakeChainDepth = options.has("handshake-chain-depth")
                ? (Integer) options.valueOf("handshake-chain-depth")
                : defaultHandshakeChainDepth;
        System.out.println("** Flop tree: depth=" + flopTreeDepth
                + ", maxDepthPerSLR=" + maxDepthPerSLR
                + ", chainBudget=" + chainBudget
                + ", handshakeChainDepth=" + handshakeChainDepth
                + " (defaults from " + nRows + "x" + nCols
                + ": depth=" + defaultFlopTreeDepth
                + ", maxDepthPerSLR=" + defaultMaxDepthPerSLR
                + ", handshakeChainDepth=" + defaultHandshakeChainDepth + ")");

        // Build the no-go bbox list BEFORE flatten — flattenDesign() collapses
        // the post-flatten ModuleInsts (weight EBs, input EBs, drain tiles, MM2S,
        // S2MM placements) so we can't query them afterward. The pre-flatten
        // GEMM tile + SLR-crossing bboxes come from ArrayBuilder's retained list.
        List<RelocatableTileRectangle> noGoBboxes = new ArrayList<>(ab.getPlacedArrayBoundingBoxes());
        for (ModuleInst mi : arrayDesign.getModuleInsts()) {
            if (mi.isPlaced()) {
                Module mod = mi.getModule();
                noGoBboxes.add(mod.getBoundingBox()
                        .getCorresponding(mi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }
        System.out.println("** Inserted-flop placement no-go list: " + noGoBboxes.size()
                + " bboxes (placed array tiles + post-flatten ModuleInsts)");

        arrayDesign.flattenDesign();
        EDIFTools.uniqueifyNetlist(arrayDesign);

        // TEMP DIAGNOSTIC: find which net has multiple sources
        debugFindMultiSourceNets(arrayDesign);

        arrayDesign.getNetlist().collapseMacroUnisims(Series.Versal);
        FlopTreeTools.insertFlopTreeForNet(arrayDesign, "sa_accum_shift", "clk", flopTreeDepth, maxDepthPerSLR, noGoBboxes);
        FlopTreeTools.insertFlopTreeForNet(arrayDesign, "output_wr_en", "clk", flopTreeDepth, maxDepthPerSLR, noGoBboxes);
        DesignTools.makePhysNetNamesConsistent(arrayDesign);
        Set<SiteInst> chainSiteInsts = new HashSet<>();
        EDIFHierNet parentNet = arrayDesign.getNetlist().getParentNet(arrayDesign.getNetlist().getHierNetFromName("s2mm/s2mm_start"));
        Net s2mmStartNet = arrayDesign.getNet(parentNet.getHierarchicalNetName());
        // Filter to only remote sinks (in s2mm, not mm2s)
        List<EDIFHierPortInst> s2mmStartRemoteSinks = new ArrayList<>();
        for (EDIFHierPortInst p : s2mmStartNet.getLogicalHierNet().getLeafHierPortInsts(false)) {
            if (p.getFullHierarchicalInstName().startsWith("s2mm/")) {
                s2mmStartRemoteSinks.add(p);
            }
        }
        FlopTreeTools.insertFlopChain(arrayDesign, s2mmStartNet, "clk", handshakeChainDepth,
                s2mmStartRemoteSinks, chainSiteInsts, noGoBboxes);
        EDIFHierNet s2mmDoneParentNet = arrayDesign.getNetlist().getParentNet(arrayDesign.getNetlist().getHierNetFromName("mm2s/s2mm_done"));
        Net s2mmDoneNet = arrayDesign.getNet(s2mmDoneParentNet.getHierarchicalNetName());
        // Filter to only remote sinks (in mm2s, not s2mm)
        List<EDIFHierPortInst> s2mmDoneRemoteSinks = new ArrayList<>();
        for (EDIFHierPortInst p : s2mmDoneNet.getLogicalHierNet().getLeafHierPortInsts(false)) {
            if (p.getFullHierarchicalInstName().startsWith("mm2s/")) {
                s2mmDoneRemoteSinks.add(p);
            }
        }
        FlopTreeTools.insertFlopChain(arrayDesign, s2mmDoneNet, "clk", handshakeChainDepth,
                s2mmDoneRemoteSinks, chainSiteInsts, noGoBboxes);

        for (SiteInst si : chainSiteInsts) {
            si.routeSite();
        }

        arrayDesign.getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(arrayDesign);
//        dumpUnroutedStaticPins(ab.getArray(), "before_bufg_unrouted_static_pins.txt");

        // Insert the top-level BUFGCE just before write/route, so it doesn't
        // perturb the earlier peripheral placement (which iterates the design's
        // SiteInsts to derive array top/bottom row constraints) and so the
        // BUFGCE site doesn't sit inside the design throughout the build.
        insertTopLevelBUFGCE(arrayDesign);

        // [diag] dump every SitePinInst on the bufg SiteInst across all nets,
        // with their connectedNode, so we can verify that RapidWright created
        // the BUFGCE pins on the same physical nodes Vivado expects (e.g. the
        // CLK_BUFGCE_<n>_CE_PIN that report_route_status complains about).
        SiteInst bufgSiteInst = arrayDesign.getSiteInstFromSiteName("BUFGCE_X2Y0");
        if (bufgSiteInst != null) {
            for (SitePinInst spi : bufgSiteInst.getSitePinInsts()) {
                Net n = spi.getNet();
                System.out.println("[bufg-pin] " + spi
                        + " net=" + (n == null ? "<null>" : n.getName())
                        + " connectedNode=" + spi.getConnectedNode());
            }
        } else {
            System.out.println("[bufg-pin] no SiteInst at BUFGCE_X2Y0!");
        }

        // Add clock constraint
        arrayDesign.addXDCConstraint("create_clock -period 2.0 -name clk [get_ports clk_in]");

        arrayDesign.setDesignOutOfContext(true);
        String baseDcpName = "systolic_array_" + nRows + "x" + nCols;
        if (!options.hasArgument("route") && !options.hasArgument("vivado-route")) {
            arrayDesign.writeCheckpoint(baseDcpName + ".dcp");
        }
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(arrayDesign);
//        dumpUnroutedStaticPins(ab.getArray(), "before_route_unrouted_static_pins.txt");

        // [diag] static-net pin/PIP counts immediately before routing, so we
        // can attribute the unrouted-pin growth to RapidSA additions vs.
        // RWRoute-internal pin materialization (createPossiblePinsToStaticNets).
//        System.out.println("[diag] pre-route array GND pins=" + arrayDesign.getGndNet().getPins().size()
//                + " PIPs=" + arrayDesign.getGndNet().getPIPs().size());
//        System.out.println("[diag] pre-route array VCC pins=" + arrayDesign.getVccNet().getPins().size()
//                + " PIPs=" + arrayDesign.getVccNet().getPIPs().size());

        // [diag] dump unrouted static-net pins (one file per static net) as
        // logical pin names so we can compare against Vivado's view of the
        // same design without paying the cost of get_pips/get_nodes traversal.
//        for (Net staticNet : new Net[] { arrayDesign.getGndNet(), arrayDesign.getVccNet() }) {
//            if (staticNet == null) continue;
//            String fileName = "unrouted_" + staticNet.getName().replace('<', '_').replace('>', '_') + ".txt";
//            int unrouted = 0;
//            try (java.io.PrintWriter pw = new java.io.PrintWriter(fileName)) {
//                for (SitePinInst spi : staticNet.getPins()) {
//                    if (spi.isOutPin() || spi.isRouted()) continue;
//                    boolean wroteAny = false;
//                    SiteInst si = spi.getSiteInst();
//                    for (com.xilinx.rapidwright.device.BELPin belPin : DesignTools.getConnectedBELPins(spi)) {
//                        Cell cell = si.getCell(belPin.getBEL());
//                        if (cell == null) continue;
//                        String logical = cell.getLogicalPinMapping(belPin.getName());
//                        if (logical == null) continue;
//                        pw.println(cell.getName() + "/" + logical);
//                        wroteAny = true;
//                    }
//                    if (!wroteAny) {
//                        // Fall back to the physical pin if no logical mapping exists
//                        // (e.g. dedicated tie-offs with no leaf-cell connection).
//                        pw.println(spi);
//                    }
//                    unrouted++;
//                }
//            } catch (java.io.IOException e) {
//                throw new RuntimeException("Failed to write " + fileName, e);
//            }
//            System.out.println("[diag] wrote " + unrouted + " unrouted " + staticNet.getName() + " pins to " + fileName);
//        }

        if (options.has("route")) {
            arrayDesign.getNetlist().expandMacroUnisims();

            // Mirror RWRoute's own entry-time preprocess so the pre-route
            // report reflects the same physical surface RWRoute will see:
            // makePhysNetNamesConsistent, createPossiblePinsToStaticNets
            // (materializes static SitePinInsts), createMissingSitePinInsts,
            // updateVersalXPHYPinsForDMC.

//            DesignTools.updatePinsIsRouted(arrayDesign);
//            com.xilinx.rapidwright.util.ReportRouteStatusResult preRrs =
//                    com.xilinx.rapidwright.util.ReportRouteStatus.reportRouteStatus(arrayDesign);
//            System.out.println(preRrs.toString("RapidWright Route Status (pre-RWRoute)"));
//            if (!preRrs.isFullyRouted()) {
//                dumpFailingNets(arrayDesign, preRrs, baseDcpName + "_unrouted_nets_pre.txt");
//            }

            // Detect nets where the source SitePinInst lost its outgoing PIPs
            // during Module relocation (a known issue with certain Versal SLICE
            // output pin types: G_O, C_O, H_O, EQ2, etc.). PartialRouter's
            // getUnroutedPins skips output pins so these never get queued for
            // routing. Unroute them entirely so RWRoute sees them as fresh and
            // re-routes from scratch.
//            DesignTools.updatePinsIsRouted(arrayDesign);
//            int unroutedNetsRescued = 0;
//            for (Net n : arrayDesign.getNets()) {
//                if (n.isStaticNet()) continue;
//                if (com.xilinx.rapidwright.design.NetTools.isGlobalClock(n)) continue;
//                SitePinInst src = n.getSource();
//                if (src == null || src.isRouted()) continue;
//                // Source SitePinInst exists but has no outgoing PIPs — broken
//                // relocation. Unroute the whole net so RWRoute reroutes from
//                // scratch using sink-pin queueing.
//                n.unroute();
//                unroutedNetsRescued++;
//            }
//            if (unroutedNetsRescued > 0) {
//                System.out.println("** Pre-route: unrouted " + unroutedNetsRescued
//                        + " signal nets with !isRouted source pins so RWRoute can re-route them");
//            }

            System.out.println("** Running RWRoute partial route + HoldFixer on " + baseDcpName + ".dcp (softPreserve=true)");
            PartialRouter.routeDesignWithUserDefinedArguments(arrayDesign,
                    new String[]{
                            "--useUTurnNodes",
                            "--nonTimingDriven",
                    },
                    /*pinsToRoute=*/ null,
                    /*softPreserve=*/ false);
//            routeClockNetIfUnrouted(arrayDesign, "clk");
            // HoldFixer holdFixer = new HoldFixer(arrayDesign, "clk");
            // holdFixer.fixHoldViolations();

//            DesignTools.updatePinsIsRouted(arrayDesign);
//            com.xilinx.rapidwright.util.ReportRouteStatusResult rrs =
//                    com.xilinx.rapidwright.util.ReportRouteStatus.reportRouteStatus(arrayDesign);
//            System.out.println(rrs.toString("RapidWright Route Status (post-RWRoute)"));
//            if (!rrs.isFullyRouted()) {
//                System.out.println("** WARNING: design is NOT fully routed (unroutedNets="
//                        + rrs.unroutedNets + ", routingErrors=" + rrs.netsWithRoutingErrors
//                        + ", someUnroutedPins=" + rrs.netsWithSomeUnroutedPins
//                        + ", resourceConflicts=" + rrs.netsWithResourceConflicts + ")");
//                dumpFailingNets(arrayDesign, rrs, baseDcpName + "_unrouted_nets.txt");
//            }

            arrayDesign.writeCheckpoint(baseDcpName + "_routed.dcp");
            System.out.println("** Wrote " + baseDcpName + "_routed.dcp");
        }

        if (options.has("vivado-route")) {
            Path workdir = Paths.get(".").toAbsolutePath().normalize();
            Path inputDcp = workdir.resolve(baseDcpName + ".dcp");
            Path outputDcp = workdir.resolve(baseDcpName + "_vivado_routed.dcp");
            Path tclLog = workdir.resolve(baseDcpName + "_vivado_route.log");
            StringBuilder tcl = new StringBuilder()
                    .append("open_checkpoint {").append(inputDcp).append("}; ")
                    .append("route_design; ")
                    .append("write_checkpoint -force {").append(outputDcp).append("}; ")
                    .append("report_route_status; ");
            if (options.has("report-timing")) {
                // Same Vivado session — no need to re-load the DCP.
                tcl.append(reportTimingTcl());
            }
            System.out.println("** Running Vivado route_design on " + inputDcp
                    + (options.has("report-timing") ? " (with report-timing in same session)" : ""));
            VivadoTools.runTcl(tclLog, tcl.toString(), true);
            System.out.println("** Wrote " + outputDcp);
        }

        if (options.has("lsf-route")) {
            runLsfRoute(baseDcpName, options);
        }

        // Standalone report-timing pass (skipped if --vivado-route already
        // did it in the same session above).
        if (options.has("report-timing") && !options.has("vivado-route")) {
            runReportTiming(baseDcpName, options);
        }
    }

    /**
     * Returns the Tcl snippet that runs the timing report. Used both inline
     * inside the --vivado-route session and from a standalone Vivado session.
     */
    private static String reportTimingTcl() {
        return "puts {===== report_timing_summary =====}; "
                + "report_timing_summary; "
                + "puts {===== report_timing -nworst 10 (setup) =====}; "
                + "report_timing -nworst 10 -setup; "
                + "puts {===== report_timing -nworst 10 (hold) =====}; "
                + "report_timing -nworst 10 -hold; ";
    }

    /**
     * Picks the most recently-produced routed DCP (preferring vivado &gt; lsf &gt;
     * local RWRoute &gt; unrouted) and opens it in a Vivado session to run the
     * timing report. Output streams live so timing tables print to the terminal.
     */
    private static void runReportTiming(String baseDcpName, joptsimple.OptionSet options) {
        Path workdir = Paths.get(".").toAbsolutePath().normalize();
        // Pick the routed DCP that exists, in order of preference.
        Path[] candidates = new Path[]{
                workdir.resolve(baseDcpName + "_vivado_routed.dcp"),
                workdir.resolve(baseDcpName + "_lsf_routed.dcp"),
                workdir.resolve(baseDcpName + "_routed.dcp"),
                workdir.resolve(baseDcpName + ".dcp"),
        };
        Path dcp = null;
        for (Path c : candidates) {
            if (java.nio.file.Files.exists(c)) {
                dcp = c;
                break;
            }
        }
        if (dcp == null) {
            throw new RuntimeException("No DCP found to report timing on (looked for "
                    + java.util.Arrays.toString(candidates) + ")");
        }

        Path tclLog = workdir.resolve(baseDcpName + "_report_timing.log");
        String tcl = "open_checkpoint {" + dcp + "}; " + reportTimingTcl();
        System.out.println("** Running Vivado report_timing on " + dcp);
        VivadoTools.runTcl(tclLog, tcl, true);
        System.out.println("** Timing report log: " + tclLog);
    }

    /** Single-quote a string for safe inclusion in a `sh -c` command line. */
    private static String shellEscape(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Submits an RWRoute partial route + HoldFixer pass on the unrouted DCP via
     * bsub and blocks (using {@code bsub -K}) until the LSF job finishes. The
     * routed DCP lands at {@code <baseDcpName>_lsf_routed.dcp}.
     */
    private static void runLsfRoute(String baseDcpName, joptsimple.OptionSet options) {
        Path workdir = Paths.get(".").toAbsolutePath().normalize();
        Path inputDcp = workdir.resolve(baseDcpName + ".dcp");
        Path outputDcp = workdir.resolve(baseDcpName + "_lsf_routed.dcp");
        Path stdoutLog = workdir.resolve(baseDcpName + "_lsf_route.out");
        Path stderrLog = workdir.resolve(baseDcpName + "_lsf_route.err");

        String defaultBsubOpts = "-n 4 -R rusage[mem=64000] -W 6:00";
        String bsubOpts = options.has("lsf-bsub-opts")
                ? (String) options.valueOf("lsf-bsub-opts")
                : defaultBsubOpts;

        String launcher = com.xilinx.rapidwright.util.FileTools.getRapidWrightPath()
                + java.io.File.separator + "bin" + java.io.File.separator + "rapidwright";
        String jvmHeap = "60g";

        // Write a small wrapper script next to the DCPs. We need this (rather
        // than passing a one-liner via `sh -c`) because some LSF hosts have a
        // broken profile-sourcing chain (e.g. Vivado settings64.sh fails when
        // awk isn't on the default PATH), which kills the bsub shell before
        // our launcher ever runs. The wrapper:
        //   1. Sets PATH so basic utilities are findable BEFORE
        //   2. Sourcing .bashrc (so Vivado/Java env vars get set up properly)
        //   3. Sets _JAVA_OPTIONS for the heap
        //   4. Execs the launcher
        Path wrapperScript = workdir.resolve(baseDcpName + "_lsf_route.sh");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/bash\n");
        sb.append("export PATH=/usr/bin:/bin:$PATH\n");
        sb.append("[ -f \"$HOME/.bashrc\" ] && source \"$HOME/.bashrc\"\n");
        sb.append("export _JAVA_OPTIONS=\"-Xmx").append(jvmHeap).append("\"\n");
        sb.append("exec ").append(launcher)
                .append(" com.xilinx.rapidwright.rapidsa.RapidSARouteJob ")
                .append(inputDcp).append(' ')
                .append(outputDcp).append(" clk\n");
        try {
            java.nio.file.Files.write(wrapperScript, sb.toString().getBytes());
            wrapperScript.toFile().setExecutable(true);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write LSF wrapper script " + wrapperScript, e);
        }

        // bsub -K blocks until the job exits.
        String fullBsub = "bsub -K " + bsubOpts
                + " -o " + stdoutLog
                + " -e " + stderrLog
                + " /bin/bash " + wrapperScript;
        System.out.println("** Submitting LSF route job (blocking until complete):");
        System.out.println("   " + fullBsub);

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", fullBsub);
        pb.inheritIO();
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new RuntimeException("bsub exited with status " + exit
                        + "; see " + stdoutLog + " and " + stderrLog + " for details");
            }
            if (!java.nio.file.Files.exists(outputDcp)) {
                throw new RuntimeException("LSF job reported success but " + outputDcp
                        + " was not produced; see " + stdoutLog + " and " + stderrLog);
            }
            System.out.println("** LSF route done: " + outputDcp);
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("Failed to run LSF route via bsub: " + e.getMessage(), e);
        }
    }

    /**
     * Surgically replaces the top-level {@code clk} input port (which currently
     * drives the {@code clk} fanout net) with a BUFGCE: a new {@code clk_in}
     * top-level input drives the BUFG.I, the BUFG.O drives the existing
     * {@code clk} net (preserving all sub-module clk fan-out), and CE is tied
     * to VCC. The BUFG is physically placed at {@code BUFGCE_X2Y0}.
     */
    private static void insertTopLevelBUFGCE(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        EDIFNet clkNet = topCell.getNet("clk");
        if (clkNet == null) {
            throw new RuntimeException("Expected a top-level 'clk' net to replace with BUFGCE output");
        }

        // Detach the old top-level 'clk' port from the clk fanout net and
        // remove it; the buffer's output will drive that net instead. The
        // existing per-instance clk port-insts on the net are preserved.
        EDIFPort oldClkPort = topCell.getPort("clk");
        if (oldClkPort != null) {
            EDIFPortInst oldPortInst = clkNet.getPortInst(null, "clk");
            if (oldPortInst != null) {
                clkNet.removePortInst(oldPortInst);
            }
            topCell.removePort(oldClkPort);
        }

        // Create the physical BUFGCE (handles intra-site CEINV/IINV SitePIPs,
        // intra-site VCC route to CE, the CE SitePinInst on VCC, and the macro
        // pin mapping fix-ups in one call).
        Site bufgSite = design.getDevice().getSite("BUFGCE_X2Y0");
        if (bufgSite == null) {
            throw new RuntimeException("Site BUFGCE_X2Y0 not found on device " + design.getPartName());
        }
        Cell bufg = ArrayBuilder.createBUFGCE(design, topCell, "bufg", bufgSite);
        EDIFCellInst bufgInst = bufg.getEDIFCellInst();

        // BUFG.O drives the existing clk net (which already has all the fanout).
        // Use the physical Net.connect so the source SitePinInst is created on
        // the physical net (otherwise RWRoute will not see the clock as having
        // a routable source and will skip clock routing entirely).
        Net physicalClk = design.getNet("clk");
        if (physicalClk == null) {
            physicalClk = design.createNet("clk");
        }
        physicalClk.connect(bufg, "O");

        // New top-level clk_in input drives BUFG.I. Same story for the input
        // side — use Net.connect so a SitePinInst is registered on BUFG.I.
        EDIFPort clkInPort = topCell.createPort("clk_in", EDIFDirection.INPUT, 1);
        Net physicalClkIn = design.createNet("clk_in");
        physicalClkIn.getLogicalNet().createPortInst(clkInPort);
        physicalClkIn.connect(bufg, "I");

        System.out.println("** Inserted top-level BUFGCE 'bufg' at BUFGCE_X2Y0 (clk_in -> BUFG.I, BUFG.O -> clk fanout, CE -> VCC)");
    }

    /**
     * Routes the named clock net using the symmetric Versal clock router if it
     * does not already have PIPs. Required after PartialRouter when the clock
     * source is a freshly-inserted BUFGCE that PartialRouter did not know to
     * route, otherwise HoldFixer cannot find a VROUTE root for the clock tree.
     */
    static void routeClockNetIfUnrouted(Design design, String clkName) {
        Net clk = design.getNet(clkName);
        if (clk == null) {
            System.out.println("** routeClockNetIfUnrouted: no physical net named '" + clkName + "', skipping");
            return;
        }
        if (clk.hasPIPs()) {
            System.out.println("** routeClockNetIfUnrouted: '" + clkName + "' already has " + clk.getPIPs().size() + " PIPs, skipping");
            return;
        }
        if (clk.getSource() == null) {
            throw new RuntimeException("Cannot route clock '" + clkName + "': net has no source SitePinInst");
        }
        System.out.println("** routeClockNetIfUnrouted: routing '" + clkName + "' (" + clk.getPins().size() + " pins) with symmetricClkRouting");
        GlobalSignalRouting.symmetricClkRouting(
                clk,
                design.getDevice(),
                node -> NodeStatus.AVAILABLE,
                new HashMap<>());
        System.out.println("** routeClockNetIfUnrouted: '" + clkName + "' now has " + clk.getPIPs().size() + " PIPs");
    }

    /**
     * Writes the failing nets from a ReportRouteStatusResult to a file (one
     * net per line, plus its unrouted SitePinInsts indented). Skips clock and
     * reset nets — clock is handled by the dedicated clock router; reset is
     * intentionally left dangling for AVED to drive at integration time.
     */
    /**
     * Writes unrouted SitePinInsts on the design's VCC and GND nets to a
     * file, one pin per line under its net header. Skips output (source) pins.
     * Intended for the loadRelocatableModule diagnostic where only static-net
     * coverage on the precompile DCP is interesting.
     */
    private static void dumpUnroutedStaticPins(Design design, String dumpFile) {
        int gndPinsBefore = design.getGndNet() != null ? design.getGndNet().getPins().size() : 0;
        int vccPinsBefore = design.getVccNet() != null ? design.getVccNet().getPins().size() : 0;
        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(design);
        int gndPinsAfter = design.getGndNet() != null ? design.getGndNet().getPins().size() : 0;
        int vccPinsAfter = design.getVccNet() != null ? design.getVccNet().getPins().size() : 0;
        System.out.println("[diag] RWRoute.preprocess pin delta:"
                + " GND " + gndPinsBefore + "->" + gndPinsAfter + " (+" + (gndPinsAfter - gndPinsBefore) + ")"
                + ", VCC " + vccPinsBefore + "->" + vccPinsAfter + " (+" + (vccPinsAfter - vccPinsBefore) + ")");
        DesignTools.updatePinsIsRouted(design);
        int totalUnrouted = 0;
        try (java.io.PrintWriter pw = new java.io.PrintWriter(dumpFile)) {
            for (Net staticNet : new Net[] { design.getVccNet(), design.getGndNet() }) {
                if (staticNet == null) continue;
                java.util.List<SitePinInst> bad = new java.util.ArrayList<>();
                for (SitePinInst spi : staticNet.getPins()) {
                    if (spi.isOutPin()) continue;
                    if (!spi.isRouted()) bad.add(spi);
                }
                pw.println(staticNet.getName() + "  (" + bad.size() + " unrouted sink pins)");
                for (SitePinInst spi : bad) {
                    pw.println("    [IN ] " + spi + "  connectedNode=" + spi.getConnectedNode());
                }
                totalUnrouted += bad.size();
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write " + dumpFile, e);
        }
        System.out.println("** Dumped " + totalUnrouted + " unrouted static-net sink pins to " + dumpFile);
    }

    private static void dumpFailingNets(Design design,
                                        com.xilinx.rapidwright.util.ReportRouteStatusResult rrs,
                                        String dumpFile) {
        java.util.Set<String> skipNetNames = new java.util.HashSet<>(
                java.util.Arrays.asList("clk", "clk_in", "rst_n", "rst", "reset", "reset_n"));
        int netsDumped = 0;
        int pinsDumped = 0;
        java.util.Map<String, Integer> categoryHistogram = new java.util.TreeMap<>();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(dumpFile)) {
            java.util.List<Net> failing = new java.util.ArrayList<>();
            for (Net n : rrs.netsWithSomeUnroutedPinsList) failing.add(n);
            for (Net n : rrs.unroutedNetsList) failing.add(n);
            for (Net n : rrs.netsWithResourceConflictsList) failing.add(n);
            failing.sort(java.util.Comparator.comparing(Net::getName));
            for (Net n : failing) {
                if (com.xilinx.rapidwright.design.NetTools.isGlobalClock(n)) continue;
                if (skipNetNames.contains(n.getName())) continue;
                java.util.List<SitePinInst> bad = new java.util.ArrayList<>();
                for (SitePinInst spi : n.getPins()) {
                    if (!spi.isRouted()) bad.add(spi);
                }
                netsDumped++;
                pinsDumped += bad.size();
                boolean hasLogical = design.getNetlist()
                        .getHierNetFromName(n.getName()) != null;
                String category = rrs.netsWithResourceConflictsList.contains(n)
                        ? "RESOURCE_CONFLICT"
                        : (rrs.unroutedNetsList.contains(n) ? "FULLY_UNROUTED" : "PARTIALLY_ROUTED");
                categoryHistogram.merge(category, 1, Integer::sum);
                pw.println(n.getName() + "  (" + bad.size()
                        + " unrouted pins, " + category
                        + ", edifNet=" + (hasLogical ? "yes" : "NO") + ")");
                for (SitePinInst spi : bad) {
                    String node = String.valueOf(spi.getConnectedNode());
                    String dir = spi.isOutPin() ? "OUT" : "IN ";
                    pw.println("    [" + dir + "] " + spi + "  connectedNode=" + node);
                }
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to write " + dumpFile, e);
        }
        System.out.println("** Dumped " + pinsDumped + " unrouted pins across "
                + netsDumped + " nets to " + dumpFile + " (excluding clock/reset)");
        System.out.println("** Failing-net category breakdown: " + categoryHistogram);
    }

    /**
     * Counts signal nets where the source {@code SitePinInst} exists but its
     * {@code connectedNode} has no outgoing PIPs (so {@code isRouted()} is
     * false). These look like Module-relocation casualties — the source pin
     * survives translation but the upstream/downstream PIPs don't. Static
     * and clock nets are skipped since they're handled separately. Prints a
     * one-line summary tagged with the supplied stage label.
     */
    private static void countSourceUnroutedSignalNets(Design design, String stageLabel) {
        DesignTools.updatePinsIsRouted(design);
        int unrouted = 0;
        String firstName = null;
        for (Net n : design.getNets()) {
            if (n.isStaticNet()) continue;
            if (com.xilinx.rapidwright.design.NetTools.isGlobalClock(n)) continue;
            SitePinInst src = n.getSource();
            if (src == null || src.isRouted()) continue;
            unrouted++;
            if (firstName == null) firstName = n.getName();
        }
        System.out.println("[source-unrouted-signal] " + stageLabel + ": " + unrouted + " net(s)"
                + (firstName != null ? "  e.g. " + firstName : ""));
    }

    /**
     * Counts physical-only Nets (no EDIF logical net) whose simple-tail name
     * matches Vivado's per-cell static-tie naming convention
     * ({@code VCC(_N)?} / {@code GND(_N)?}). These are the orphans that show
     * up at array RWRoute time as unrouted sinks with edifNet=NO. Prints a
     * one-line summary tagged with the supplied stage label so multiple
     * snapshots can be compared to bisect when the orphans appear.
     */
    private static void countOrphanStaticTieNets(Design design, String stageLabel) {
        EDIFNetlist netlist = design.getNetlist();
        Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        int orphanNets = 0;
        int orphanPins = 0;
        String firstName = null;
        for (Net n : design.getNets()) {
            if (n == vcc || n == gnd) continue;
            if (netlist.getHierNetFromName(n.getName()) != null) continue;
            String tail = n.getName().substring(n.getName().lastIndexOf('/') + 1);
            if (!tail.matches("VCC(_\\d+)?|GND(_\\d+)?")) continue;
            orphanNets++;
            for (SitePinInst spi : n.getPins()) {
                if (!spi.isOutPin()) orphanPins++;
            }
            if (firstName == null) firstName = n.getName();
        }
        System.out.println("[orphan-static-tie] " + stageLabel + ": " + orphanNets
                + " net(s), " + orphanPins + " sink pin(s)"
                + (firstName != null ? "  e.g. " + firstName : ""));
    }

    private static String[] createIndexedInstanceNames(String prefix, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = prefix + i;
        }
        return names;
    }

    /**
     * For instances named {@code prefix0..prefix(count-1)} placed in the design,
     * returns the name of the lowest-indexed instance that landed in each SLR.
     * The result is sorted by EB index ascending, so {@code [0]} is the
     * topmost EB (lowest logical index) and matches where the primary
     * peripheral channel would be placed at the top of the array.
     */
    private static String[] computeFirstEbPerSLR(Design design, String prefix, int count) {
        Map<String, ModuleInst> miByName = new HashMap<>();
        for (ModuleInst mi : design.getModuleInsts()) {
            miByName.put(mi.getName(), mi);
        }
        TreeMap<Integer, Integer> firstIndexPerSLR = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            ModuleInst mi = miByName.get(prefix + i);
            if (mi == null || !mi.isPlaced()) continue;
            SLR slr = mi.getPlacement().getTile().getSLR();
            firstIndexPerSLR.putIfAbsent(slr.getId(), i);
        }
        return firstIndexPerSLR.values().stream()
                .sorted()
                .map(i -> prefix + i)
                .toArray(String[]::new);
    }

    private static void debugFindMultiSourceNets(Design design) {
        EDIFNetlist nl = design.getNetlist();
        EDIFCell topCell = nl.getTopCell();

        // For each top-level net, simulate getNetAliases and catch the exception.
        for (EDIFNet net : topCell.getNets()) {
            com.xilinx.rapidwright.edif.EDIFHierNet hn =
                    nl.getHierNetFromName(net.getName());
            try {
                nl.getNetAliases(hn);
            } catch (RuntimeException e) {
                System.err.println("getNetAliases FAILS for net '" + net.getName()
                        + "': " + e.getMessage());
                // Dump all port-insts on this exact net (top-level only)
                for (EDIFPortInst p : net.getPortInsts()) {
                    EDIFCellInst inst = p.getCellInst();
                    String kind;
                    if (inst == null) {
                        kind = p.isInput() ? "TOP-INPUT" : (p.isOutput() ? "TOP-OUTPUT" : "TOP-INOUT");
                    } else {
                        boolean leaf = inst.getCellType().isLeafCellOrBlackBox();
                        kind = (leaf ? "LEAF-" : "HIER-") + (p.isOutput() ? "OUT" : (p.isInput() ? "IN" : "INOUT"));
                    }
                    System.err.println("    " + kind + " " + (inst == null ? "<top>" : inst.getName())
                            + "." + p.getName());
                }
            }
        }
    }

    private static String[][] createTileInstanceNames(int nRows, int nCols) {
        String[][] names = new String[nRows][nCols];
        for (int row = 0; row < nRows; row++) {
            for (int col = 0; col < nCols; col++) {
                names[row][col] = "tile_x" + col + "y" + row;
            }
        }
        return names;
    }

    /**
     * Loads a component's pnr.dcp and creates a relocatable Module, removing
     * clock routing, BUFGs, and orphaned static tie-off cells that inflate
     * the bounding box.
     */
    private static <T> T awaitFuture(Deque<Future<T>> futures) {
        try {
            return futures.pollFirst().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    private static Module awaitModule(Deque<Future<Module>> futures) {
        return awaitFuture(futures);
    }

    private static Module loadRelocatableModule(String precompileDir, RapidComponent component,
                                                 com.xilinx.rapidwright.device.Device targetDevice) {
        String dcpPath = precompileDir + File.separator
                + component.getComponentName() + File.separator + "pnr.dcp";
        Design pnrDesign = Design.readCheckpoint(dcpPath);
        EDIFTools.removeVivadoBusPreventionAnnotations(pnrDesign.getNetlist());
        pnrDesign.getNetlist().expandMacroUnisims();
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(pnrDesign);

        // [diag] precompile snapshot — count GND SitePinInsts on SLICEM SiteInsts
        // and on the GND net total. Distinguishes Case A (PIPs/pins were in the
        // precompile and got lost in relocation) from Case B (pins materialized
        // by createPossiblePinsToStaticNets at array RWRoute time).
        {
            Net pcGnd = pnrDesign.getGndNet();
            int totalGndSinks = 0;
            int slicemGndSinks = 0;
            int slicemGndSitesWithPins = 0;
            java.util.Set<SiteInst> slicemSeen = new java.util.HashSet<>();
            if (pcGnd != null) {
                for (SitePinInst spi : pcGnd.getPins()) {
                    if (spi.isOutPin()) continue;
                    totalGndSinks++;
                    SiteInst si = spi.getSiteInst();
                    if (si == null || si.getSite() == null) continue;
                    if (si.getSiteTypeEnum() != null
                            && si.getSiteTypeEnum().name().startsWith("SLICEM")) {
                        slicemGndSinks++;
                        if (slicemSeen.add(si)) slicemGndSitesWithPins++;
                    }
                }
            }
        }

        ArrayBuilder.removeBUFGs(pnrDesign);
        Net clkNet = pnrDesign.getNet(component.getClkName());
        if (clkNet != null) {
            clkNet.unroute();
        }
        // Remove SiteInsts that only contain <LOCKED> cells or are static sources
//        List<SiteInst> toRemove = new ArrayList<>();
//        int staticSourceCount = 0;
//        int allLockedCount = 0;
//        for (SiteInst si : pnrDesign.getSiteInsts()) {
//            if (si.getName().startsWith(SiteInst.STATIC_SOURCE)) {
//                toRemove.add(si);
//                staticSourceCount++;
//                continue;
//            }
//            boolean allLocked = !si.getCells().isEmpty();
//            for (Cell c : si.getCells()) {
//                if (!c.getName().equals("<LOCKED>")) {
//                    allLocked = false;
//                    break;
//                }
//            }
//            if (allLocked) {
//                toRemove.add(si);
//                allLockedCount++;
//            }
//        }
//        for (SiteInst si : toRemove) {
//            pnrDesign.removeSiteInst(si);
//        }
//        System.out.println("** loadRelocatableModule[" + component.getComponentName()
//                + "]: removed " + staticSourceCount + " STATIC_SOURCE SiteInsts, "
//                + allLockedCount + " all-<LOCKED> SiteInsts (total SiteInsts before removal: "
//                + (pnrDesign.getSiteInsts().size() + toRemove.size()) + ")");

        // Mirror RWRoute's own entry-time preprocess so the route-status
        // report sees the same physical surface RWRoute would: materialize
        // static-net SitePinInsts, fill in missing site pins, etc.
//        com.xilinx.rapidwright.rwroute.RWRoute.preprocess(pnrDesign);

        // Refresh isRouted from current PIP topology and report route status
        // on the loaded precompile DCP, so we can spot any per-component
        // routing gaps before Module relocation hides them.
//        DesignTools.updatePinsIsRouted(pnrDesign);
//        com.xilinx.rapidwright.util.ReportRouteStatusResult rrs =
//                com.xilinx.rapidwright.util.ReportRouteStatus.reportRouteStatus(pnrDesign);
//        System.out.println(rrs.toString("Route Status: loadRelocatableModule[" + component.getComponentName() + "]"));

        // Pass unrouteStaticNets=false so the precompile's GND/VCC PIPs
        // survive into the Module template and relocate into the array's
        // global static nets along with the rest of the routing. The
        // single-arg ctor defaults to true, which silently dropped these PIPs
        // and left ~tens of GND sinks unrouted in the array.
        Module module = new Module(pnrDesign, false);
        module.calculateAllValidPlacements(targetDevice);
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
            String instName = "weight_eb_x" + col;

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

            System.out.println("  ** PLACED WeightEB: " + instName + " at " + bestAnchor);
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
        List<String> placedBoundingBoxNames = new ArrayList<>();

        // Seed with the GEMM tile + SLR-crossing bboxes ArrayBuilder retained pre-flatten.
        for (RelocatableTileRectangle bb : ab.getPlacedArrayBoundingBoxes()) {
            placedBoundingBoxes.add(bb);
            placedBoundingBoxNames.add("array_bbox");
        }
        // Then add bboxes for any post-flatten ModuleInsts (weight EBs placed earlier in this run).
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                placedBoundingBoxes.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
                placedBoundingBoxNames.add(existingMi.getName());
            }
        }

        // Find number of columns from centroid map
        int nCols = 0;
        for (Pair<Integer, Integer> key : centroidMap.keySet()) {
            nCols = Math.max(nCols, key.getFirst() + 1);
        }

        // Compute the array's rightmost column from GEMM tile centroids only
        // (not from weight EBs or other modules placed above/below)
        int arrayRightCol = Integer.MIN_VALUE;
        for (Site s : centroidMap.values()) {
            arrayRightCol = Math.max(arrayRightCol, s.getTile().getColumn());
        }

        // Build occupied tiles set for site-level conflict checking
        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Compute the row offset from the module anchor to the module's centroid
        int anchorRow = dcuModule.getAnchor().getTile().getRow();
        int bbCenterRow = (moduleBoundingBox.getMinRow() + moduleBoundingBox.getMaxRow()) / 2;
        int anchorToCentroidRowOffset = bbCenterRow - anchorRow;

        for (int row = nRows - 1; row >= 0; row--) {
            Site targetCentroid = centroidMap.get(new Pair<>(nCols - 1, row));
            String instName = "input_eb_y" + row;

            ModuleInst mi = arrayDesign.createModuleInst(instName, dcuModule);

            Site bestAnchor = null;
            int bestDist = Integer.MAX_VALUE;

            for (Site anchor : dcuModule.getAllValidPlacements()) {
                RelocatableTileRectangle candidateBB = moduleBoundingBox
                        .getCorresponding(anchor.getTile(), dcuModule.getAnchor().getTile());

                // Left edge must be right of GEMM array
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

                // Check site-level conflicts with occupied tiles
                boolean siteConflict = false;
                for (SiteInst modSi : dcuModule.getSiteInsts()) {
                    Site newSite = dcuModule.getCorrespondingSite(modSi, anchor);
                    if (newSite != null && occupiedTiles.contains(newSite.getTile())) {
                        siteConflict = true;
                        break;
                    }
                }
                if (siteConflict) continue;

                // Use module centroid (not anchor) for distance calculation
                int moduleCentroidRow = anchor.getTile().getRow() + anchorToCentroidRowOffset;
                int dist = Math.abs(anchor.getTile().getColumn() - targetCentroid.getTile().getColumn())
                         + Math.abs(moduleCentroidRow - targetCentroid.getTile().getRow());
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

            RelocatableTileRectangle placedBb = moduleBoundingBox
                    .getCorresponding(bestAnchor.getTile(), dcuModule.getAnchor().getTile());
            placedBoundingBoxes.add(placedBb);
            placedBoundingBoxNames.add(instName);

            // Diagnostic: print this EB's bbox and check for overlap against everything
            // already in placedBoundingBoxes (excluding the one we just added).
            System.out.println("[InputEB-PLACE] " + instName + " bbox: minCol=" + placedBb.getMinColumn()
                    + " maxCol=" + placedBb.getMaxColumn()
                    + " minRow=" + placedBb.getMinRow()
                    + " maxRow=" + placedBb.getMaxRow());
            for (int k = 0; k < placedBoundingBoxes.size() - 1; k++) {
                RelocatableTileRectangle other = placedBoundingBoxes.get(k);
                if (other.overlaps(placedBb)) {
                    System.out.println("[InputEB-PLACE]   *** OVERLAP with '" + placedBoundingBoxNames.get(k)
                            + "' bbox: minCol=" + other.getMinColumn()
                            + " maxCol=" + other.getMaxColumn()
                            + " minRow=" + other.getMinRow()
                            + " maxRow=" + other.getMaxRow());
                }
            }

            // Add newly placed sites to occupied set for subsequent iterations
            for (SiteInst modSi : dcuModule.getSiteInsts()) {
                Site newSite = dcuModule.getCorrespondingSite(modSi, bestAnchor);
                if (newSite != null) {
                    occupiedTiles.add(newSite.getTile());
                }
            }

            System.out.println("  ** PLACED InputEB: " + instName + " at " + bestAnchor);

            if (row == nRows - 1) {
                System.out.println("  >> GEMM bottom-row centroid row: " + targetCentroid.getTile().getRow()
                        + "  |  input_eb_y" + row + " placed row: " + bestAnchor.getTile().getRow());
            }
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
     * Places an additional MM2S NOC channel instance at the top-right of a
     * specific SLR. Uses {@link ArrayBuilder#getPlacedArrayBoundingBoxes()}
     * to avoid the GEMM tile / SLR-crossing footprints that flattenDesign()
     * has already removed from the queryable ModuleInst list.
     */
    private static void placeMM2SNOCChannelInSLR(ArrayBuilder ab, Module mm2sModule,
                                                 String instName, SLR slrFilter) {
        Design arrayDesign = ab.getArray();
        RelocatableTileRectangle moduleBoundingBox = mm2sModule.getBoundingBox();

        List<RelocatableTileRectangle> existingBBs = new ArrayList<>(ab.getPlacedArrayBoundingBoxes());
        for (ModuleInst existingMi : arrayDesign.getModuleInsts()) {
            if (existingMi.isPlaced()) {
                Module mod = existingMi.getModule();
                existingBBs.add(mod.getBoundingBox()
                        .getCorresponding(existingMi.getPlacement().getTile(), mod.getAnchor().getTile()));
            }
        }

        Set<Tile> occupiedTiles = new HashSet<>();
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            occupiedTiles.add(si.getTile());
        }

        // Top-right corner of the array within the requested SLR
        // Compare SLRs by id to avoid object-identity mismatches.
        int slrFilterId = slrFilter.getId();
        int targetRow = Integer.MAX_VALUE;
        int targetCol = Integer.MIN_VALUE;
        for (SiteInst si : arrayDesign.getSiteInsts()) {
            if (si.getTile().getSLR().getId() != slrFilterId) continue;
            targetRow = Math.min(targetRow, si.getTile().getRow());
            targetCol = Math.max(targetCol, si.getTile().getColumn());
        }

        ModuleInst mi = arrayDesign.createModuleInst(instName, mm2sModule);
        Site bestAnchor = null;
        int bestDist = Integer.MAX_VALUE;

        for (Site anchor : mm2sModule.getAllValidPlacements()) {
            if (anchor.getTile().getSLR().getId() != slrFilterId) continue;
            RelocatableTileRectangle candidateBB = moduleBoundingBox
                    .getCorresponding(anchor.getTile(), mm2sModule.getAnchor().getTile());

            boolean overlaps = false;
            for (RelocatableTileRectangle existing : existingBBs) {
                if (existing.overlaps(candidateBB)) { overlaps = true; break; }
            }
            if (overlaps) continue;

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
            throw new RuntimeException("Could not find valid placement for " + instName + " in SLR " + slrFilter.getId());
        }
        if (!mi.place(bestAnchor, false, false)) {
            throw new RuntimeException("Failed to place " + instName + " at " + bestAnchor);
        }

        System.out.println("  ** PLACED MM2S NOC: " + instName + " at " + bestAnchor + " (SLR " + slrFilter.getId() + ")");
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
