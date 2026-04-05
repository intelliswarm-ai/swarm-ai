package ai.intelliswarm.swarmai.distributed.execution;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.consensus.RaftLog;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reconciliation loop for distributed goal execution.
 *
 * <p>Continuously compares desired state (the SwarmGoal) against actual state (GoalStatus)
 * and takes corrective actions to drive convergence. Runs on the RAFT leader only.</p>
 *
 * <h3>Reconciliation Cycle</h3>
 * <ol>
 *   <li>Partition the goal into work units based on the partition strategy</li>
 *   <li>Assign partitions to nodes based on load balancing</li>
 *   <li>Monitor partition execution via heartbeats</li>
 *   <li>Detect and handle node failures — reassign orphaned partitions</li>
 *   <li>Evaluate success criteria against completed results</li>
 *   <li>Repeat until all criteria are met or deadline expires</li>
 * </ol>
 */
public class GoalReconciler {

    private static final Logger log = LoggerFactory.getLogger(GoalReconciler.class);

    private final RaftNode raftNode;
    private final ClusterTopology topology;
    private final Map<String, WorkPartition> partitions = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> collectedResults = new ConcurrentHashMap<>();

    private volatile GoalStatus goalStatus;

    public GoalReconciler(RaftNode raftNode, ClusterTopology topology) {
        this.raftNode = raftNode;
        this.topology = topology;

        // listen for committed log entries
        raftNode.onCommit(this::onLogEntryCommitted);
    }

    /**
     * Initialize a goal and begin reconciliation.
     */
    public GoalStatus initializeGoal(SwarmGoal goal, List<Map<String, Object>> workItems) {
        this.goalStatus = new GoalStatus(goal);
        goalStatus.setLeaderId(raftNode.nodeId());
        goalStatus.transitionTo(GoalStatus.Phase.PARTITIONING);

        // partition work items
        List<WorkPartition> partitionList = partition(goal, workItems);
        partitionList.forEach(p -> partitions.put(p.partitionId(), p));
        goalStatus.setTotalPartitions(partitionList.size());

        log.info("Goal '{}' initialized with {} partitions across {} active nodes",
                goal.name(), partitionList.size(), topology.activeNodeCount());

        // assign partitions to nodes
        assignPartitions(partitionList);
        goalStatus.transitionTo(GoalStatus.Phase.EXECUTING);

        return goalStatus;
    }

    /**
     * Run one reconciliation cycle — compare desired vs. actual state and take corrective action.
     * Returns true if the goal has reached a terminal state.
     */
    public boolean reconcile() {
        if (goalStatus == null || goalStatus.isTerminal()) return true;
        if (!raftNode.isLeader()) return false;

        // 1. Detect failed nodes and reassign their work
        handleNodeFailures();

        // 2. Check for deadline expiry
        if (goalStatus.goal().isExpired()) {
            goalStatus.addEvent("Deadline expired");
            if (goalStatus.completedPartitions() > 0) {
                goalStatus.transitionTo(GoalStatus.Phase.DEGRADED);
            } else {
                goalStatus.transitionTo(GoalStatus.Phase.FAILED);
            }
            return true;
        }

        // 3. Check if all partitions are done
        long completed = partitions.values().stream()
                .filter(p -> p.status() == WorkPartition.Status.COMPLETED).count();
        long failed = partitions.values().stream()
                .filter(p -> p.status() == WorkPartition.Status.FAILED && !p.isReassignable()).count();
        long total = partitions.size();

        if (completed + failed == total) {
            // all partitions terminal — evaluate success criteria
            goalStatus.transitionTo(GoalStatus.Phase.RECONCILING);
            evaluateSuccessCriteria();

            if (goalStatus.allCriteriaMet()) {
                goalStatus.transitionTo(GoalStatus.Phase.SUCCEEDED);
                log.info("Goal '{}' SUCCEEDED — all criteria met", goalStatus.goal().name());
            } else if (failed > 0 && completed > 0) {
                goalStatus.transitionTo(GoalStatus.Phase.DEGRADED);
                log.warn("Goal '{}' DEGRADED — {}/{} partitions completed, {} failed",
                        goalStatus.goal().name(), completed, total, failed);
            } else if (failed == total) {
                goalStatus.transitionTo(GoalStatus.Phase.FAILED);
                log.error("Goal '{}' FAILED — all partitions failed", goalStatus.goal().name());
            }
            return true;
        }

        // 4. Retry failed partitions that are reassignable
        retryFailedPartitions();

        return false;
    }

