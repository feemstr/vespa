// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.maintenance;

import com.yahoo.config.provision.Deployer;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.jdisc.Metric;
import com.yahoo.vespa.hosted.provision.Node;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.maintenance.MaintenanceDeployment.Move;
import com.yahoo.vespa.hosted.provision.node.Agent;
import com.yahoo.vespa.hosted.provision.provisioning.HostCapacity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * A maintainer which attempts to ensure there is spare capacity available in chunks which can fit
 * all node resource configuration in use, such that the system is able to quickly replace a failed node
 * if necessary.
 *
 * This also emits the following metrics:
 * - Overcommitted hosts: Hosts whose capacity is less than the sum of its children's
 * - Spare host capacity, or how many hosts the repository can stand to lose without ending up in a situation where it's
 *   unable to find a new home for orphaned tenants.
 *
 * @author mgimle
 * @author bratseth
 */
public class SpareCapacityMaintainer extends NodeRepositoryMaintainer {

    private final int maxIterations;
    private final Deployer deployer;
    private final Metric metric;

    public SpareCapacityMaintainer(Deployer deployer,
                                   NodeRepository nodeRepository,
                                   Metric metric,
                                   Duration interval) {
        this(deployer, nodeRepository, metric, interval,
             10_000 // Should take less than a few minutes
        );
    }

    public SpareCapacityMaintainer(Deployer deployer,
                                   NodeRepository nodeRepository,
                                   Metric metric,
                                   Duration interval,
                                   int maxIterations) {
        super(nodeRepository, interval);
        this.deployer = deployer;
        this.metric = metric;
        this.maxIterations = maxIterations;
    }

    @Override
    protected void maintain() {
        if ( ! nodeRepository().zone().getCloud().allowHostSharing()) return;

        CapacityChecker capacityChecker = new CapacityChecker(nodeRepository());

        List<Node> overcommittedHosts = capacityChecker.findOvercommittedHosts();
        if (overcommittedHosts.size() != 0) {
            log.log(Level.WARNING, String.format("%d nodes are overcommitted! [ %s ]",
                                                 overcommittedHosts.size(),
                                                 overcommittedHosts.stream().map(Node::hostname).collect(Collectors.joining(", "))));
        }
        metric.set("overcommittedHosts", overcommittedHosts.size(), null);

        Optional<CapacityChecker.HostFailurePath> failurePath = capacityChecker.worstCaseHostLossLeadingToFailure();
        if (failurePath.isPresent()) {
            int spareHostCapacity = failurePath.get().hostsCausingFailure.size() - 1;
            if (spareHostCapacity == 0) {
                Move move = findMitigatingMove(failurePath.get());
                if (moving(move)) {
                    // We succeeded or are in the process of taking a step to mitigate.
                    // Report with the assumption this will eventually succeed to avoid alerting before we're stuck
                    spareHostCapacity++;
                }
            }
            metric.set("spareHostCapacity", spareHostCapacity, null);
        }
    }

    private boolean moving(Move move) {
        if (move.isEmpty()) return false;
        if (move.node().allocation().get().membership().retired()) return true; // Move already in progress
        return move.execute(false, Agent.SpareCapacityMaintainer, deployer, metric, nodeRepository());
    }

    private Move findMitigatingMove(CapacityChecker.HostFailurePath failurePath) {
        Optional<Node> nodeWhichCantMove = failurePath.failureReason.tenant;
        if (nodeWhichCantMove.isEmpty()) return Move.empty();

        Node node = nodeWhichCantMove.get();
        NodeList allNodes = nodeRepository().list();
        // Allocation will assign the two most empty nodes as "spares", which will not be allocated on
        // unless needed for node failing. Our goal here is to make room on these spares for the given node
        HostCapacity hostCapacity = new HostCapacity(allNodes, nodeRepository().resourcesCalculator());
        Set<Node> spareHosts = hostCapacity.findSpareHosts(allNodes.hosts().satisfies(node.resources()).asList(), 2);
        List<Node> hosts = allNodes.hosts().except(spareHosts).asList();

        CapacitySolver capacitySolver = new CapacitySolver(hostCapacity, maxIterations);
        List<Move> shortestMitigation = null;
        for (Node spareHost : spareHosts) {
            List<Move> mitigation = capacitySolver.makeRoomFor(node, spareHost, hosts, List.of(), List.of());
            if (mitigation == null) continue;
            if (shortestMitigation == null || shortestMitigation.size() > mitigation.size())
                shortestMitigation = mitigation;
        }
        if (shortestMitigation == null || shortestMitigation.isEmpty()) return Move.empty();
        return shortestMitigation.get(0);
    }

    private static class CapacitySolver {

        private final HostCapacity hostCapacity;
        private final int maxIterations;

        private int iterations = 0;

        CapacitySolver(HostCapacity hostCapacity, int maxIterations) {
            this.hostCapacity = hostCapacity;
            this.maxIterations = maxIterations;
        }

        /** The map of subproblem solutions already found. The value is null when there is no solution. */
        private Map<SolutionKey, List<Move>> solutions = new HashMap<>();

        /**
         * Finds the shortest sequence of moves which makes room for the given node on the given host,
         * assuming the given moves already made over the given hosts' current allocation.
         *
         * @param node the node to make room for
         * @param host the target host to make room on
         * @param hosts the hosts onto which we can move nodes
         * @param movesConsidered the moves already being considered to add as part of this scenario
         *                        (after any moves made by this)
         * @param movesMade the moves already made in this scenario
         * @return the list of movesMade with the moves needed for this appended, in the order they should be performed,
         *         or null if no sequence could be found
         */
        List<Move> makeRoomFor(Node node, Node host, List<Node> hosts, List<Move> movesConsidered, List<Move> movesMade) {
            SolutionKey solutionKey = new SolutionKey(node, host, movesConsidered, movesMade);
            List<Move> solution = solutions.get(solutionKey);
            if (solution == null) {
                solution = findRoomFor(node, host, hosts, movesConsidered, movesMade);
                solutions.put(solutionKey, solution);
            }
            return solution;
        }

