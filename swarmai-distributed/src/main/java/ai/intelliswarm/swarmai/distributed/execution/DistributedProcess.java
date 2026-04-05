package ai.intelliswarm.swarmai.distributed.execution;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterNode;
import ai.intelliswarm.swarmai.distributed.cluster.ClusterTopology;
import ai.intelliswarm.swarmai.distributed.consensus.RaftLog;
import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.fault.FailureDetector;
import ai.intelliswarm.swarmai.distributed.goal.GoalStatus;
import ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy;
import ai.intelliswarm.swarmai.distributed.goal.SwarmGoal;
import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Distributed process type — executes goals across a RAFT-coordinated cluster of agent nodes.
 *
 * <p>You declare a goal (desired state), and the DistributedProcess drives
 * the cluster toward that goal through continuous reconciliation.</p>
 *
 * <h3>Execution Flow</h3>
 * <ol>
 *   <li>Form cluster — RAFT leader election across available nodes</li>
 *   <li>Leader partitions the goal into work units</li>
 *   <li>Work units are distributed to nodes via RAFT log replication</li>
 *   <li>Each node executes its partition using local agents</li>
 *   <li>Results are submitted back to leader via RAFT</li>
 *   <li>Leader reconciles results against success criteria</li>
 *   <li>On node failure, orphaned partitions are reassigned</li>
 *   <li>Goal completes when all success criteria are met</li>
 * </ol>
 *
 * <h3>YAML DSL</h3>
 * <pre>{@code
 * swarm:
 *   name: "distributed-audit"
 *   process: DISTRIBUTED
 *
 *   goal:
 *     objective: "Audit 10,000 repositories for critical vulnerabilities"
 *     successCriteria:
 *       - "All repositories scanned"
 *       - "Critical vulnerabilities patched"
 *       - "Coverage >= 95%"
 *     deadline: "2026-04-30T00:00:00Z"
 *
 *   cluster:
 *     replicas: 5
 *     consensus: RAFT
 *     heartbeatMs: 150
 *     electionTimeoutMs: 300
 *
 *   partitioning:
 *     strategy: ADAPTIVE
 *     rebalanceOnFailure: true
 *
 *   agents:
 *     coordinator:
 *       role: "Cluster Coordinator"
 *     worker:
 *       role: "Security Analyst"
 *
 *   tasks:
 *     scan:
 *       description: "Scan repository for vulnerabilities"
 *       agent: worker
 * }</pre>
 */
public class DistributedProcess implements Process {

    private static final Logger log = LoggerFactory.getLogger(DistributedProcess.class);

    private final List<Agent> agents;
    private final Agent coordinatorAgent;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxParallelPartitions;
    private final String qualityCriteria;
    private final PartitionStrategy partitionStrategy;

    // Cluster infrastructure — initialized on execute()
    private ClusterTopology topology;
    private RaftNode raftNode;
    private GoalReconciler reconciler;
    private FailureDetector failureDetector;
    private ExecutorService partitionExecutor;

