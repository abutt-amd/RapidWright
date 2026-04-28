/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.rwroute.HoldFixer;
import com.xilinx.rapidwright.rwroute.PartialRouter;

/**
 * Standalone entry point for routing a RapidSA-built DCP with the same
 * RWRoute partial-route + HoldFixer pass that {@code RapidSA --route} runs
 * locally. Designed to be invoked under bsub so the route can run on a
 * compute host with more memory than the local machine. Used by
 * {@code RapidSA --lsf-route}.
 *
 * Usage:
 *   rapidwright com.xilinx.rapidwright.rapidsa.RapidSARouteJob &lt;input.dcp&gt; &lt;output.dcp&gt; [clkName]
 *
 * If clkName is omitted, defaults to "clk".
 */
public class RapidSARouteJob {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp> <output.dcp> [clkName=clk]");
            System.exit(1);
        }
        String inputDcp = args[0];
        String outputDcp = args[1];
        String clkName = args.length >= 3 ? args[2] : "clk";

        System.out.println("** RapidSARouteJob: reading " + inputDcp);
        Design design = Design.readCheckpoint(inputDcp);

        System.out.println("** RapidSARouteJob: PartialRouter (non-timing-driven, fixBoundingBox, useUTurnNodes)");
        PartialRouter.routeDesignWithUserDefinedArguments(design, new String[]{
                "--fixBoundingBox",
                "--useUTurnNodes",
                "--nonTimingDriven",
        });

        System.out.println("** RapidSARouteJob: HoldFixer on clock '" + clkName + "'");
        HoldFixer holdFixer = new HoldFixer(design, clkName);
        holdFixer.fixHoldViolations();

        System.out.println("** RapidSARouteJob: writing " + outputDcp);
        design.writeCheckpoint(outputDcp);
        System.out.println("** RapidSARouteJob: done");
    }
}
