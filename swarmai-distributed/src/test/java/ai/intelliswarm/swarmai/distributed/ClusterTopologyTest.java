package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.execution.WorkPartition;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTopologyTest {

    @Test
    void shouldTrackNodes() {
        ClusterTopology topology = new ClusterTopology("local");
        topology.addNode(new ClusterNode("n1", "host1", 8001));
        topology.addNode(new ClusterNode("n2", "host2", 8002));

        assertEquals(2, topology.clusterSize());
        assertTrue(topology.getNode("n1").isPresent());
    }

    @Test
    void shouldFilterActiveNodes() {
        ClusterTopology topology = new ClusterTopology("local");

        ClusterNode active = new ClusterNode("n1", "h1", 1);
        active.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(active);

        ClusterNode dead = new ClusterNode("n2", "h2", 2);
        dead.setStatus(ClusterNode.Status.DEAD);
        topology.addNode(dead);

        assertEquals(2, topology.clusterSize());
        assertEquals(1, topology.activeNodeCount());
        assertEquals(1, topology.deadNodes().size());
    }

    @Test
    void shouldSortNodesByLoad() {
        ClusterTopology topology = new ClusterTopology("local");

        ClusterNode busy = new ClusterNode("busy", "h1", 1);
        busy.setStatus(ClusterNode.Status.ACTIVE);
        busy.assignPartition();
        busy.assignPartition();
        busy.assignPartition();
        topology.addNode(busy);

        ClusterNode idle = new ClusterNode("idle", "h2", 2);
        idle.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(idle);

        List<ClusterNode> byLoad = topology.nodesByLoad();
        assertEquals("idle", byLoad.get(0).nodeId());
        assertEquals("busy", byLoad.get(1).nodeId());
    }

    @Test
    void shouldFindLeastLoadedNode() {
        ClusterTopology topology = new ClusterTopology("local");

        ClusterNode n1 = new ClusterNode("n1", "h1", 1);
        n1.setStatus(ClusterNode.Status.ACTIVE);
        n1.assignPartition();
        n1.assignPartition();
        topology.addNode(n1);

        ClusterNode n2 = new ClusterNode("n2", "h2", 2);
        n2.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(n2);

        assertEquals("n2", topology.leastLoadedNode().orElseThrow().nodeId());
    }

    @Test
    void shouldProduceSnapshot() {
        ClusterTopology topology = new ClusterTopology("local");
        ClusterNode node = new ClusterNode("n1", "h1", 1);
        node.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(node);

        ClusterTopology.TopologySnapshot snapshot = topology.snapshot();
        assertEquals(1, snapshot.totalNodes());
        assertEquals(1, snapshot.activeNodes());
        assertEquals(1, snapshot.nodes().size());
        assertEquals("ACTIVE", snapshot.nodes().get(0).status());
    }

    @Test
    void shouldRemoveNode() {
        ClusterTopology topology = new ClusterTopology("local");
        topology.addNode(new ClusterNode("n1", "h1", 1));
        topology.addNode(new ClusterNode("n2", "h2", 2));

        topology.removeNode("n1");

        assertEquals(1, topology.clusterSize());
        assertTrue(topology.getNode("n1").isEmpty());
    }

    // ─── WorkPartition Tests ────────────────────────────────────────────

    @Test
    void shouldTrackPartitionLifecycle() {
        WorkPartition partition = new WorkPartition("p-1", 0, Map.of("data", "test"), List.of("t1"));

        assertEquals(WorkPartition.Status.UNASSIGNED, partition.status());
        assertEquals(0, partition.attemptCount());

        partition.assignTo("node-1");
        assertEquals(WorkPartition.Status.ASSIGNED, partition.status());
        assertEquals(1, partition.attemptCount());

        partition.markInProgress();
        assertEquals(WorkPartition.Status.IN_PROGRESS, partition.status());

        partition.complete(Map.of("result", "done"));
        assertEquals(WorkPartition.Status.COMPLETED, partition.status());
        assertTrue(partition.isTerminal());
    }

    @Test
    void shouldTrackReassignment() {
        WorkPartition partition = new WorkPartition("p-1", 0, Map.of(), List.of());
        partition.assignTo("node-1");
        partition.assignTo("node-2"); // reassignment

        assertEquals(WorkPartition.Status.REASSIGNED, partition.status());
        assertEquals("node-2", partition.assignedNodeId());
        assertEquals("node-1", partition.previousNodeId());
        assertEquals(2, partition.attemptCount());
    }

    @Test
    void shouldLimitReassignmentAttempts() {
        WorkPartition partition = new WorkPartition("p-1", 0, Map.of(), List.of());
        partition.assignTo("node-1");
        partition.assignTo("node-2");
        partition.assignTo("node-3");

        assertFalse(partition.isReassignable()); // 3 attempts = max
    }

    @Test
    void shouldTrackExecutionTime() {
        WorkPartition partition = new WorkPartition("p-1", 0, Map.of(), List.of());
        partition.assignTo("node-1");
        assertTrue(partition.executionTimeMs() >= 0);
    }

    // ─── SwarmGoal Tests ────────────────────────────────────────────────

    @Test
    void shouldBuildGoalWithBuilder() {
        SwarmGoal goal = SwarmGoal.builder()
                .name("audit")
                .objective("Audit all repos")
                .successCriterion("All scanned")
                .successCriterion("Coverage >= 95%")
                .partitioning(PartitionStrategy.ADAPTIVE)
                .replicas(5)
                .replicationFactor(3)
                .parameter("scope", "critical")
                .build();

        assertEquals("audit", goal.name());
        assertEquals("Audit all repos", goal.objective());
        assertEquals(2, goal.successCriteria().size());
        assertEquals(PartitionStrategy.ADAPTIVE, goal.partitioning());
        assertEquals(5, goal.replicas());
        assertEquals(3, goal.quorumSize()); // 5/2 + 1 = 3
    }

    @Test
    void shouldDetectExpiredGoal() {
        SwarmGoal expired = SwarmGoal.builder()
                .name("old")
                .objective("expired")
                .deadline(Instant.now().minusSeconds(3600))
                .partitioning(PartitionStrategy.ROUND_ROBIN)
                .replicas(1)
                .build();

        assertTrue(expired.isExpired());
        assertTrue(expired.hasDeadline());
    }

    @Test
    void shouldRejectInvalidGoal() {
        assertThrows(NullPointerException.class, () ->
                SwarmGoal.builder().objective("no name").partitioning(PartitionStrategy.HASH).replicas(1).build());

        assertThrows(IllegalArgumentException.class, () ->
                SwarmGoal.builder().name("x").objective("x").partitioning(PartitionStrategy.HASH).replicas(0).build());
    }

    // ─── GoalStatus Tests ───────────────────────────────────────────────

    @Test
    void shouldTrackGoalStatusTransitions() {
        SwarmGoal goal = SwarmGoal.builder()
                .name("status-test")
                .objective("test")
                .successCriterion("Done")
                .partitioning(PartitionStrategy.ROUND_ROBIN)
                .replicas(1)
                .build();

        GoalStatus status = new GoalStatus(goal);
        assertEquals(GoalStatus.Phase.PENDING, status.phase());
        assertFalse(status.isTerminal());

        status.transitionTo(GoalStatus.Phase.EXECUTING);
        assertNotNull(status.startedAt());

        status.transitionTo(GoalStatus.Phase.SUCCEEDED);
        assertTrue(status.isTerminal());
        assertNotNull(status.completedAt());
    }

    @Test
    void shouldTrackCriteriaStatus() {
        SwarmGoal goal = SwarmGoal.builder()
                .name("criteria-test")
                .objective("test")
                .successCriterion("A")
                .successCriterion("B")
                .partitioning(PartitionStrategy.ROUND_ROBIN)
                .replicas(1)
                .build();

        GoalStatus status = new GoalStatus(goal);
        assertFalse(status.allCriteriaMet());

        status.updateCriterion("A", GoalStatus.CriterionStatus.MET);
        assertFalse(status.allCriteriaMet());

        status.updateCriterion("B", GoalStatus.CriterionStatus.MET);
        assertTrue(status.allCriteriaMet());
    }
}