    /**
     * Record a partition result — called when a worker node reports completion.
     */
    public void recordPartitionResult(String partitionId, boolean success, Map<String, Object> result) {
        WorkPartition partition = partitions.get(partitionId);
        if (partition == null) {
            log.warn("Unknown partition result: {}", partitionId);
            return;
        }

        if (success) {
            partition.complete(result);
            goalStatus.recordPartitionCompleted();
            collectedResults.computeIfAbsent(partitionId, k -> new ArrayList<>()).add(result);

            // replicate via RAFT
            raftNode.propose(RaftLog.LogEntry.of(
                    raftNode.currentTerm(),
                    RaftLog.EntryType.PARTITION_COMPLETED,
                    partitionId,
                    Map.of("nodeId", partition.assignedNodeId(), "executionTimeMs", partition.executionTimeMs())
            ));

            log.info("Partition {} completed on node {} ({}ms)",
                    partitionId, partition.assignedNodeId(), partition.executionTimeMs());
        } else {
            partition.fail(result != null ? result.getOrDefault("error", "unknown").toString() : "unknown");
            goalStatus.recordPartitionFailed();

            raftNode.propose(RaftLog.LogEntry.of(
                    raftNode.currentTerm(),
                    RaftLog.EntryType.PARTITION_FAILED,
                    partitionId,
                    Map.of("nodeId", partition.assignedNodeId(), "reason", partition.failureReason())
            ));

            log.warn("Partition {} failed on node {}: {}",
                    partitionId, partition.assignedNodeId(), partition.failureReason());
        }
    }

    // ─── Partitioning ───────────────────────────────────────────────────

    private List<WorkPartition> partition(SwarmGoal goal, List<Map<String, Object>> workItems) {
        return switch (goal.partitioning()) {
            case ROUND_ROBIN -> partitionRoundRobin(workItems);
            case HASH -> partitionByHash(workItems);
            case RANGE -> partitionByRange(workItems);
            case ADAPTIVE -> partitionAdaptive(workItems);
        };
    }

    private List<WorkPartition> partitionRoundRobin(List<Map<String, Object>> items) {
        int nodeCount = Math.max(1, topology.activeNodeCount());
        int partitionSize = Math.max(1, items.size() / nodeCount);
        return createPartitions(items, partitionSize);
    }

    private List<WorkPartition> partitionByHash(List<Map<String, Object>> items) {
        int nodeCount = Math.max(1, topology.activeNodeCount());
        int partitionSize = Math.max(1, items.size() / nodeCount);
        return createPartitions(items, partitionSize);
    }

    private List<WorkPartition> partitionByRange(List<Map<String, Object>> items) {
        int nodeCount = Math.max(1, topology.activeNodeCount());
        int partitionSize = Math.max(1, items.size() / nodeCount);
        return createPartitions(items, partitionSize);
    }

    private List<WorkPartition> partitionAdaptive(List<Map<String, Object>> items) {
        // adaptive: partition based on node capacity (fewer items per busy node)
        List<ClusterNode> nodes = topology.nodesByLoad();
        int nodeCount = Math.max(1, nodes.size());
        int partitionSize = Math.max(1, items.size() / nodeCount);
        return createPartitions(items, partitionSize);
    }

    private List<WorkPartition> createPartitions(List<Map<String, Object>> items, int chunkSize) {
        List<WorkPartition> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, items.size());
            List<Map<String, Object>> chunk = items.subList(i, end);

            Map<String, Object> partitionInput = new LinkedHashMap<>();
            partitionInput.put("items", chunk);
            partitionInput.put("startIndex", i);
            partitionInput.put("endIndex", end);
            partitionInput.put("totalItems", items.size());

