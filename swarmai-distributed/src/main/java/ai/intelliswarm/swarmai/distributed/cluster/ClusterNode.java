package ai.intelliswarm.swarmai.distributed.cluster;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a node in the distributed agent cluster.
 *
 * <p>Each node runs one or more agents and participates in RAFT consensus.
 * Nodes can be physical machines, containers, or JVM instances.</p>
 */
public class ClusterNode {

    public enum Status { JOINING, ACTIVE, SUSPECT, DRAINING, DEAD }

    private final String nodeId;
    private final String host;
    private final int port;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.JOINING);
    private final AtomicInteger assignedPartitions = new AtomicInteger(0);
    private final AtomicInteger completedPartitions = new AtomicInteger(0);
    private final Map<String, Object> capabilities;
    private volatile Instant lastHeartbeat;
    private volatile Instant joinedAt;

    public ClusterNode(String nodeId, String host, int port, Map<String, Object> capabilities) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.capabilities = capabilities != null ? Map.copyOf(capabilities) : Map.of();
        this.lastHeartbeat = Instant.now();
        this.joinedAt = Instant.now();
    }

    public ClusterNode(String nodeId, String host, int port) {
        this(nodeId, host, port, Map.of());
    }

    public String nodeId() { return nodeId; }
    public String host() { return host; }
    public int port() { return port; }
    public Status status() { return status.get(); }
    public int assignedPartitions() { return assignedPartitions.get(); }
    public int completedPartitions() { return completedPartitions.get(); }
    public Map<String, Object> capabilities() { return capabilities; }
    public Instant lastHeartbeat() { return lastHeartbeat; }
    public Instant joinedAt() { return joinedAt; }

    public String address() { return host + ":" + port; }

    public void setStatus(Status newStatus) { status.set(newStatus); }
    public void recordHeartbeat() { lastHeartbeat = Instant.now(); }
    public void assignPartition() { assignedPartitions.incrementAndGet(); }
    public void completePartition() { completedPartitions.incrementAndGet(); }

    public boolean isAlive() {
        Status s = status.get();
        return s == Status.ACTIVE || s == Status.JOINING;
    }

    public long millisSinceHeartbeat() {
        return Instant.now().toEpochMilli() - lastHeartbeat.toEpochMilli();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClusterNode other && nodeId.equals(other.nodeId);
    }

    @Override
    public int hashCode() { return nodeId.hashCode(); }

    @Override
    public String toString() {
        return "ClusterNode{id=%s, address=%s, status=%s, partitions=%d/%d}"
                .formatted(nodeId, address(), status.get(), completedPartitions.get(), assignedPartitions.get());
    }
}
