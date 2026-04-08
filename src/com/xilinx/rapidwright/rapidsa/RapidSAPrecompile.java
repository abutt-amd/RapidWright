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
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rapidsa.components.MM2SNOCChannel;
import com.xilinx.rapidwright.rapidsa.components.RapidComponent;
import com.xilinx.rapidwright.rapidsa.components.S2MMNOCChannel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RapidSAPrecompile {

    private static final String SYNTH_TCL_NAME = "synth.tcl";
    private static final String SYNTH_DCP_NAME = "synth.dcp";
    private static final String SYNTH_EDF_NAME = "synth.edf";
    private static final String PNR_DCP_NAME = "pnr.dcp";
    private static final String PE_BEST_DCP = "pblock0_best.dcp";
    private static final String XDC_NAME = "constraints.xdc";
    private static final String PE_RUN_DIR = "PerformanceExplorer";
    private static final double DEFAULT_MIN_CLK_UNCERT = -0.100;
    private static final double DEFAULT_MAX_CLK_UNCERT = 0.250;
    private static final double DEFAULT_STEP_CLK_UNCERT = 0.025;
    private static final List<PlacerDirective> DEFAULT_PLACE_DIRECTIVES = Arrays.asList(PlacerDirective.Default, PlacerDirective.Explore);
    private static final List<RouterDirective> DEFAULT_ROUTE_DIRECTIVES = Arrays.asList(RouterDirective.Default, RouterDirective.Explore);
    private static final String DEFAULT_VIVADO = "vivado";

    private static final List<RapidComponent> COMPONENTS = Collections.unmodifiableList(
            Arrays.asList(
//                    new GEMMTile(4, 4),
//                    new WeightDCUTile(4),
//                    new InputDCUTile(4)
//                    new DrainTile(4, 16),
//                    new MM2SChannel(0, "/group/zircon2/abutt/integrated-sa/count_512_clean.mem")
                    new MM2SNOCChannel(),
                    new S2MMNOCChannel()
//                    new SAControlFSM()
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
        lines.add("synth_design -mode out_of_context -flatten_hierarchy none -part $part");
        lines.add("write_checkpoint -force $output_dir/" + SYNTH_DCP_NAME);
        lines.add("write_edif -force $output_dir/" + SYNTH_EDF_NAME);

        return lines;
    }

    private static List<String> getXDCConstraints(RapidComponent component, double clkPeriod) {
        List<String> lines = new ArrayList<>();
        lines.add("create_clock -period " + clkPeriod + " -name clk [get_ports " + component.getClkName() + "]");
        return lines;
    }

    public static void precompileRapidSAComponents(String outputDirectory, Part part, double clkPeriod) {
        FileTools.makeDirs(outputDirectory);
        outputDirectory = new File(outputDirectory).getAbsolutePath();
        for (RapidComponent component : COMPONENTS) {

            String compOutputDir = outputDirectory + File.separator + component.getComponentName();
            FileTools.makeDir(compOutputDir);
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
        }
    }
}
