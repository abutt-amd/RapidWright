/*
 *
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt
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
package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import static com.xilinx.rapidwright.design.DesignTools.getTrimmablePIPsFromPins;
import static com.xilinx.rapidwright.rwroute.RouterHelper.computeNodeDelay;
import static com.xilinx.rapidwright.rwroute.RouterHelper.getNodesOfNet;
import static com.xilinx.rapidwright.rwroute.RouterHelper.projectInputPinToINTNode;

public class HoldFixer {
    Design design;
    private long wirelength;
    private int numWireNetsToRoute;
    private int numConnectionsToRoute;
    private long usedNodes;
    private Map<IntentCode, Long> nodeTypeUsage ;
    private Map<IntentCode, Long> nodeTypeLength;
    private RouteNodeGraph routingGraph;
    private Map<Connection, Long> connectionWireLengths;
    private int fixedNets;
    private int MIN_WIRE_LENGTH = 100;

    public HoldFixer(Design design, RWRouteConfig config) {
        this.design = design;
        routingGraph = new RouteNodeGraph(design, config);
        wirelength = 0;
        usedNodes = 0;
        fixedNets = 0;
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
        connectionWireLengths = new HashMap<>();
    }

    private void rerouteConnection(Connection connection) {
        System.out.println("Fix: " + connection.getNet() + ", fixed " + fixedNets + " nets so far");
        long wirelength = 0;
        List<Node> avoidNodes = new ArrayList<>();
        while (wirelength < MIN_WIRE_LENGTH) {
            List<SitePinInst> pinsToRoute = new ArrayList<>();
            Set<PIP> pips = getTrimmablePIPsFromPins(connection.getNet(), Collections.singletonList(connection.getSink()));
            for (PIP pip : pips) {
                Node endNode = pip.getEndNode();
                if (endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT
                        && endNode.getIntentCode() != IntentCode.NODE_PINBOUNCE
                        && endNode.getIntentCode() != IntentCode.NODE_SDQNODE) {
                    avoidNodes.add(endNode);
                }
            }
            connection.getNet().unroutePin(connection.getSink());
            pinsToRoute.add(connection.getSink());

            // Create the PartialRouter
            RWRouteConfig config = new RWRouteConfig(new String[]{
                    "--fixBoundingBox",
                    "--expandBoundingBox",
                    // use U-turn nodes and no masking of nodes cross RCLK
                    // Pros: maximum routability
                    // Con: might result in delay optimism and a slight increase in runtime
                    "--useUTurnNodes",
                    "--nonTimingDriven"});
            PartialRouter router = new PartialRouter(design, config, pinsToRoute);

            // Initialize router object
            router.initialize();

            // Prevent router from using nodes that previously created short routes
            RouteNodeGraph routingGraph = router.routingGraph;
            for (Node nodeToBlock : avoidNodes) {
                RouteNode rnode = routingGraph.getOrCreate(nodeToBlock);
                rnode.setType(RouteNodeType.INACCESSIBLE);
            }

            // Routes the design
            router.route();
            if (!connection.getSink().isRouted()) {
                break;
            }

            // Update wirelength
            NetWrapper netWrapper = createNetWrapper(connection.getNet());
            Map<SitePinInst, Pair<Node, Long>> sourceToSinkINTNodeWireLengths =
                    getSourceToSinkINTNodeWireLengths(netWrapper.getNet());
            wirelength = sourceToSinkINTNodeWireLengths.get(connection.getSink()).getSecond();
            System.out.println("Wirelength: " + wirelength);
        }
        fixedNets++;
    }

    /**
     * Computes the wirelength and delay for each net and reports the total wirelength and critical path delay.
     */
    private void computeStatisticsAndReport() {
        computeNetsWirelength();

        System.out.println("\n");
        System.out.println("Total nodes: " + usedNodes);
        System.out.println("Total wirelength: " + wirelength);
        RWRoute.printNodeTypeUsageAndWirelength(true, nodeTypeUsage, nodeTypeLength, design.getSeries());

        PriorityQueue<Pair<Connection, Long>> maxHeap = new PriorityQueue<>((a, b) -> Long.compare(b.getSecond(), a.getSecond()));
        PriorityQueue<Pair<Connection, Long>> minHeap = new PriorityQueue<>(Comparator.comparingLong(Pair::getSecond));

        for (Map.Entry<Connection, Long> entry : connectionWireLengths.entrySet()) {
            if (entry.getValue() != 0) {
                maxHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                if (inDifferentClockRegionColumns(entry.getKey().getSource(), entry.getKey().getSink())) {
                    minHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                }
            }
        }

        System.out.println("Setup Time: ");
        for (int i = 0; i < 10; i++) {
            Pair<Connection, Long> curr = maxHeap.poll();
            if (curr != null) {
                System.out.println(curr.getFirst().getNet() + ", " + curr.getSecond());
            }
        }

        System.out.println();
        System.out.println("Hold Time: ");
        for (int i = 0; i < 10; i++) {
            Pair<Connection, Long> curr = minHeap.poll();
            if (curr != null) {
                System.out.println(curr.getFirst().getNet() + ", " + curr.getFirst().getSource() + ", " + curr.getFirst().getSink() + ", " + curr.getSecond());
            }
        }
    }

    private void fixHoldViolations() {
        computeNetsWirelength();

        PriorityQueue<Pair<Connection, Long>> minHeap = new PriorityQueue<>(Comparator.comparingLong(Pair::getSecond));

        for (Map.Entry<Connection, Long> entry : connectionWireLengths.entrySet()) {
            if (entry.getValue() != 0) {
                if (inDifferentClockRegionColumns(entry.getKey().getSource(), entry.getKey().getSink())) {
                    minHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                }
            }
        }

        while (!minHeap.isEmpty()) {
            Pair<Connection, Long> curr = minHeap.poll();
            if (curr != null) {
                if (curr.getSecond() >= MIN_WIRE_LENGTH) {
                    break;
                }
                rerouteConnection(curr.getFirst());
            }
        }
    }

    private boolean inDifferentClockRegionColumns(SitePinInst sourcePin, SitePinInst sinkPin) {
        Site source = sourcePin.getSite();
        Site sink = sinkPin.getSite();
        return source.getTile().getClockRegion().getColumn() != sink.getTile().getClockRegion().getColumn();
    }

    /**
     * Computes the wirelength for each net.
     */
    private void computeNetsWirelength() {
        for (Net net : design.getNets()) {
            if (net.getType() != NetType.WIRE) continue;
            if (!RouterHelper.isRoutableNetWithSourceSinks(net)) continue;
            if (net.getSource().toString().contains("CLK")) continue;
            if (net.getSource().toString().contains("BUFG")) continue;
            NetWrapper netplus = createNetWrapper(net);
            for (Node node : RouterHelper.getNodesOfNet(net)) {
                if (RouteNodeGraph.isExcludedTile(node)) {
                    continue;
                }
                usedNodes++;
                int wl = RouteNode.getLength(node, routingGraph);
                wirelength += wl;
                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            setAccumulativeWireLengthOfEachNetNode(netplus);
        }
    }

    /**
     * Creates a {@link NetWrapper} Object that consists of a list of {@link Connection} Objects, based on a net.
     * @param net
     * @return
     */
    private NetWrapper createNetWrapper(Net net) {
        NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
        SitePinInst source = net.getSource();
        Node sourceINTNode = null;
        for (SitePinInst sink:net.getSinkPins()) {
            if (RouterHelper.isExternalConnectionToCout(source, sink)) {
                source = net.getAlternateSource();
                if (source == null) {
                    String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
                    throw new IllegalArgumentException(errMsg);
                }
            }
            Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);
            Node sinkINTNode = projectInputPinToINTNode(sink);
            if (sinkINTNode == null) {
                connection.setDirect(true);
            } else {
                if (sourceINTNode == null) {
                    sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
                }
                connection.setSourceRnode(routingGraph.getOrCreate(sourceINTNode, RouteNodeType.EXCLUSIVE_SOURCE));
                connection.setSinkRnode(routingGraph.getOrCreate(sinkINTNode));
                connection.setDirect(false);
            }
        }
        return netWrapper;
    }

    public long computeNodeWirelength(Node node) {
        int wirelength = RouteNode.getLength(node, routingGraph) * 10;
        if (wirelength == 0) {
            wirelength = 3;
        }
        return wirelength;
    }

    /**
     * Gets a map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     * @param net The target routed net.
     * @return The map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     */
    public Map<SitePinInst, Pair<Node,Long>> getSourceToSinkINTNodeWireLengths(Net net) {
        Map<Node, Long> wirelengthMap = new HashMap<>();
        Node sourceNode = net.getSource().getConnectedNode();
        Set<PIP> pips = new HashSet<>(net.getPIPs());
        Queue<Node> queue = new LinkedList<>();
        queue.add(sourceNode);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (PIP pip : node.getAllDownhillPIPs()) {
                if (!pips.contains(pip)) {
                    continue;
                }
                Node startNode = pip.getStartNode();
                long upstreamWirelength = wirelengthMap.getOrDefault(startNode, 0L);

                Node endNode = pip.getEndNode();
                long wirelength = 0;
                if (endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {//device independent?
                    wirelength = computeNodeWirelength(endNode);
                }
                wirelengthMap.put(endNode, upstreamWirelength + wirelength);
                queue.add(endNode);
            }
        }

        Map<SitePinInst, Pair<Node,Long>> sinkNodeWirelength = new HashMap<>();
        for (SitePinInst sink : net.getSinkPins()) {
            Node sinkNode = sink.getConnectedNode();
            if (sinkNode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
                Node sinkINTNode = projectInputPinToINTNode(sink);
                if (sinkINTNode != null) {
                    sinkNode = sinkINTNode;
                } else {
                    // Must be a direct connection (e.g. COUT -> CIN)
                }
            }

            if (wirelengthMap.containsKey(sinkNode)) {
                long routeWirelength = wirelengthMap.get(sinkNode).longValue();
                sinkNodeWirelength.put(sink, new Pair<>(sinkNode,routeWirelength));
            } else {
                System.out.println("WARNING: net " + net.getName() + " not fully routed");
            }
        }

        return sinkNodeWirelength;
    }

    /**
     * Using PIPs to calculate and set accumulative delay for each used node of a routed net that is represented by a {@link NetWrapper} Object.
     * The delay of each node is the total route delay from the source to the node (inclusive).
     * @param netWrapper
     */
    private void setAccumulativeWireLengthOfEachNetNode(NetWrapper netWrapper) {
        if (netWrapper.getNet().getName().contains("u_systolic_array/x[9].y[11].u_tile/x[3].y[1].u_pe/east_outputs[1][0]")) {
            System.out.println();
        }
        Map<SitePinInst, Pair<Node,Long>> sourceToSinkINTNodeWireLengths =
                getSourceToSinkINTNodeWireLengths(netWrapper.getNet());

        for (Connection connection : netWrapper.getConnections()) {
            if (connection.isDirect()) {
                continue;
            }
            Pair<Node,Long> sinkINTNodeWireLengths = sourceToSinkINTNodeWireLengths.get(connection.getSink());
            if (sinkINTNodeWireLengths != null) {
                long connectionWirelength = sinkINTNodeWireLengths.getSecond();
                connectionWireLengths.put(connection, connectionWirelength);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE:\n <input.dcp> <output.dcp>");
        }
        Design design = Design.readCheckpoint(args[0]);

        CodePerfTracker t = new CodePerfTracker("HoldFix");
        t.start("make phys net names consistent");
//        DesignTools.makePhysNetNamesConsistent(design);
        t.stop().start("create missing site pin insts");
        DesignTools.createMissingSitePinInsts(design);
        t.stop().start("calculate wirelength");
        RWRouteConfig config = new RWRouteConfig(new String[0]);
        config.setTimingDriven(false);
        HoldFixer holdFixer = new HoldFixer(design, config);

        holdFixer.computeStatisticsAndReport();
        holdFixer.fixHoldViolations();
        holdFixer.computeStatisticsAndReport();
        t.stop();
        design.writeCheckpoint(args[1]);
    }
}
