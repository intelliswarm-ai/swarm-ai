package ai.intelliswarm.swarmai.distributed.goal;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the live state of a distributed goal — desired vs. actual.
 *
 * <p>The goal spec declares what you want, GoalStatus tracks what you have.
 * The reconciler drives actual state toward desired state.</p>
 */
public class GoalStatus {

    public enum Phase {
        PENDING,        // Goal accepted, not yet scheduled
        PARTITIONING,   // Leader is splitting work into partitions
        EXECUTING,      // Partitions being processed by nodes
        RECONCILING,    // Merging partial results, checking success criteria
        SUCCEEDED,      // All success criteria met
        FAILED,         // Deadline expired or unrecoverable error
        DEGRADED        // Partial success — some nodes failed, partial results available
    }

    private final SwarmGoal goal;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.PENDING);
    private final AtomicInteger totalPartitions = new AtomicInteger(0);
    private final AtomicInteger completedPartitions = new AtomicInteger(0);
    private final AtomicInteger failedPartitions = new AtomicInteger(0);
    private final Map<String, CriterionStatus> criteriaStatus = new ConcurrentHashMap<>();
    private final Map<String, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String leaderId;

    public GoalStatus(SwarmGoal goal) {
        this.goal = goal;
        goal.successCriteria().forEach(c -> criteriaStatus.put(c, CriterionStatus.PENDING));
    }

    public Phase phase() { return phase.get(); }
    public SwarmGoal goal() { return goal; }
    public int totalPartitions() { return totalPartitions.get(); }
    public int completedPartitions() { return completedPartitions.get(); }
    public int failedPartitions() { return failedPartitions.get(); }
    public String leaderId() { return leaderId; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public List<String> events() { return List.copyOf(events); }
    public Map<String, CriterionStatus> criteriaStatus() { return Map.copyOf(criteriaStatus); }
    public Map<String, NodeStatus> nodeStatuses() { return Map.copyOf(nodeStatuses); }

    public void transitionTo(Phase newPhase) {
        Phase old = phase.getAndSet(newPhase);
        if (newPhase == Phase.EXECUTING && startedAt == null) {
            startedAt = Instant.now();
        }
        if (newPhase == Phase.SUCCEEDED || newPhase == Phase.FAILED || newPhase == Phase.DEGRADED) {
            completedAt = Instant.now();
        }
        addEvent("Phase transition: %s → %s".formatted(old, newPhase));
    }

    public void setTotalPartitions(int total) { totalPartitions.set(total); }
    public void recordPartitionCompleted() { completedPartitions.incrementAndGet(); }
    public void recordPartitionFailed() { failedPartitions.incrementAndGet(); }
    public void setLeaderId(String id) { this.leaderId = id; addEvent("Leader elected: " + id); }

    public void updateCriterion(String criterion, CriterionStatus status) {
        criteriaStatus.put(criterion, status);
    }

    public void updateNodeStatus(String nodeId, NodeStatus status) {
        nodeStatuses.put(nodeId, status);
    }

    public void addEvent(String event) {
        events.add("[%s] %s".formatted(Instant.now(), event));
    }

    public double completionPercentage() {
        int total = totalPartitions.get();
        if (total == 0) return 0.0;
        return (completedPartitions.get() * 100.0) / total;
    }

    public boolean allCriteriaMet() {
        return criteriaStatus.values().stream().allMatch(s -> s == CriterionStatus.MET);
    }

    public boolean isTerminal() {
        Phase p = phase.get();
        return p == Phase.SUCCEEDED || p == Phase.FAILED || p == Phase.DEGRADED;
    }

    public GoalStatusSnapshot snapshot() {
        return new GoalStatusSnapshot(
                goal.name(), goal.objective(), phase.get(),
                totalPartitions.get(), completedPartitions.get(), failedPartitions.get(),
                completionPercentage(), allCriteriaMet(),
                Map.copyOf(criteriaStatus), leaderId,
                startedAt, completedAt, List.copyOf(events)
        );
    }

    public enum CriterionStatus { PENDING, IN_PROGRESS, MET, FAILED }

    public record NodeStatus(String nodeId, NodeState state, int assignedPartitions,
                             int completedPartitions, long lastHeartbeatMs) {
        public enum NodeState { ACTIVE, SUSPECT, DEAD, DRAINING }
    }

    public record GoalStatusSnapshot(
            String goalName, String objective, Phase phase,
            int totalPartitions, int completedPartitions, int failedPartitions,
            double completionPercentage, boolean allCriteriaMet,
            Map<String, CriterionStatus> criteriaStatus, String leaderId,
            Instant startedAt, Instant completedAt, List<String> events
    ) {}
}
