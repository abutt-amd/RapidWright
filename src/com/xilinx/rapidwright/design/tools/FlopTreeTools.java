/*
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
package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FlopTreeTools {

    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES = new HashSet<>(
            Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM));

    private static class PortInstQuadrants implements Iterable<List<EDIFHierPortInst>> {
        List<EDIFHierPortInst> topLeft;
        List<EDIFHierPortInst> topRight;
        List<EDIFHierPortInst> bottomLeft;
        List<EDIFHierPortInst> bottomRight;

        PortInstQuadrants() {
            topLeft = new ArrayList<>();
            topRight = new ArrayList<>();
            bottomLeft = new ArrayList<>();
            bottomRight = new ArrayList<>();
        }

        @Override
        public @NotNull Iterator<List<EDIFHierPortInst>> iterator() {
            return Arrays.asList(topLeft, topRight, bottomLeft, bottomRight).iterator();
        }
    }

    private static Pair<Cell, Net> createAndPlaceFlopForTree(Design design, EDIFHierNet logicalNet, String newNetName,
                                                             Pair<Site, BEL> loc, EDIFHierNet clk,
                                                             List<EDIFHierPortInst> portInsts) {
        Cell flop = design.createAndPlaceCell(design.getTopEDIFCell(), newNetName, Unisim.FDRE, loc.getFirst(),
                loc.getSecond());
        Net net = design.createNet(newNetName);
        net.connect(flop, "Q");
        design.getGndNet().connect(flop, "R");
        design.getVccNet().connect(flop, "CE");
        EDIFHierCellInst flopHierCellInst = flop.getEDIFHierCellInst();
        EDIFHierPortInst clkHierPortInst = flopHierCellInst.getPortInst("C");
        if (clkHierPortInst == null) {
            clk.getNet().createPortInst("C", flopHierCellInst.getInst());
            clkHierPortInst = flopHierCellInst.getPortInst("C");
        }
        EDIFTools.connectPortInstsThruHier(clk, clkHierPortInst, newNetName + "_clk");
        EDIFNet origNet = logicalNet.getNet();
        int i = 0;
        for (EDIFHierPortInst portInst : portInsts) {
            if (portInst.isInput()) {
                ECOTools.disconnectNet(design, portInst);
            }
            i++;
        }
        Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        netToPortInsts.put(net.getLogicalHierNet(), portInsts);
        ECOTools.connectNet(design, netToPortInsts, null);
        Net origPhysNet = design.getNet(origNet.getName());
        if (origPhysNet == null) {
            if (origNet.isGND()) {
                origPhysNet = design.getGndNet();
            } else if (origNet.isVCC()) {
                origPhysNet = design.getVccNet();
            } else {
                origPhysNet = design.createNet(new EDIFHierNet(design.getNetlist().getTopHierCellInst(), origNet));
            }
        }
        origPhysNet.connect(flop, "D");
        return new Pair<>(flop, net);
    }

    private static Site findCentroidOfPortInsts(Design design, List<EDIFHierPortInst> portInsts) {
        List<Point> points = new ArrayList<>();
        for (EDIFHierPortInst leafInst : portInsts) {
            Cell cell = design.getCell(leafInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                Point p = new Point(t.getColumn(), t.getRow());
                points.add(p);
            }
        }

        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    private static PortInstQuadrants splitPortInstsIntoQuadrants(Design design, List<EDIFHierPortInst> portInsts,
                                                                 Site centroid) {
        PortInstQuadrants quadrants = new PortInstQuadrants();

        Tile tile = centroid.getTile();
        int tileColumn = tile.getColumn();
        int tileRow = tile.getRow();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = design.getCell(portInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                int portColumn = t.getColumn();
                int portRow = t.getRow();

                if (portColumn <= tileColumn && portRow < tileRow) {
                    quadrants.topLeft.add(portInst);
                } else if (portColumn < tileColumn) {
                    quadrants.bottomLeft.add(portInst);
                } else if (portColumn > tileColumn && portRow <= tileRow) {
                    quadrants.topRight.add(portInst);
                } else if (portRow > tileRow) {
                    quadrants.bottomRight.add(portInst);
                }
            }
        }

        return quadrants;
    }

    private static Map<SLR, List<EDIFHierPortInst>> splitPortInstsBySLR(Design design,
                                                                        List<EDIFHierPortInst> portInsts) {
        Map<SLR, List<EDIFHierPortInst>> portInstMap = new HashMap<>();

        for (EDIFHierPortInst portInst : portInsts) {
            Cell cell = design.getCell(portInst.getFullHierarchicalInstName());
            if (cell != null && cell.isPlaced()) {
                Tile t = cell.getTile();
                SLR slr = t.getSLR();
                portInstMap.computeIfAbsent(slr, k -> new ArrayList<>()).add(portInst);
            }
        }

        return portInstMap;
    }

    private static Pair<Site, BEL> nextAvailFlopPlacement(Design design, Iterator<Site> itr, SLR slr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            if (slr != null && curr.getTile().getSLR() != slr) {
                continue;
            }
            SiteInst candidate = design.getSiteInstFromSite(curr);
            List<BEL> usedFFs = new ArrayList<>();
            if (candidate != null) {
                for (Cell c : candidate.getCells()) {
                    if (c.isPlaced() && c.getBEL().isFF() && !c.getBEL().isAnyIMR()) {
                        usedFFs.add(c.getBEL());
                    }
                }
            }
            if (usedFFs.isEmpty()) {
                // There is an FF available, use one of them
                List<BEL> bels = Arrays.stream(curr.getBELs()).filter((BEL b) -> b.isFF() && !b.isAnyIMR())
                        .collect(Collectors.toList());
                for (BEL b : bels) {
                    return new Pair<>(curr, b);
                }
            }
        }
        return null;
    }

    private static Pair<Site, Net> placeFlopNearCentroidOfPortInsts(Design design, String clkName, Net inputNet,
                                                                    String newNetName, List<EDIFHierPortInst> portInsts,
                                                                    Set<SiteInst> siteInstsToRoute) {
        Site centroid = findCentroidOfPortInsts(design, portInsts);

        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(centroid).iterator();
        Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, null);
        assert loc != null;
        Pair<Cell, Net> flopNetPair = createAndPlaceFlopForTree(design, inputNet.getLogicalHierNet(), newNetName, loc,
                design.getNetlist().getHierNetFromName(clkName), portInsts);
        siteInstsToRoute.add(flopNetPair.getFirst().getSiteInst());
        return new Pair<>(centroid, flopNetPair.getSecond());
    }

    private static void insertFlopTreeForNetInSLR(Design design, SLR slr, String netName, String clkName, int depth,
                                                  List<EDIFHierPortInst> sinkHierPortInsts,
                                                  Set<SiteInst> siteInstsToRoute) {
        Net topNet = design.getNet(netName);
        List<Pair<Net, List<EDIFHierPortInst>>> currPortInstList = new ArrayList<>();
        currPortInstList.add(new Pair<>(topNet, sinkHierPortInsts));

        List<Pair<Net, List<EDIFHierPortInst>>> nextPortInstList = new ArrayList<>();

        for (int currDepth = 0; currDepth < depth; currDepth++) {
            int i = 0;
            for (Pair<Net, List<EDIFHierPortInst>> pair : currPortInstList) {
                Net net = pair.getFirst();
                List<EDIFHierPortInst> portInsts = pair.getSecond();
                String newNetName = netName + "_slr" + slr.getId() + "_d" + currDepth + "_" + i;
                Pair<Site, Net> centroidNetPair = placeFlopNearCentroidOfPortInsts(design, clkName, net, newNetName,
                        portInsts, siteInstsToRoute);

                Site centroid = centroidNetPair.getFirst();
                Net newNet = centroidNetPair.getSecond();
                PortInstQuadrants quadrants = splitPortInstsIntoQuadrants(design, portInsts, centroid);
                for (List<EDIFHierPortInst> quadrant : quadrants) {
                    nextPortInstList.add(new Pair<>(newNet, quadrant));
                }
                i++;
            }
            currPortInstList = nextPortInstList;
            nextPortInstList = new ArrayList<>();
        }
    }

    private static Site getNearestValidSite(Design design, int row, int col) {
        List<Point> points = new ArrayList<>();
        Point p = new Point(col, row);
        points.add(p);

        return ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);
    }

    private static Net insertSLRCrossing(Design design, Net net, String clkName,
                                         List<EDIFHierPortInst> otherSLRPortInsts, Set<SiteInst> siteInstsToRoute) {
        Cell sourceCell = net.getLogicalHierNet().getSourcePortInsts(false).get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();
        int sourceRow = sourceSite.getTile().getRow();
        int sourceCol = sourceSite.getTile().getColumn();

        int newFirstSLRRow = sourceSLR.getLowerRight().getRow();

        Site firstSLRSite = getNearestValidSite(design, newFirstSLRRow, sourceCol);
        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(firstSLRSite).iterator();
        Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, null);

        Pair<Cell, Net> topNetCellPair = createAndPlaceFlopForTree(design, net.getLogicalHierNet(),
                "test_slr_crossing_top", loc,
                design.getNet(clkName).getLogicalHierNet(), otherSLRPortInsts);

        siteInstsToRoute.add(topNetCellPair.getFirst().getSiteInst());

        // Distance approx. 87 rows for SLL length
        int newSecondSLRRow = newFirstSLRRow + 87;
        Site secondSLRSite = getNearestValidSite(design, newSecondSLRRow, sourceCol);
        siteItr = ECOPlacementHelper.spiralOutFrom(secondSLRSite).iterator();
        loc = nextAvailFlopPlacement(design, siteItr, null);

        Pair<Cell, Net> bottomNetCellPair = createAndPlaceFlopForTree(design,
                topNetCellPair.getSecond().getLogicalHierNet(), "test_slr_crossing_top_2", loc,
                design.getNet(clkName).getLogicalHierNet(), otherSLRPortInsts);

        siteInstsToRoute.add(bottomNetCellPair.getFirst().getSiteInst());

        return bottomNetCellPair.getSecond();
    }

    private static Net insertFlopChain(Design design, Net net, String clkName, int depth,
                                       List<EDIFHierPortInst> portInsts, Set<SiteInst> siteInstsToRoute) {
        Cell sourceCell = net.getLogicalHierNet().getSourcePortInsts(false).get(0).getPhysicalCell(design);
        Site sourceSite = sourceCell.getSite();
        Site portInstCentroid = findCentroidOfPortInsts(design, portInsts);

        List<Point> points = new ArrayList<>();
        points.add(new Point(portInstCentroid.getTile().getColumn(), portInstCentroid.getTile().getColumn()));
        points.add(new Point(sourceSite.getTile().getColumn(), sourceSite.getTile().getColumn()));

        Site midPoint = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points, VALID_CENTROID_SITE_TYPES);

        Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(midPoint).iterator();
        Pair<Site, BEL> loc = nextAvailFlopPlacement(design, siteItr, null);

        Pair<Cell, Net> netCellPair = createAndPlaceFlopForTree(design, net.getLogicalHierNet(),
                net.getName() + "_ff0", loc,
                design.getNet(clkName).getLogicalHierNet(), portInsts);

        siteInstsToRoute.add(netCellPair.getFirst().getSiteInst());


        return netCellPair.getSecond();
    }

    public static void insertFlopTreeForNet(Design design, String netName, String clkName, int depth,
                                            int maxDepthPerSLR) {
        Net topNet = design.getNet(netName);
        List<EDIFHierPortInst> sinkHierPortInsts = topNet.getLogicalHierNet().getLeafHierPortInsts(false);
        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        Map<SLR, List<EDIFHierPortInst>> slrPortInstMap = splitPortInstsBySLR(design, sinkHierPortInsts);
        List<EDIFHierPortInst> sourcePortInsts = topNet.getLogicalHierNet().getSourcePortInsts(false);
        if (sourcePortInsts.isEmpty()) {
            throw new RuntimeException("Net " + netName + " does not have a source");
        }
        if (sourcePortInsts.size() > 1) {
            throw new RuntimeException("Net " + netName + " has multiple sources");
        }
        EDIFHierPortInst sourcePortInst = sourcePortInsts.get(0);
        Cell sourceCell = sourcePortInst.getPhysicalCell(design);
        if (sourceCell == null || !sourceCell.isPlaced()) {
            throw new RuntimeException("Source cell of net " + netName + " must be placed to create flop tree");
        }
        Site sourceSite = sourceCell.getSite();
        SLR sourceSLR = sourceSite.getTile().getSLR();

        for (Map.Entry<SLR, List<EDIFHierPortInst>> slrPortInsts : slrPortInstMap.entrySet()) {
            SLR slr = slrPortInsts.getKey();
            List<EDIFHierPortInst> portInsts = slrPortInsts.getValue();
            int slrDistFromSource = Math.abs(slr.getId() - sourceSLR.getId());
            int newDepth = depth - (2 * slrDistFromSource); // Assume 2 registers to cross SLRs for now

            Net slrCrossedNet = topNet;
            if (slrDistFromSource > 0) {
                slrCrossedNet = insertSLRCrossing(design, topNet, clkName, portInsts, siteInstsToRoute);
            }
            if (newDepth > maxDepthPerSLR) {
                slrCrossedNet = insertFlopChain(design, slrCrossedNet, clkName, newDepth - maxDepthPerSLR, portInsts,
                        siteInstsToRoute);
                newDepth = maxDepthPerSLR;
            }
            insertFlopTreeForNetInSLR(design, slr, slrCrossedNet.getName(), clkName, newDepth, portInsts,
                    siteInstsToRoute);
        }

        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("USAGE : <input.dcp> <output.dcp> <netName> <clkName>");
            return;
        }

        Design d = Design.readCheckpoint(args[0]);

        EDIFTools.uniqueifyNetlist(d);
        insertFlopTreeForNet(d, args[2], args[3], 4, 3);


//        PartialRouter.routeDesignWithUserDefinedArguments(d,
//                new String[]{"--fixBoundingBox", "--useUTurnNodes", "--nonTimingDriven",});
        d.writeCheckpoint(args[1]);
    }
}
