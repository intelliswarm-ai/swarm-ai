package ai.intelliswarm.swarmai.distributed.cluster;

import ai.intelliswarm.swarmai.distributed.consensus.RaftLog;
import ai.intelliswarm.swarmai.distributed.consensus.RaftMessage;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.execution.GoalReconciler;
import ai.intelliswarm.swarmai.distributed.execution.WorkPartition;
import ai.intelliswarm.swarmai.distributed.fault.FailureDetector;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * A node in a distributed SwarmAI cluster — the unit of collaborative goal execution.
 *
 * <p>Each DistributedSwarmNode runs on a separate JVM (or machine) and participates
 * in RAFT consensus with peer nodes. Together, the cluster executes a shared goal
 * by partitioning work, distributing it to nodes, and reconciling results.</p>
 *
 * <h3>Node Responsibilities</h3>
 * <ul>
 *   <li><b>Leader:</b> Partitions the goal, assigns work, monitors progress,
 *       handles failures, evaluates success criteria, drives reconciliation</li>
 *   <li><b>Follower:</b> Executes assigned partitions using local agents,
 *       reports results back to leader, replicates RAFT log</li>
 * </ul>
 *
 * <h3>Inter-Node Communication</h3>
 * <p>Nodes communicate via TCP using length-prefixed JSON messages.
 * RAFT heartbeats serve as both consensus protocol and health monitoring.
 * Goal-level messages (partition assignments, results) are replicated
 * through the RAFT log for consistency and fault tolerance.</p>
 *
 * <h3>Collaborative Execution</h3>
 * <pre>{@code
 * // Node 1 (becomes leader via RAFT election)
 * DistributedSwarmNode node1 = DistributedSwarmNode.builder()
 *     .nodeId("node-1")
 *     .listenPort(9100)
 *     .peer("node-2", "host2", 9100)
 *     .peer("node-3", "host3", 9100)
 *     .partitionExecutor(partition -> {
 *         // execute partition using local SwarmAI agents
 *         return mySwarm.kickoff(partition.inputs());
 *     })
 *     .build();
 *
 * node1.start();
 *
 * // Only the leader submits the goal — followers receive work via RAFT
 * if (node1.isLeader()) {
 *     GoalStatus status = node1.submitGoal(goal, workItems);
 * }
 * }</pre>
 */