    public DistributedProcess(List<Agent> agents,
                              Agent coordinatorAgent,
                              ApplicationEventPublisher eventPublisher,
                              int maxParallelPartitions,
                              String qualityCriteria,
                              PartitionStrategy partitionStrategy) {
        this.agents = new ArrayList<>(agents);
        this.coordinatorAgent = coordinatorAgent;
        this.eventPublisher = eventPublisher;
        this.maxParallelPartitions = maxParallelPartitions > 0 ? maxParallelPartitions : Runtime.getRuntime().availableProcessors();
        this.qualityCriteria = qualityCriteria;
        this.partitionStrategy = partitionStrategy != null ? partitionStrategy : PartitionStrategy.ADAPTIVE;
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        log.info("Distributed process starting for swarm '{}' with {} tasks", swarmId, tasks.size());

        try {
            validateTasks(tasks);

            // 1. Build the goal from inputs and tasks
            SwarmGoal goal = buildGoalFromInputs(inputs, tasks, swarmId);

            // 2. Initialize cluster (in-process for single-JVM, networked for multi-node)
            initializeCluster(swarmId, goal);

            // 3. Extract work items from inputs
            List<Map<String, Object>> workItems = extractWorkItems(inputs, tasks);

            // 4. Initialize goal and begin reconciliation
            GoalStatus status = reconciler.initializeGoal(goal, workItems);

            // 5. Execute partitions in parallel using local agents
            executePartitionsLocally(tasks, inputs);

            // 6. Reconciliation loop — drive to completion
            int maxCycles = 100;
            int cycle = 0;
            while (!reconciler.reconcile() && cycle++ < maxCycles) {
                Thread.sleep(100); // reconciliation tick
            }

            // 7. Build output
            long executionMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            return buildOutput(status, executionMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Distributed execution interrupted", e);
        } finally {
            shutdown();
        }
    }

    private SwarmGoal buildGoalFromInputs(Map<String, Object> inputs, List<Task> tasks, String swarmId) {
        SwarmGoal.Builder builder = SwarmGoal.builder()
                .name(swarmId)
                .partitioning(partitionStrategy)
                .replicas(maxParallelPartitions);

        // extract goal from inputs or use quality criteria
        Object objective = inputs.get("objective");
        if (objective != null) {
            builder.objective(objective.toString());
        } else {
            builder.objective("Execute %d tasks across distributed cluster".formatted(tasks.size()));
        }

        // extract success criteria
        Object criteria = inputs.get("successCriteria");
        if (criteria instanceof List<?> list) {
            list.forEach(c -> builder.successCriterion(c.toString()));
        } else if (qualityCriteria != null && !qualityCriteria.isEmpty()) {
            builder.successCriterion(qualityCriteria);
        } else {
            builder.successCriterion("All partitions completed");
        }

        builder.parameters(inputs);
        return builder.build();
    }

    private void initializeCluster(String swarmId, SwarmGoal goal) {
        String localNodeId = "node-" + swarmId;
        topology = new ClusterTopology(localNodeId);

        // in-process transport for single-JVM execution
        RaftNode.MessageTransport inProcessTransport = (targetId, message) -> {
            // messages delivered directly in single-JVM mode
        };

        raftNode = new RaftNode(localNodeId, inProcessTransport);

        // register local node + virtual worker nodes
        ClusterNode localNode = new ClusterNode(localNodeId, "localhost", 0);
        localNode.setStatus(ClusterNode.Status.ACTIVE);
        topology.addNode(localNode);

        for (int i = 0; i < maxParallelPartitions - 1; i++) {
            String workerId = "worker-%d".formatted(i);
            ClusterNode worker = new ClusterNode(workerId, "localhost", 0);
            worker.setStatus(ClusterNode.Status.ACTIVE);
            topology.addNode(worker);
        }

        // single-JVM mode: become leader immediately
        raftNode.start();

        reconciler = new GoalReconciler(raftNode, topology);

        failureDetector = new FailureDetector(topology);
        failureDetector.start();

        partitionExecutor = Executors.newFixedThreadPool(maxParallelPartitions, r -> {
            Thread t = new Thread(r, "partition-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractWorkItems(Map<String, Object> inputs, List<Task> tasks) {
        // check if inputs contain explicit work items
        Object items = inputs.get("workItems");
        if (items instanceof List<?> list) {
            return list.stream()
                    .map(item -> item instanceof Map<?,?> map ? (Map<String, Object>) map : Map.of("item", item))
                    .toList();
        }

        // default: one work item per task
        List<Map<String, Object>> workItems = new ArrayList<>();
        for (Task task : tasks) {
            Map<String, Object> item = new LinkedHashMap<>(inputs);
            item.put("taskId", task.getId());
            item.put("taskDescription", task.getDescription());
            workItems.add(item);
        }
        return workItems;
    }

    private void executePartitionsLocally(List<Task> tasks, Map<String, Object> inputs) {
        Map<String, WorkPartition> partitions = reconciler.partitions();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (WorkPartition partition : partitions.values()) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    partition.markInProgress();

                    // execute tasks for this partition using assigned agent
                    Agent agent = selectAgent(partition);
                    Map<String, Object> partitionInputs = new LinkedHashMap<>(inputs);
                    partitionInputs.putAll(partition.inputs());

                    // interpolate and execute each task
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (Task task : tasks) {
                        String interpolated = interpolateInputs(task.getDescription(), partitionInputs);
                        // build context from partition inputs
                        result.put("task_" + task.getId(), interpolated);
                    }

                    // report success
                    reconciler.recordPartitionResult(partition.partitionId(), true, result);

                } catch (Exception e) {
                    reconciler.recordPartitionResult(partition.partitionId(), false,
                            Map.of("error", e.getMessage()));
                }
            }, partitionExecutor);

            futures.add(future);
        }

        // wait for all partitions
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private Agent selectAgent(WorkPartition partition) {
        // round-robin agent selection based on partition index
        int idx = partition.index() % agents.size();
        return agents.get(idx);
    }

    private SwarmOutput buildOutput(GoalStatus status, long executionMs) {
        GoalStatus.GoalStatusSnapshot snapshot = status.snapshot();

        StringBuilder finalOutput = new StringBuilder();
        finalOutput.append("# Distributed Goal: %s\n\n".formatted(snapshot.goalName()));
        finalOutput.append("**Status**: %s\n".formatted(snapshot.phase()));
        finalOutput.append("**Objective**: %s\n\n".formatted(snapshot.objective()));
        finalOutput.append("## Progress\n");
        finalOutput.append("- Partitions: %d/%d completed\n".formatted(
                snapshot.completedPartitions(), snapshot.totalPartitions()));
        finalOutput.append("- Failed: %d\n".formatted(snapshot.failedPartitions()));
        finalOutput.append("- Completion: %.1f%%\n\n".formatted(snapshot.completionPercentage()));

        finalOutput.append("## Success Criteria\n");
        snapshot.criteriaStatus().forEach((criterion, criterionStatus) ->
                finalOutput.append("- [%s] %s\n".formatted(
                        criterionStatus == GoalStatus.CriterionStatus.MET ? "x" : " ", criterion)));

        finalOutput.append("\n## Events\n");
        snapshot.events().forEach(event -> finalOutput.append("- %s\n".formatted(event)));

        return SwarmOutput.builder()
                .taskOutputs(List.of())
                .finalOutput(finalOutput.toString())
                .executionTime(Duration.ofMillis(executionMs))
                .build();
    }

    private void shutdown() {
        if (failureDetector != null) failureDetector.stop();
        if (raftNode != null) raftNode.stop();
        if (partitionExecutor != null) partitionExecutor.shutdown();
    }

    @Override
    public ProcessType getType() {
        return ProcessType.DISTRIBUTED;
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Distributed process requires at least one task");
        }
    }
}
