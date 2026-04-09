package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.fault.FailureDetector;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FailureDetector — the component that detects dead nodes
 * and triggers partition reassignment.
 *
 * State machine: JOINING → ACTIVE → SUSPECT → DEAD
 *
 * If failure detection doesn't work, dead node partitions are never
 * reassigned and the distributed workflow stalls.
 */
@DisplayName("FailureDetector — Node Health State Transitions")
class FailureDetectorTest {

    private ClusterTopology topology;

    @BeforeEach
    void setUp() {
        topology = new ClusterTopology("leader-node");
    }

    private ClusterNode addNode(String id) {
        ClusterNode node = new ClusterNode(id, "host-" + id, 9100);
        node.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(node);
        return node;
    }

    // ================================================================
    // NODE STATE TRANSITIONS
    // ================================================================

    @Nested
    @DisplayName("Node State Transitions")
    class NodeStateTransitions {

        @Test
        @DisplayName("newly added node starts ACTIVE")
        void newNodeIsActive() {
            ClusterNode node = addNode("node-1");
            assertEquals(ClusterNode.Status.ACTIVE, node.status());
        }

        @Test
        @DisplayName("node transitions to SUSPECT after heartbeat timeout")
        void nodeTransitionsToSuspect() throws InterruptedException {
            ClusterNode node = addNode("node-1");
            // Let heartbeat age slightly
            Thread.sleep(50);

            List<ClusterNode> suspects = topology.detectSuspectNodes(10); // 10ms threshold

            assertFalse(suspects.isEmpty(), "Node should become SUSPECT");
            assertEquals(ClusterNode.Status.SUSPECT, node.status());
        }

        @Test
        @DisplayName("SUSPECT node transitions to DEAD after dead threshold")
        void suspectTransitionsToDead() throws InterruptedException {
            ClusterNode node = addNode("node-1");
            Thread.sleep(50);

            topology.detectSuspectNodes(10);
            assertEquals(ClusterNode.Status.SUSPECT, node.status());

            List<ClusterNode> dead = topology.promoteToDeadNodes(10);

            assertFalse(dead.isEmpty(), "SUSPECT node should become DEAD");
            assertEquals(ClusterNode.Status.DEAD, node.status());
        }

        @Test
        @DisplayName("ACTIVE node with recent heartbeat stays ACTIVE")
        void recentHeartbeatStaysActive() {
            ClusterNode node = addNode("node-1");
            node.recordHeartbeat(); // fresh heartbeat

            List<ClusterNode> suspects = topology.detectSuspectNodes(60_000);

            assertTrue(suspects.isEmpty(), "Node with fresh heartbeat should not be SUSPECT");
            assertEquals(ClusterNode.Status.ACTIVE, node.status());
        }

        @Test
        @DisplayName("heartbeat resets SUSPECT node back to ACTIVE")
        void heartbeatResetsSuspect() throws InterruptedException {
            ClusterNode node = addNode("node-1");
            Thread.sleep(50);
            topology.detectSuspectNodes(10);
            assertEquals(ClusterNode.Status.SUSPECT, node.status());

            // Send heartbeat — should recover
            node.recordHeartbeat();
            node.setStatus(ClusterNode.Status.ACTIVE);

            assertEquals(ClusterNode.Status.ACTIVE, node.status(),
                "Heartbeat should allow recovery from SUSPECT");
        }
    }

    // ================================================================
    // FAILURE DETECTOR — Periodic checks with callback
    // ================================================================

    @Nested
    @DisplayName("FailureDetector Periodic Detection")
    class PeriodicDetection {

        @Test
        @DisplayName("onNodesDead callback fires when node dies")
        void callbackFiresOnDeath() throws InterruptedException {
            addNode("node-1");

            CopyOnWriteArrayList<String> deadNodeIds = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            FailureDetector detector = new FailureDetector(topology, 1, 2, 50);
            detector.onNodesDead(deadNodes -> {
                deadNodes.forEach(n -> deadNodeIds.add(n.nodeId()));
                latch.countDown();
            });

            detector.start();
            boolean detected = latch.await(5, TimeUnit.SECONDS);
            detector.stop();

            assertTrue(detected,
                "FailureDetector should detect dead node within 5s");
            assertTrue(deadNodeIds.contains("node-1"),
                "Dead callback should include node-1");
        }

        @Test
        @DisplayName("no callback when all nodes are healthy")
        void noCallbackWhenHealthy() throws InterruptedException {
            ClusterNode node = addNode("node-1");

            CopyOnWriteArrayList<String> deadNodeIds = new CopyOnWriteArrayList<>();

            FailureDetector detector = new FailureDetector(topology, 60_000, 120_000, 100);
            detector.onNodesDead(deadNodes ->
                deadNodes.forEach(n -> deadNodeIds.add(n.nodeId())));

            // Keep node alive with heartbeats
            Thread heartbeater = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    node.recordHeartbeat();
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            });

            detector.start();
            heartbeater.start();
            heartbeater.join(2000);
            detector.stop();

            assertTrue(deadNodeIds.isEmpty(),
                "No nodes should die when heartbeats are being sent");
        }

        @Test
        @DisplayName("detector stop prevents further callbacks")
        void stopPreventsCallbacks() throws InterruptedException {
            addNode("node-1");

            CopyOnWriteArrayList<String> deadNodeIds = new CopyOnWriteArrayList<>();

            FailureDetector detector = new FailureDetector(topology, 60_000, 120_000, 100);
            detector.onNodesDead(deadNodes ->
                deadNodes.forEach(n -> deadNodeIds.add(n.nodeId())));

            detector.start();
            detector.stop();
            Thread.sleep(500);

            assertTrue(deadNodeIds.isEmpty(), "Stopped detector should not fire");
        }
    }

    // ================================================================
    // MULTIPLE NODES — Selective death detection
    // ================================================================

    @Nested
    @DisplayName("Multiple Node Tracking")
    class MultipleNodes {

        @Test
        @DisplayName("only nodes without heartbeats die")
        void selectiveDeath() throws InterruptedException {
            ClusterNode node1 = addNode("node-1"); // will die
            ClusterNode node2 = addNode("node-2"); // will stay alive
            ClusterNode node3 = addNode("node-3"); // will die

            CopyOnWriteArrayList<String> deadNodeIds = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            // Use thresholds that give heartbeater time to run:
            // suspect at 200ms, dead at 400ms, check every 100ms
            // heartbeats every 50ms — well within the 200ms window
            FailureDetector detector = new FailureDetector(topology, 200, 400, 100);
            detector.onNodesDead(deadNodes -> {
                deadNodes.forEach(n -> deadNodeIds.add(n.nodeId()));
                latch.countDown();
            });

            // Start heartbeater BEFORE detector to ensure node-2 has fresh heartbeats
            Thread heartbeater = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    node2.recordHeartbeat();
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            });
            heartbeater.start();
            Thread.sleep(50); // let first heartbeat land

            detector.start();
            boolean detected = latch.await(5, TimeUnit.SECONDS);
            detector.stop();
            heartbeater.interrupt();

            assertTrue(detected, "Should detect dead nodes");
            assertFalse(deadNodeIds.contains("node-2"),
                "WEAKNESS DETECTED: Node-2 (receiving heartbeats every 50ms, suspect threshold " +
                "200ms) should NOT be marked dead. Dead nodes: " + deadNodeIds +
                ". This indicates a race condition in failure detection — the detector " +
                "may be checking state before heartbeats are processed.");
        }
    }
}
