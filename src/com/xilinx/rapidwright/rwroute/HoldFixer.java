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
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.util.Pair;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static com.xilinx.rapidwright.rwroute.RouterHelper.computeNodeDelay;
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

    public HoldFixer(Design design, RWRouteConfig config) {
        this.design = design;
        routingGraph = new RouteNodeGraph(design, config);
        wirelength = 0;
        usedNodes = 0;
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
        connectionWireLengths = new HashMap<>();
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

        PriorityQueue<Pair<Connection, Long>> pq = new PriorityQueue<>(Comparator.comparingLong(Pair::getSecond));
        for (int i = 0; i < 10; i++) {
            Pair<Connection, Long> curr = pq.poll();
            System.out.println(curr.getFirst().getNet());
        }
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
            setAccumulativeDelayOfEachNetNode(netplus);
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

    public int computeNodeWirelength(Node node) {
        if (RouteNode.isExitNode(node)) {
            return RouteNode.getLength(node, routingGraph);
        }
        return 0;
    }

    /**
     * Gets a map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     * @param net The target routed net.
     * @return The map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     */
    public Map<SitePinInst, Pair<Node,Long>> getSourceToSinkINTNodeWireLengths(Net net) {
        List<PIP> pips = net.getPIPs();
        Map<Node, Long> wirelengthMap = new HashMap<>();
        for (PIP pip : pips) {
            Node startNode = pip.getStartNode();
            long upstreamWirelength = wirelengthMap.getOrDefault(startNode, 0L);

            Node endNode = pip.getEndNode();
            long wirelength = 0;
            if (endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {//device independent?
                wirelength = computeNodeWirelength(endNode);
            }
            wirelengthMap.put(endNode, upstreamWirelength + wirelength);
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

            long routeWirelength = wirelengthMap.get(sinkNode).longValue();
            sinkNodeWirelength.put(sink, new Pair<>(sinkNode,routeWirelength));
        }

        return sinkNodeWirelength;
    }

    /**
     * Using PIPs to calculate and set accumulative delay for each used node of a routed net that is represented by a {@link NetWrapper} Object.
     * The delay of each node is the total route delay from the source to the node (inclusive).
     * @param netWrapper
     */
    private void setAccumulativeDelayOfEachNetNode(NetWrapper netWrapper) {
        Map<SitePinInst, Pair<Node,Long>> sourceToSinkINTNodeWireLengths =
                getSourceToSinkINTNodeWireLengths(netWrapper.getNet());

        for (Connection connection : netWrapper.getConnections()) {
            if (connection.isDirect()) {
                continue;
            }
            Pair<Node,Long> sinkINTNodeWireLengths = sourceToSinkINTNodeWireLengths.get(connection.getSink());
            long connectionWirelength = sinkINTNodeWireLengths.getSecond();
            connectionWireLengths.put(connection, connectionWirelength);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("USAGE:\n <input.dcp>");
        }
        Design design = Design.readCheckpoint(args[0]);

        CodePerfTracker t = new CodePerfTracker("HoldFix");
        t.start("preprocess");
        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        t.stop().start("calculate wirelength");
        RWRouteConfig config = new RWRouteConfig(new String[0]);
        config.setTimingDriven(false);
        HoldFixer holdFixer = new HoldFixer(design, config);
        holdFixer.computeStatisticsAndReport();
        t.stop();
    }
}