        private List<Move> findRoomFor(Node node, Node host, List<Node> hosts,
                                       List<Move> movesConsidered, List<Move> movesMade) {
            if (iterations++ > maxIterations)
                return null;

            if ( ! host.resources().satisfies(node.resources())) return null;
            NodeResources freeCapacity = freeCapacityWith(movesMade, host);
            if (freeCapacity.satisfies(node.resources())) return List.of();

            List<Move> shortest = null;
            for (var i = subsets(hostCapacity.allNodes().childrenOf(host), 5); i.hasNext(); ) {
                List<Node> childrenToMove = i.next();
                if ( ! addResourcesOf(childrenToMove, freeCapacity).satisfies(node.resources())) continue;
                List<Move> moves = move(childrenToMove, host, hosts, movesConsidered, movesMade);
                if (moves == null) continue;

                if (shortest == null || moves.size() < shortest.size())
                    shortest = moves;
            }
            if (shortest == null) return null;
            return append(movesMade, shortest);
        }

        private List<Move> move(List<Node> nodes, Node host, List<Node> hosts, List<Move> movesConsidered, List<Move> movesMade) {
            List<Move> moves = new ArrayList<>();
            for (Node childToMove : nodes) {
                List<Move> childMoves = move(childToMove, host, hosts, movesConsidered, append(movesMade, moves));
                if (childMoves == null) return null;
                moves.addAll(childMoves);
            }
            return moves;
        }

        private List<Move> move(Node node, Node host, List<Node> hosts, List<Move> movesConsidered, List<Move> movesMade) {
            if (contains(node, movesConsidered)) return null;
            if (contains(node, movesMade)) return null;
            List<Move> shortest = null;
            for (Node target : hosts) {
                if (target.equals(host)) continue;
                Move move = new Move(node, host, target);
                List<Move> childMoves = makeRoomFor(node, target, hosts, append(movesConsidered, move), movesMade);
                if (childMoves == null) continue;
                if (shortest == null || shortest.size() > childMoves.size() + 1) {
                    shortest = new ArrayList<>(childMoves);
                    shortest.add(move);
                }
            }
            return shortest;
        }

        private boolean contains(Node node, List<Move> moves) {
            return moves.stream().anyMatch(move -> move.node().equals(node));
        }

        private NodeResources addResourcesOf(List<Node> nodes, NodeResources resources) {
            for (Node node : nodes)
                resources = resources.add(node.resources());
            return resources;
        }

        private Iterator<List<Node>> subsets(NodeList nodes, int maxSize) {
            return new SubsetIterator(nodes.asList(), maxSize);
        }

        private List<Move> append(List<Move> a, List<Move> b) {
            List<Move> list = new ArrayList<>();
            list.addAll(a);
            list.addAll(b);
            return list;
        }

        private List<Move> append(List<Move> moves, Move move) {
            List<Move> list = new ArrayList<>(moves);
            list.add(move);
            return list;
        }

        private NodeResources freeCapacityWith(List<Move> moves, Node host) {
            NodeResources resources = hostCapacity.freeCapacityOf(host);
            for (Move move : moves) {
                if ( ! move.toHost().equals(host)) continue;
                resources = resources.subtract(move.node().resources());
            }
            for (Move move : moves) {
                if ( ! move.fromHost().equals(host)) continue;
                resources = resources.add(move.node().resources());
            }
            return resources;
        }

    }

    private static class SolutionKey {

        private final Node node;
        private final Node host;
        private final List<Move> movesConsidered;
        private final List<Move> movesMade;

        private final int hash;

        public SolutionKey(Node node, Node host, List<Move> movesConsidered, List<Move> movesMade) {
            this.node = node;
            this.host = host;
            this.movesConsidered = movesConsidered;
            this.movesMade = movesMade;

            hash = Objects.hash(node, host, movesConsidered, movesMade);
        }

        @Override
        public int hashCode() { return hash; }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null || o.getClass() != this.getClass()) return false;

            SolutionKey other = (SolutionKey)o;
            if ( ! other.node.equals(this.node)) return false;
            if ( ! other.host.equals(this.host)) return false;
            if ( ! other.movesConsidered.equals(this.movesConsidered)) return false;
            if ( ! other.movesMade.equals(this.movesMade)) return false;
            return true;
        }

    }

    private static class SubsetIterator implements Iterator<List<Node>> {

        private final List<Node> nodes;
        private final int maxLength;

        // A number whose binary representation determines which items of list we'll include
        private int i = 0; // first "previous" = 0 -> skip the empty set
        private List<Node> next = null;

        public SubsetIterator(List<Node> nodes, int maxLength) {
            this.nodes = new ArrayList<>(nodes.subList(0, Math.min(nodes.size(), 31)));
            this.maxLength = maxLength;
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;

            // find next
            while (++i < 1<<nodes.size()) {
                int ones = Integer.bitCount(i);
                if (ones > maxLength) continue;

                next = new ArrayList<>(ones);
                for (int position = 0; position < nodes.size(); position++) {
                    if (hasOneAtPosition(position, i))
                        next.add(nodes.get(position));
                }
                return true;
            }
            return false;
        }

        @Override
        public List<Node> next() {
            if ( ! hasNext()) throw new IllegalStateException("No more elements");
            var current = next;
            next = null;
            return current;
        }

        private boolean hasOneAtPosition(int position, int number) {
            return (number & (1 << position)) > 0;
        }

    }

}