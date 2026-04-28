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
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.DrainTile;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.util.ArrayBuilderSLRCrossingCreator;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.PerformanceExplorer;
import com.xilinx.rapidwright.util.PlacerDirective;
import com.xilinx.rapidwright.util.RouterDirective;
import com.xilinx.rapidwright.util.VivadoTools;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RapidSAPrecompile {

    private static final String SYNTH_TCL_NAME = "synth.tcl";
    private static final String SYNTH_DCP_NAME = "synth.dcp";
    private static final String SYNTH_EDF_NAME = "synth.edf";
    private static final String PNR_DCP_NAME = "pnr.dcp";
    public static final String SLR_CROSSING_SYNTH_DCP_NAME = "slr_crossing_synth.dcp";
    public static final String SLR_CROSSING_DCP_NAME = "slr_crossing.dcp";
    private static final String PE_BEST_DCP = "pblock0_best.dcp";
    private static final String XDC_NAME = "constraints.xdc";
    private static final String PE_RUN_DIR = "PerformanceExplorer";
    public static final String SLR_CROSSING_RUN_DIR = "SLRCrossing";
    public static final String SLR_CROSSING_TOP_INST_NAME = "slr_crossing_top";
    public static final String SLR_CROSSING_BOTTOM_INST_NAME = "slr_crossing_bottom";
    private static final double DEFAULT_MIN_CLK_UNCERT = -0.100;
    private static final double DEFAULT_MAX_CLK_UNCERT = 0.250;
    private static final double DEFAULT_STEP_CLK_UNCERT = 0.025;
    private static final List<PlacerDirective> DEFAULT_PLACE_DIRECTIVES = Arrays.asList(PlacerDirective.Default, PlacerDirective.Explore);
    private static final List<RouterDirective> DEFAULT_ROUTE_DIRECTIVES = Arrays.asList(RouterDirective.Default, RouterDirective.Explore);
    private static final String DEFAULT_VIVADO = "vivado";

    private static final List<RapidComponent> COMPONENTS = Collections.unmodifiableList(
            Arrays.asList(
//                    new GEMMTile(4, 4)
//                    new EdgeBufferTile(4, EdgeBufferTile.Type.WEIGHT),
//                    new EdgeBufferTile(4, EdgeBufferTile.Type.INPUT)
                    new DrainTile(4, 16)
//                    new MM2SNOCChannel()
//                    new S2MMNOCChannel()
            )
    );

    private static List<String> getSynthTclScript(Part part, String compOutputDir,
                                                  RapidComponent component) {
        List<String> lines = new ArrayList<>();
        lines.add("set part \"" + part.toString() + "\"");
        lines.add("set output_dir \"" + compOutputDir + "\"");
        lines.add("file mkdir $output_dir");
        lines.add("create_project -in_memory -part $part rapid_synth_proj");

        lines.addAll(component.getDesignTclLines());

        lines.add("read_xdc " + compOutputDir + File.separator + XDC_NAME);
        lines.add("update_compile_order -fileset sources_1");
        lines.add("synth_design -mode out_of_context -part $part");
        lines.add("write_checkpoint -force $output_dir/" + SYNTH_DCP_NAME);
        lines.add("write_edif -force $output_dir/" + SYNTH_EDF_NAME);

        return lines;
    }

    private static List<String> getXDCConstraints(RapidComponent component, double clkPeriod) {
        List<String> lines = new ArrayList<>();
        lines.add("create_clock -period " + clkPeriod + " -name clk [get_ports " + component.getClkName() + "]");
        lines.add("set_clock_uncertainty -setup 0.3 [get_clocks clk]");
        lines.add("set_clock_uncertainty -hold 0.1 [get_clocks clk]");
        return lines;
    }

    private static void compileSLRCrossingArtifacts(String compOutputDir, Part part, RapidComponent component,
                                                    double clkPeriod) {
        if (!component.shouldCompileSLRCrossing()) {
            return;
        }

        String synthDcpName = compOutputDir + File.separator + SYNTH_DCP_NAME;
        String pnrDcpName = compOutputDir + File.separator + PNR_DCP_NAME;
        if (!Files.exists(Paths.get(synthDcpName))) {
            throw new RuntimeException("Missing synth DCP for SLR crossing precompile: " + synthDcpName);
        }
        if (!Files.exists(Paths.get(pnrDcpName))) {
            throw new RuntimeException("Missing PNR DCP for SLR crossing precompile: " + pnrDcpName);
        }

        Design synthDesign = Design.readCheckpoint(synthDcpName);
        Map<EDIFPort, PBlockSide> sideMap = component.getSideMap(synthDesign);
        String slrCrossingDir = compOutputDir + File.separator + SLR_CROSSING_RUN_DIR;
        FileTools.makeDir(slrCrossingDir);

        Design crossingSynthDesign = createSLRCrossingWrapperDesign(part, component, synthDesign, true);
        String crossingSynthPath = slrCrossingDir + File.separator + SLR_CROSSING_SYNTH_DCP_NAME;
        crossingSynthDesign.writeCheckpoint(crossingSynthPath);

        Design crossingTopDesign = createSLRCrossingWrapperDesign(part, component,
                Design.readCheckpoint(pnrDcpName), false);
        String crossingPath = slrCrossingDir + File.separator + SLR_CROSSING_DCP_NAME;
        Design d = Design.readCheckpoint(pnrDcpName);
        ArrayBuilderSLRCrossingCreator.createSLRCrossing(
                Design.readCheckpoint(pnrDcpName),
                crossingTopDesign,
                sideMap,
                SLR_CROSSING_TOP_INST_NAME,
                SLR_CROSSING_BOTTOM_INST_NAME,
                crossingPath,
                component.getSLRCrossingPBlock(),
                clkPeriod);
    }

    private static EDIFDirection getPortDirection(EDIFPort port) {
        if (port.isInput()) {
            return EDIFDirection.INPUT;
        }
        if (port.isOutput()) {
            return EDIFDirection.OUTPUT;
        }
        return EDIFDirection.INOUT;
    }

    private static EDIFCellInst createBlackBox(EDIFCell parent, String name, EDIFCell cellType) {
        EDIFCellInst inst = parent.createChildCellInst(name, cellType);
        inst.addProperty(EDIFCellInst.BLACK_BOX_PROP_VERSAL, "1");
        return inst;
    }

    private static EDIFPort createPortLike(EDIFCell cell, String name, EDIFPort templatePort) {
        int width = templatePort.getWidth();
        if (width > 1) {
            return cell.createPort(name + "[" + (width - 1) + ":0]", getPortDirection(templatePort), width);
        }
        return cell.createPort(name, getPortDirection(templatePort), 1);
    }

    private static void connectPortToInstance(EDIFCell topCell, EDIFPort topPort, EDIFCellInst inst, EDIFPort instPort) {
        if (instPort.getWidth() == 1) {
            EDIFNet net = topCell.getNet(topPort.getBusName());
            if (net == null) {
                net = topCell.createNet(topPort.getBusName());
                net.createPortInst(topPort);
            }
            net.createPortInst(instPort.getPortInstNameFromPort(0), inst);
            return;
        }

        int[] indices = topPort.getBitBlastedIndices();
        for (int i = 0; i < instPort.getWidth(); i++) {
            String netName = topPort.getBusName() + "_" + i;
            EDIFNet net = topCell.getNet(netName);
            if (net == null) {
                net = topCell.createNet(netName);
                net.createPortInst(topPort, indices[i]);
            }
            net.createPortInst(instPort.getPortInstNameFromPort(i), inst);
        }
    }

    private static void connectPortsBetweenInstances(EDIFCell topCell, EDIFCellInst topInst, EDIFPort topPort,
                                                     EDIFCellInst bottomInst, EDIFPort bottomPort) {
        if (topPort.getWidth() != bottomPort.getWidth()) {
            throw new RuntimeException("Mismatched port widths for SLR crossing connection "
                    + topPort.getBusName() + " -> " + bottomPort.getBusName());
        }

        if (topPort.getWidth() == 1) {
            EDIFNet net = topCell.createNet("slr_internal_" + topPort.getBusName() + "_to_" + bottomPort.getBusName());
            net.createPortInst(topPort.getPortInstNameFromPort(0), topInst);
            net.createPortInst(bottomPort.getPortInstNameFromPort(0), bottomInst);
            return;
        }

        for (int i = 0; i < topPort.getWidth(); i++) {
            EDIFNet net = topCell.createNet("slr_internal_" + topPort.getBusName() + "_to_" + bottomPort.getBusName() + "_" + i);
            net.createPortInst(topPort.getPortInstNameFromPort(i), topInst);
            net.createPortInst(bottomPort.getPortInstNameFromPort(i), bottomInst);
        }
    }

    private static Design createSLRCrossingWrapperDesign(Part part, RapidComponent component, Design synthDesign,
                                                         boolean createBlackBoxInstances) {
        EDIFTools.removeVivadoBusPreventionAnnotations(synthDesign.getNetlist());
        EDIFCell synthCell = synthDesign.getTopEDIFCell();
        List<RapidComponent.SLRCrossingConnection> crossingConnections = component.getSLRCrossingConnections(synthDesign);
        String clkName = component.getClkName();
        String resetName = component.getResetName();

        Design wrapperDesign = new Design(synthCell.getName() + "_slr_crossing", part.getName());
        wrapperDesign.setAutoIOBuffers(false);
        EDIFNetlist wrapperNetlist = wrapperDesign.getNetlist();
        wrapperNetlist.migrateCellAndSubCells(synthCell, true);

        EDIFCell migratedCell = wrapperNetlist.getLibrary(synthCell.getLibrary().getName()).getCell(synthCell.getName());
        if (createBlackBoxInstances) {
            migratedCell.makePrimitive();
        }

        EDIFCell topCell = wrapperNetlist.getTopCell();
        EDIFCellInst topInst = createBlackBoxInstances
                ? createBlackBox(topCell, SLR_CROSSING_TOP_INST_NAME, migratedCell)
                : topCell.createChildCellInst(SLR_CROSSING_TOP_INST_NAME, migratedCell);
        EDIFCellInst bottomInst = createBlackBoxInstances
                ? createBlackBox(topCell, SLR_CROSSING_BOTTOM_INST_NAME, migratedCell)
                : topCell.createChildCellInst(SLR_CROSSING_BOTTOM_INST_NAME, migratedCell);

        Map<String, EDIFPort> migratedPorts = new HashMap<>();
        for (EDIFPort port : migratedCell.getPorts()) {
            migratedPorts.put(port.getBusName(), port);
        }

        Set<String> internallyConnectedTopPorts = new HashSet<>();
        Set<String> internallyConnectedBottomPorts = new HashSet<>();
        for (RapidComponent.SLRCrossingConnection connection : crossingConnections) {
            EDIFPort topPort = migratedPorts.get(connection.getTopPortBusName());
            if (topPort == null) {
                throw new RuntimeException("Top port '" + connection.getTopPortBusName()
                        + "' not found on " + migratedCell.getName());
            }
            EDIFPort bottomPort = migratedPorts.get(connection.getBottomPortBusName());
            if (bottomPort == null) {
                throw new RuntimeException("Bottom port '" + connection.getBottomPortBusName()
                        + "' not found on " + migratedCell.getName());
            }
            if (!topPort.isOutput()) {
                throw new RuntimeException("Top port '" + topPort.getBusName()
                        + "' must be an output for SLR crossing connection");
            }
            if (!bottomPort.isInput()) {
                throw new RuntimeException("Bottom port '" + bottomPort.getBusName()
                        + "' must be an input for SLR crossing connection");
            }

            connectPortsBetweenInstances(topCell, topInst, topPort, bottomInst, bottomPort);
            internallyConnectedTopPorts.add(topPort.getBusName());
            internallyConnectedBottomPorts.add(bottomPort.getBusName());
        }

        for (EDIFPort port : migratedCell.getPorts()) {
            boolean isSharedInfrastructurePort = port.getBusName().equals(clkName) ||
                    (resetName != null && port.getBusName().equals(resetName));
            if (isSharedInfrastructurePort) {
                EDIFPort sharedPort = topCell.getPort(port.getBusName());
                if (sharedPort == null) {
                    sharedPort = createPortLike(topCell, port.getBusName(), port);
                }
                connectPortToInstance(topCell, sharedPort, topInst, port);
                connectPortToInstance(topCell, sharedPort, bottomInst, port);
                continue;
            }

            if (!internallyConnectedTopPorts.contains(port.getBusName())) {
                EDIFPort topPort = createPortLike(topCell, SLR_CROSSING_TOP_INST_NAME + "_" + port.getBusName(), port);
                connectPortToInstance(topCell, topPort, topInst, port);
            }

            if (!internallyConnectedBottomPorts.contains(port.getBusName())) {
                EDIFPort bottomPort = createPortLike(topCell, SLR_CROSSING_BOTTOM_INST_NAME + "_" + port.getBusName(), port);
                connectPortToInstance(topCell, bottomPort, bottomInst, port);
            }
        }

        wrapperDesign.setDesignOutOfContext(true);
        return wrapperDesign;
    }

    public static void precompileRapidSAComponents(String outputDirectory, Part part, double clkPeriod) {
        precompileRapidSAComponents(outputDirectory, part, clkPeriod, false);
    }

    public static void precompileRapidSAComponents(String outputDirectory, Part part, double clkPeriod,
                                                   boolean slrCrossingsOnly) {
        FileTools.makeDirs(outputDirectory);
        outputDirectory = new File(outputDirectory).getAbsolutePath();
        for (RapidComponent component : COMPONENTS) {

            String compOutputDir = outputDirectory + File.separator + component.getComponentName();
            FileTools.makeDir(compOutputDir);
            if (slrCrossingsOnly) {
                compileSLRCrossingArtifacts(compOutputDir, part, component, clkPeriod);
                continue;
            }

            List<String> synthScript = getSynthTclScript(part, compOutputDir, component);
            String scriptName = compOutputDir + File.separator + SYNTH_TCL_NAME;
            FileTools.writeLinesToTextFile(synthScript, scriptName);
            List<String> xdcFile = getXDCConstraints(component, clkPeriod);
            String xdcName = compOutputDir + File.separator + XDC_NAME;
            FileTools.writeLinesToTextFile(xdcFile, xdcName);

            Path outputLog = Paths.get(compOutputDir, "synth.log");
            System.out.println("Running Vivado");
            VivadoTools.runTcl(outputLog, Paths.get(scriptName), true);
            System.out.println("Vivado finished");

            String synthDcpName = compOutputDir + File.separator + SYNTH_DCP_NAME;
            Design d = Design.readCheckpoint(synthDcpName);
            Map<EDIFPort, PBlockSide> sideMap = component.getSideMap(d);
            EDIFTools.ensurePreservedInterfaceVivado(d.getNetlist());

            String peRunDir = compOutputDir + File.separator + PE_RUN_DIR;
            FileTools.deleteFolderContents(peRunDir);
            PerformanceExplorer pe = new PerformanceExplorer(d, peRunDir, component.getClkName(), clkPeriod);

            pe.setMinClockUncertainty(DEFAULT_MIN_CLK_UNCERT);
            pe.setMaxClockUncertainty(DEFAULT_MAX_CLK_UNCERT);
            pe.setClockUncertaintyStep(DEFAULT_STEP_CLK_UNCERT);
            pe.updateClockUncertaintyValues();

            pe.setPlacerDirectives(DEFAULT_PLACE_DIRECTIVES);
            pe.setRouterDirectives(DEFAULT_ROUTE_DIRECTIVES);
            pe.setVivadoPath(DEFAULT_VIVADO);
            pe.setContainRouting(true);
            pe.setBaseClockUncertainty(0.3);
            pe.setAddEDIFAndMetadata(true);
            pe.setGetBestPerPBlock(true);
            pe.setReusePreviousResults(false);
            pe.setEnsureExternalRoutability(true);
            pe.setLockPlacement(true);

            if (sideMap != null) {
                pe.setExternalRoutabilitySideMap(sideMap);
            }

            Map<PBlock,String> pblocks = new LinkedHashMap<>();
            pblocks.put(component.getPBlock(), null);
            pe.setPBlocks(pblocks);

            boolean success = pe.explorePerformance();

//            if (!success) {
//                throw new RuntimeException("PerformanceExplorer failed in RapidSA");
//            }

            pe.getBestDesignPerPBlock();
            try {
                Files.copy(Paths.get(peRunDir + File.separator + PE_BEST_DCP),
                        Paths.get(compOutputDir + File.separator + PNR_DCP_NAME),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }

            compileSLRCrossingArtifacts(compOutputDir, part, component, clkPeriod);
        }
    }
}
