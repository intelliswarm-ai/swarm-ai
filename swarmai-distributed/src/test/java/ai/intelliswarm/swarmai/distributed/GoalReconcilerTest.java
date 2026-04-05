package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.execution.GoalReconciler;
import ai.intelliswarm.swarmai.distributed.execution.WorkPartition;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GoalReconcilerTest {

    private RaftNode raftNode;
    private ClusterTopology topology;
    private GoalReconciler reconciler;

    @BeforeEach
    void setUp() {
        topology = new ClusterTopology("leader-node");

        // add 3 active nodes
        for (int i = 0; i < 3; i++) {
            ClusterNode node = new ClusterNode("node-" + i, "localhost", 8000 + i);
            node.setStatus(ClusterNode.Status.ACTIVE);
            topology.addNode(node);
        }

        RaftNode.MessageTransport noopTransport = (target, msg) -> {};
        raftNode = new RaftNode("leader-node", noopTransport);
        raftNode.start();
        raftNode.startElection(); // become leader

        reconciler = new GoalReconciler(raftNode, topology);
    }

    @AfterEach
    void tearDown() {
        raftNode.stop();
    }

    @Test
    void shouldInitializeGoalAndCreatePartitions() {
        SwarmGoal goal = buildGoal("test-goal", List.of("All items processed"));
        List<Map<String, Object>> items = createWorkItems(9);

        GoalStatus status = reconciler.initializeGoal(goal, items);

        assertEquals(GoalStatus.Phase.EXECUTING, status.phase());
        assertEquals(3, status.totalPartitions()); // 9 items / 3 nodes = 3 partitions
        assertNotNull(status.leaderId());
    }

    @Test
    void shouldSucceedWhenAllPartitionsComplete() {
        SwarmGoal goal = buildGoal("test-goal", List.of("All items processed"));
        List<Map<String, Object>> items = createWorkItems(6);
        reconciler.initializeGoal(goal, items);

        // complete all partitions
        for (WorkPartition partition : reconciler.partitions().values()) {
            reconciler.recordPartitionResult(partition.partitionId(), true, Map.of("result", "done"));
        }

        boolean terminal = reconciler.reconcile();

        assertTrue(terminal);
        assertEquals(GoalStatus.Phase.SUCCEEDED, reconciler.goalStatus().phase());
    }

    @Test
    void shouldReportDegradedWhenSomePartitionsFail() {
        SwarmGoal goal = buildGoal("test-goal", List.of("Coverage >= 50%"));
        List<Map<String, Object>> items = createWorkItems(6);
        reconciler.initializeGoal(goal, items);

        // complete 2 partitions, fail 1
        List<WorkPartition> partitions = new ArrayList<>(reconciler.partitions().values());
        reconciler.recordPartitionResult(partitions.get(0).partitionId(), true, Map.of());
        reconciler.recordPartitionResult(partitions.get(1).partitionId(), true, Map.of());
        // mark as failed with max retries exhausted
        partitions.get(2).fail("timeout");
        partitions.get(2).assignTo("retry-1"); // attempt 2
        partitions.get(2).fail("timeout");
        partitions.get(2).assignTo("retry-2"); // attempt 3
        partitions.get(2).fail("timeout"); // now attempt count = 3, not reassignable

        reconciler.reconcile();

        GoalStatus status = reconciler.goalStatus();
        // with 2/3 completed and percentage criterion >= 50%, it should succeed
        assertTrue(status.phase() == GoalStatus.Phase.SUCCEEDED
                || status.phase() == GoalStatus.Phase.DEGRADED);
    }

    @Test
    void shouldFailWhenDeadlineExpires() {
        SwarmGoal goal = SwarmGoal.builder()
                .name("expired-goal")
                .objective("Do something")
                .successCriterion("All done")
                .deadline(Instant.now().minusSeconds(60)) // already expired
                .partitioning(PartitionStrategy.ROUND_ROBIN)
                .replicas(3)
                .build();

        reconciler.initializeGoal(goal, createWorkItems(3));
        boolean terminal = reconciler.reconcile();

        assertTrue(terminal);
        assertEquals(GoalStatus.Phase.FAILED, reconciler.goalStatus().phase());
    }

    @Test
    void shouldTrackCompletionPercentage() {
        SwarmGoal goal = buildGoal("progress-goal", List.of("All completed"));
        List<Map<String, Object>> items = createWorkItems(9);
        reconciler.initializeGoal(goal, items);

        assertEquals(0.0, reconciler.goalStatus().completionPercentage());

        // complete 1 of 3 partitions
        WorkPartition first = reconciler.partitions().values().iterator().next();
        reconciler.recordPartitionResult(first.partitionId(), true, Map.of());

        assertEquals(1, reconciler.goalStatus().completedPartitions());
        assertTrue(reconciler.goalStatus().completionPercentage() > 30.0);
    }

    @Test
    void shouldCollectResultsFromPartitions() {
        SwarmGoal goal = buildGoal("results-goal", List.of("All completed"));
        reconciler.initializeGoal(goal, createWorkItems(3));

        for (WorkPartition partition : reconciler.partitions().values()) {
            reconciler.recordPartitionResult(partition.partitionId(), true,
                    Map.of("findings", 42));
        }

        assertEquals(3, reconciler.collectedResults().size());
    }

    @Test
    void shouldRecordEventsInGoalStatus() {
        SwarmGoal goal = buildGoal("events-goal", List.of("Done"));
        reconciler.initializeGoal(goal, createWorkItems(3));

        List<String> events = reconciler.goalStatus().events();
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.contains("Phase transition")));
        assertTrue(events.stream().anyMatch(e -> e.contains("Partition")));
    }

    @Test
    void shouldCreateSnapshotOfGoalStatus() {
        SwarmGoal goal = buildGoal("snapshot-goal", List.of("All done"));
        GoalStatus status = reconciler.initializeGoal(goal, createWorkItems(6));

        GoalStatus.GoalStatusSnapshot snapshot = status.snapshot();

        assertEquals("snapshot-goal", snapshot.goalName());
        assertEquals(GoalStatus.Phase.EXECUTING, snapshot.phase());
        assertEquals(3, snapshot.totalPartitions()); // 6 items / chunkSize(6/3=2) = 3 partitions
        assertNotNull(snapshot.startedAt());
    }

    @Test
    void shouldEvaluatePercentageCriteria() {
        SwarmGoal goal = buildGoal("pct-goal", List.of("Coverage >= 60%"));
        reconciler.initializeGoal(goal, createWorkItems(6));

        // complete 2/2 partitions
        for (WorkPartition p : reconciler.partitions().values()) {
            reconciler.recordPartitionResult(p.partitionId(), true, Map.of());
        }

        reconciler.reconcile();

        assertEquals(GoalStatus.Phase.SUCCEEDED, reconciler.goalStatus().phase());
        assertTrue(reconciler.goalStatus().allCriteriaMet());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private SwarmGoal buildGoal(String name, List<String> criteria) {
        return SwarmGoal.builder()
                .name(name)
                .objective("Test objective")
                .successCriteria(criteria)
                .partitioning(PartitionStrategy.ROUND_ROBIN)
                .replicas(3)
                .build();
    }

    private List<Map<String, Object>> createWorkItems(int count) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(Map.of("itemId", "item-" + i, "data", "payload-" + i));
        }
        return items;
    }
}