public class DistributedSwarmNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DistributedSwarmNode.class);

    private final String nodeId;
    private final RaftNode raftNode;
    private final NodeCommunicator communicator;
    private final ClusterTopology topology;
    private final FailureDetector failureDetector;
    private final Function<WorkPartition, Map<String, Object>> partitionExecutor;
    private final ExecutorService workerPool;

    private volatile GoalReconciler reconciler;
    private volatile ScheduledExecutorService reconciliationScheduler;

    private DistributedSwarmNode(Builder builder) {
        this.nodeId = builder.nodeId;
        this.partitionExecutor = builder.partitionExecutor;

        // build communicator
        this.communicator = new NodeCommunicator(nodeId, builder.listenPort);

        // build RAFT node with network transport
        this.raftNode = new RaftNode(nodeId, communicator,
                builder.heartbeatIntervalMs, builder.electionTimeoutMinMs, builder.electionTimeoutMaxMs);

        // build topology
        this.topology = new ClusterTopology(nodeId);
        ClusterNode self = new ClusterNode(nodeId, "localhost", builder.listenPort);
        self.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(self);

        // register peers
        for (PeerSpec peer : builder.peers) {
            communicator.registerPeer(peer.id, peer.host, peer.port);
            ClusterNode peerNode = new ClusterNode(peer.id, peer.host, peer.port);
            peerNode.setStatus(ClusterNode.Status.ACTIVE);
            topology.addNode(peerNode);
            raftNode.addPeer(peerNode);
        }

        // failure detector
        this.failureDetector = new FailureDetector(topology,
                builder.suspectThresholdMs, builder.deadThresholdMs, 2000);

        // worker pool for executing partitions locally
        this.workerPool = Executors.newFixedThreadPool(builder.workerThreads, r -> {
            Thread t = new Thread(r, "swarm-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        // wire message handler — route RAFT messages to the RaftNode
        communicator.onMessage(this::handleMessage);

        // wire committed log entries — execute partition assignments on this node
        raftNode.onCommit(this::onLogEntryCommitted);
    }

    /**
     * Start the node — begins listening for peer connections and participates in RAFT.
     */
    public void start() throws IOException {
        communicator.start();
        raftNode.start();
        failureDetector.start();
        log.info("DistributedSwarmNode '{}' started on port {}", nodeId, communicator.listenPort());
    }

    /**
     * Submit a goal for distributed execution. Only the RAFT leader should call this.
     * Followers receive work assignments through RAFT log replication.
     */
    public GoalStatus submitGoal(SwarmGoal goal, List<Map<String, Object>> workItems) {
        if (!raftNode.isLeader()) {
            throw new IllegalStateException(
                    "Only the leader can submit goals. Current leader: " + raftNode.leaderId());
        }

        reconciler = new GoalReconciler(raftNode, topology);
        GoalStatus status = reconciler.initializeGoal(goal, workItems);

        // start reconciliation loop
        reconciliationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reconciler-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        reconciliationScheduler.scheduleAtFixedRate(() -> {
            try {
                if (reconciler.reconcile()) {
                    log.info("Goal '{}' reached terminal state: {}",
                            goal.name(), reconciler.goalStatus().phase());
                    reconciliationScheduler.shutdown();
                }
            } catch (Exception e) {
                log.error("Reconciliation error: {}", e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        return status;
    }

    /**
     * Wait for the current goal to complete.
     */
    public GoalStatus awaitGoalCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < deadline) {
            if (reconciler != null && reconciler.goalStatus().isTerminal()) {
                return reconciler.goalStatus();
            }
            Thread.sleep(100);
        }
        return reconciler != null ? reconciler.goalStatus() : null;
    }

    // ─── Message Handling ───────────────────────────────────────────────

    private void handleMessage(RaftMessage message) {
        switch (message) {
            case RaftMessage.VoteRequest vr -> {
                var response = raftNode.handleVoteRequest(vr);
                communicator.send(vr.candidateId(), response);
            }
            case RaftMessage.VoteResponse vr -> {
                // vote responses are handled during election — need to track votes
                // for now, the RaftNode handles this internally
            }
            case RaftMessage.AppendEntries ae -> {
                var response = raftNode.handleAppendEntries(ae);
                communicator.send(ae.leaderId(), response);
            }
            case RaftMessage.AppendEntriesResponse aer -> {
                raftNode.handleAppendEntriesResponse(aer);
            }
            case RaftMessage.PartitionResult pr -> {
                if (reconciler != null) {
                    reconciler.recordPartitionResult(pr.partitionId(), pr.success(), pr.result());
                }
            }
            case RaftMessage.PartitionAssignment pa -> {
                if (pa.targetNodeId().equals(nodeId)) {
                    executePartitionLocally(pa);
                }
            }
            case RaftMessage.GoalProposal gp -> {
                log.info("Received goal proposal from {}: {}", gp.proposerId(), gp.goalName());
            }
        }
    }

    private void onLogEntryCommitted(RaftLog.LogEntry entry) {
        if (entry.type() == RaftLog.EntryType.PARTITION_ASSIGNED) {
            String assignedNode = (String) entry.data().get("nodeId");
            if (nodeId.equals(assignedNode)) {
                log.info("Partition {} assigned to this node via RAFT log", entry.key());
                // the partition executor will pick this up
            }
        }
    }

    private void executePartitionLocally(RaftMessage.PartitionAssignment assignment) {
        workerPool.submit(() -> {
            log.info("Executing partition {} on node {}", assignment.partitionId(), nodeId);
            try {
                WorkPartition partition = new WorkPartition(
                        assignment.partitionId(), 0, assignment.partitionData(), List.of());
                Map<String, Object> result = partitionExecutor.apply(partition);

                // report result back to leader
                communicator.send(assignment.leaderId(), new RaftMessage.PartitionResult(
                        raftNode.currentTerm(), nodeId, assignment.partitionId(), true, result));

            } catch (Exception e) {
                log.error("Partition {} execution failed: {}", assignment.partitionId(), e.getMessage());
                communicator.send(assignment.leaderId(), new RaftMessage.PartitionResult(
                        raftNode.currentTerm(), nodeId, assignment.partitionId(), false,
                        Map.of("error", e.getMessage())));
            }
        });
    }

    // ─── Accessors ──────────────────────────────────────────────────────

    public String nodeId() { return nodeId; }
    public boolean isLeader() { return raftNode.isLeader(); }
    public String leaderId() { return raftNode.leaderId(); }
    public ClusterTopology topology() { return topology; }
    public GoalReconciler reconciler() { return reconciler; }
    public RaftNode raftNode() { return raftNode; }

    @Override
    public void close() {
        failureDetector.stop();
        raftNode.stop();
        communicator.close();
        workerPool.shutdownNow();
        if (reconciliationScheduler != null) reconciliationScheduler.shutdownNow();
        log.info("DistributedSwarmNode '{}' closed", nodeId);
    }

    // ─── Builder ────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String nodeId;
        private int listenPort = 9100;
        private final List<PeerSpec> peers = new ArrayList<>();
        private Function<WorkPartition, Map<String, Object>> partitionExecutor = p -> Map.of();
        private int heartbeatIntervalMs = 150;
        private int electionTimeoutMinMs = 300;
        private int electionTimeoutMaxMs = 500;
        private long suspectThresholdMs = 5000;
        private long deadThresholdMs = 15000;
        private int workerThreads = Runtime.getRuntime().availableProcessors();

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder listenPort(int port) { this.listenPort = port; return this; }
        public Builder peer(String id, String host, int port) {
            peers.add(new PeerSpec(id, host, port));
            return this;
        }
        public Builder partitionExecutor(Function<WorkPartition, Map<String, Object>> executor) {
            this.partitionExecutor = executor;
            return this;
        }
        public Builder heartbeatIntervalMs(int ms) { this.heartbeatIntervalMs = ms; return this; }
        public Builder electionTimeoutMinMs(int ms) { this.electionTimeoutMinMs = ms; return this; }
        public Builder electionTimeoutMaxMs(int ms) { this.electionTimeoutMaxMs = ms; return this; }
        public Builder suspectThresholdMs(long ms) { this.suspectThresholdMs = ms; return this; }
        public Builder deadThresholdMs(long ms) { this.deadThresholdMs = ms; return this; }
        public Builder workerThreads(int threads) { this.workerThreads = threads; return this; }

        public DistributedSwarmNode build() {
            Objects.requireNonNull(nodeId, "nodeId is required");
            return new DistributedSwarmNode(this);
        }
    }

    private record PeerSpec(String id, String host, int port) {}
}
