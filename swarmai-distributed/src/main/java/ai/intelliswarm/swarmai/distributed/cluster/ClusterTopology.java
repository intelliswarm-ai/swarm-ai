package ai.intelliswarm.swarmai.distributed.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Maintains the current view of the distributed cluster.
 *
 * <p>Tracks which nodes are alive, their workload, and health status.
 * The leader uses topology to make partition assignment decisions.</p>
 */
public class ClusterTopology {

    private static final Logger log = LoggerFactory.getLogger(ClusterTopology.class);

    private final Map<String, ClusterNode> nodes = new ConcurrentHashMap<>();
    private volatile String localNodeId;

    public ClusterTopology(String localNodeId) {
        this.localNodeId = localNodeId;
    }

    public void addNode(ClusterNode node) {
        nodes.put(node.nodeId(), node);
        log.info("Node {} joined cluster (total: {})", node.nodeId(), nodes.size());
    }

    public void removeNode(String nodeId) {
        nodes.remove(nodeId);
        log.info("Node {} removed from cluster (total: {})", nodeId, nodes.size());
    }

    public Optional<ClusterNode> getNode(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public List<ClusterNode> allNodes() {
        return List.copyOf(nodes.values());
    }

    public List<ClusterNode> activeNodes() {
        return nodes.values().stream()
                .filter(ClusterNode::isAlive)
                .collect(Collectors.toList());
    }

    public List<ClusterNode> deadNodes() {
        return nodes.values().stream()
                .filter(n -> n.status() == ClusterNode.Status.DEAD)
                .collect(Collectors.toList());
    }

    /**
     * Returns active nodes sorted by least assigned partitions — for load-balanced assignment.
     */
    public List<ClusterNode> nodesByLoad() {
        return activeNodes().stream()
                .sorted(Comparator.comparingInt(ClusterNode::assignedPartitions))
                .collect(Collectors.toList());
    }

    /**
     * Returns the node with the least load — for adaptive partitioning.
     */
    public Optional<ClusterNode> leastLoadedNode() {
        return activeNodes().stream()
                .min(Comparator.comparingInt(ClusterNode::assignedPartitions));
    }

    public int clusterSize() { return nodes.size(); }
    public int activeNodeCount() { return (int) nodes.values().stream().filter(ClusterNode::isAlive).count(); }
    public String localNodeId() { return localNodeId; }

    /**
     * Detects nodes that haven't sent a heartbeat within the threshold.
     */
    public List<ClusterNode> detectSuspectNodes(long heartbeatTimeoutMs) {
        List<ClusterNode> suspects = new ArrayList<>();
        for (ClusterNode node : nodes.values()) {
            if (node.isAlive() && node.millisSinceHeartbeat() > heartbeatTimeoutMs) {
                node.setStatus(ClusterNode.Status.SUSPECT);
                suspects.add(node);
                log.warn("Node {} is SUSPECT ({}ms since last heartbeat)",
                        node.nodeId(), node.millisSinceHeartbeat());
            }
        }
        return suspects;
    }

    /**
     * Marks suspect nodes as dead after an extended timeout.
     */
    public List<ClusterNode> promoteToDeadNodes(long deadThresholdMs) {
        List<ClusterNode> dead = new ArrayList<>();
        for (ClusterNode node : nodes.values()) {
            if (node.status() == ClusterNode.Status.SUSPECT
                    && node.millisSinceHeartbeat() > deadThresholdMs) {
                node.setStatus(ClusterNode.Status.DEAD);
                dead.add(node);
                log.error("Node {} declared DEAD ({}ms since last heartbeat)",
                        node.nodeId(), node.millisSinceHeartbeat());
            }
        }
        return dead;
    }

    public TopologySnapshot snapshot() {
        return new TopologySnapshot(
                clusterSize(), activeNodeCount(),
                allNodes().stream().map(n ->
                        new TopologySnapshot.NodeSummary(n.nodeId(), n.status().name(),
                                n.assignedPartitions(), n.completedPartitions(),
                                n.millisSinceHeartbeat())
                ).toList()
        );
    }

    public record TopologySnapshot(
            int totalNodes, int activeNodes,
            List<NodeSummary> nodes
    ) {
        public record NodeSummary(String nodeId, String status,
                                  int assignedPartitions, int completedPartitions,
                                  long msSinceHeartbeat) {}
    }
}
