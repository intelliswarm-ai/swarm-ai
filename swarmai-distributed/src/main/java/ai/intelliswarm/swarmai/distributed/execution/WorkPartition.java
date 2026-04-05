package ai.intelliswarm.swarmai.distributed.execution;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A unit of distributed work — one slice of the overall goal.
 *
 * <p>The leader partitions the goal into WorkPartitions and assigns each to a node.
 * Each partition is independently executable, with its own inputs and expected outputs.
 * On node failure, unfinished partitions are reassigned to surviving nodes.</p>
 */
public class WorkPartition {

    public enum Status {
        UNASSIGNED,     // Created but not yet assigned to a node
        ASSIGNED,       // Assigned to a node, awaiting execution
        IN_PROGRESS,    // Node is actively executing
        COMPLETED,      // Successfully completed
        FAILED,         // Execution failed
        REASSIGNED      // Original node failed, reassigned to another
    }

    private final String partitionId;
    private final int index;
    private final Map<String, Object> inputs;
    private final List<String> taskIds;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNASSIGNED);

    private volatile String assignedNodeId;
    private volatile String previousNodeId;
    private volatile Instant assignedAt;
    private volatile Instant completedAt;
    private volatile Map<String, Object> result;
    private volatile String failureReason;
    private volatile int attemptCount = 0;

    public WorkPartition(String partitionId, int index, Map<String, Object> inputs, List<String> taskIds) {
        this.partitionId = Objects.requireNonNull(partitionId);
        this.index = index;
        this.inputs = inputs != null ? new LinkedHashMap<>(inputs) : new LinkedHashMap<>();
        this.taskIds = taskIds != null ? List.copyOf(taskIds) : List.of();
    }

    public String partitionId() { return partitionId; }
    public int index() { return index; }
    public Map<String, Object> inputs() { return Map.copyOf(inputs); }
    public List<String> taskIds() { return taskIds; }
    public Status status() { return status.get(); }
    public String assignedNodeId() { return assignedNodeId; }
    public String previousNodeId() { return previousNodeId; }
    public Instant assignedAt() { return assignedAt; }
    public Instant completedAt() { return completedAt; }
    public Map<String, Object> result() { return result; }
    public String failureReason() { return failureReason; }
    public int attemptCount() { return attemptCount; }

    public void assignTo(String nodeId) {
        if (assignedNodeId != null) {
            previousNodeId = assignedNodeId;
            status.set(Status.REASSIGNED);
        } else {
            status.set(Status.ASSIGNED);
        }
        assignedNodeId = nodeId;
        assignedAt = Instant.now();
        attemptCount++;
    }

    public void markInProgress() {
        status.set(Status.IN_PROGRESS);
    }

    public void complete(Map<String, Object> result) {
        this.result = result != null ? Map.copyOf(result) : Map.of();
        this.completedAt = Instant.now();
        status.set(Status.COMPLETED);
    }

    public void fail(String reason) {
        this.failureReason = reason;
        status.set(Status.FAILED);
    }

    public boolean isTerminal() {
        Status s = status.get();
        return s == Status.COMPLETED || s == Status.FAILED;
    }

    public boolean isReassignable() {
        return attemptCount < 3; // max 3 attempts across different nodes
    }

    public long executionTimeMs() {
        if (assignedAt == null) return 0;
        Instant end = completedAt != null ? completedAt : Instant.now();
        return end.toEpochMilli() - assignedAt.toEpochMilli();
    }

    @Override
    public String toString() {
        return "WorkPartition{id=%s, index=%d, status=%s, node=%s, attempts=%d}"
                .formatted(partitionId, index, status.get(), assignedNodeId, attemptCount);
    }
}