            String id = "partition-%d".formatted(result.size());
            result.add(new WorkPartition(id, result.size(), partitionInput, List.of()));
        }
        return result;
    }

    // ─── Assignment ─────────────────────────────────────────────────────

    private void assignPartitions(List<WorkPartition> partitionList) {
        List<ClusterNode> nodes = topology.nodesByLoad();
        if (nodes.isEmpty()) {
            goalStatus.addEvent("No active nodes available for partition assignment");
            goalStatus.transitionTo(GoalStatus.Phase.FAILED);
            return;
        }

        int nodeIdx = 0;
        for (WorkPartition partition : partitionList) {
            ClusterNode node = nodes.get(nodeIdx % nodes.size());
            partition.assignTo(node.nodeId());
            node.assignPartition();

            raftNode.propose(RaftLog.LogEntry.of(
                    raftNode.currentTerm(),
                    RaftLog.EntryType.PARTITION_ASSIGNED,
                    partition.partitionId(),
                    Map.of("nodeId", node.nodeId(), "index", partition.index())
            ));

            goalStatus.addEvent("Partition %s assigned to node %s".formatted(
                    partition.partitionId(), node.nodeId()));
            nodeIdx++;
        }
    }

    // ─── Failure Handling ───────────────────────────────────────────────

    private void handleNodeFailures() {
        List<ClusterNode> deadNodes = topology.deadNodes();
        if (deadNodes.isEmpty()) return;

        for (ClusterNode dead : deadNodes) {
            List<WorkPartition> orphaned = partitions.values().stream()
                    .filter(p -> dead.nodeId().equals(p.assignedNodeId()))
                    .filter(p -> !p.isTerminal())
                    .collect(Collectors.toList());

            if (!orphaned.isEmpty()) {
                goalStatus.addEvent("Node %s failed — %d partitions orphaned"
                        .formatted(dead.nodeId(), orphaned.size()));
                reassignPartitions(orphaned);
            }
        }
    }

    private void reassignPartitions(List<WorkPartition> orphaned) {
        List<ClusterNode> availableNodes = topology.nodesByLoad();
        if (availableNodes.isEmpty()) {
            goalStatus.addEvent("No nodes available for reassignment");
            return;
        }

        int nodeIdx = 0;
        for (WorkPartition partition : orphaned) {
            if (!partition.isReassignable()) {
                partition.fail("Max reassignment attempts exceeded");
                goalStatus.recordPartitionFailed();
                continue;
            }

            ClusterNode target = availableNodes.get(nodeIdx % availableNodes.size());
            partition.assignTo(target.nodeId());
            target.assignPartition();

            raftNode.propose(RaftLog.LogEntry.of(
                    raftNode.currentTerm(),
                    RaftLog.EntryType.WORK_REBALANCED,
                    partition.partitionId(),
                    Map.of("fromNode", partition.previousNodeId(),
                           "toNode", target.nodeId(),
                           "attempt", partition.attemptCount())
            ));

            goalStatus.addEvent("Partition %s reassigned from %s to %s (attempt %d)"
                    .formatted(partition.partitionId(), partition.previousNodeId(),
                            target.nodeId(), partition.attemptCount()));
            nodeIdx++;
        }
    }

    private void retryFailedPartitions() {
        List<WorkPartition> retriable = partitions.values().stream()
                .filter(p -> p.status() == WorkPartition.Status.FAILED)
                .filter(WorkPartition::isReassignable)
                .collect(Collectors.toList());

        if (!retriable.isEmpty()) {
            reassignPartitions(retriable);
        }
    }

    // ─── Success Criteria Evaluation ────────────────────────────────────

    private void evaluateSuccessCriteria() {
        for (String criterion : goalStatus.goal().successCriteria()) {
            boolean met = evaluateCriterion(criterion);
            goalStatus.updateCriterion(criterion,
                    met ? GoalStatus.CriterionStatus.MET : GoalStatus.CriterionStatus.FAILED);
        }
    }

    /**
     * Evaluate a success criterion against collected results.
     * Supports simple checks: "All X completed", "Coverage >= N%", count thresholds.
     */
    private boolean evaluateCriterion(String criterion) {
        String lower = criterion.toLowerCase();
        int completed = goalStatus.completedPartitions();
        int total = goalStatus.totalPartitions();

        // "all" criteria — require all partitions completed
        if (lower.startsWith("all ")) {
            return completed == total;
        }

        // percentage criteria — "coverage >= 95%"
        if (lower.contains(">=") && lower.contains("%")) {
            try {
                String numStr = lower.replaceAll("[^0-9.]", "");
                double threshold = Double.parseDouble(numStr);
                double actual = (completed * 100.0) / Math.max(1, total);
                return actual >= threshold;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        // default: criterion is met if >50% of partitions completed
        return completed > total / 2;
    }

    // ─── RAFT Log Listener ──────────────────────────────────────────────

    private void onLogEntryCommitted(RaftLog.LogEntry entry) {
        log.debug("Committed log entry: type={}, key={}", entry.type(), entry.key());
    }

    // ─── Accessors ──────────────────────────────────────────────────────

    public GoalStatus goalStatus() { return goalStatus; }
    public Map<String, WorkPartition> partitions() { return Map.copyOf(partitions); }
    public Map<String, List<Map<String, Object>>> collectedResults() { return Map.copyOf(collectedResults); }
}
