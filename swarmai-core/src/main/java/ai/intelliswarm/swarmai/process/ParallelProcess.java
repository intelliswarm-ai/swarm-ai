package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import ai.intelliswarm.swarmai.exception.ProcessExecutionException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Parallel process that runs independent tasks concurrently.
 *
 * Tasks are organized into layers using topological sort:
 *   Layer 0: tasks with no dependencies (run in parallel)
 *   Layer 1: tasks whose dependencies are all in layer 0 (run in parallel after layer 0 completes)
 *   etc.
 *
 * Within each layer, all tasks run concurrently using CompletableFuture.
 * Between layers, the process waits for all tasks in the current layer to complete.
 */
public class ParallelProcess implements Process {

    private static final Logger logger = LoggerFactory.getLogger(ParallelProcess.class);

    private final List<Agent> agents;
    private final ApplicationEventPublisher eventPublisher;
    private final int threadPoolSize;

    public ParallelProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher) {
        this(agents, eventPublisher, Runtime.getRuntime().availableProcessors());
    }

    public ParallelProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher, int threadPoolSize) {
        this.agents = new ArrayList<>(agents);
        this.eventPublisher = eventPublisher;
        this.threadPoolSize = Math.max(2, threadPoolSize);
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Parallel process execution started", swarmId);

        try {
            validateTasks(tasks);

            // Interpolate inputs into task descriptions
            if (inputs != null && !inputs.isEmpty()) {
                for (Task task : tasks) {
                    task.interpolateDescription(inputs);
                }
            }

            // Organize tasks into parallel layers
            List<List<Task>> layers = buildLayers(tasks);
            logger.info("Parallel Process: {} tasks organized into {} layers", tasks.size(), layers.size());
            for (int i = 0; i < layers.size(); i++) {
                List<String> layerTaskIds = layers.get(i).stream()
                        .map(t -> truncate(t.getDescription(), 40))
                        .collect(Collectors.toList());
                logger.info("  Layer {}: {} tasks ({})", i, layers.get(i).size(),
                        String.join(" | ", layerTaskIds));
            }

            List<TaskOutput> allOutputs = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

            try {
                for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
                    List<Task> layer = layers.get(layerIdx);
                    boolean isParallel = layer.size() > 1;

                    if (isParallel) {
                        logger.info("Executing layer {} with {} tasks in PARALLEL", layerIdx, layer.size());
                    } else {
                        logger.info("Executing layer {} with 1 task", layerIdx);
                    }

                    // Build context for each task in this layer (from completed tasks)
                    Map<String, List<TaskOutput>> taskContexts = new HashMap<>();
                    for (Task task : layer) {
                        taskContexts.put(task.getId(), getContextForTask(task, allOutputs));
                    }

                    if (isParallel) {
                        // Run all tasks in this layer concurrently
                        List<CompletableFuture<TaskOutput>> futures = new ArrayList<>();

                        // Snapshot the parent thread's ObservabilityContext for propagation
                        ObservabilityContext.Snapshot parentSnapshot = ObservabilityContext.snapshot();

                        for (Task task : layer) {
                            List<TaskOutput> context = taskContexts.get(task.getId());
                            CompletableFuture<TaskOutput> future = CompletableFuture.supplyAsync(() -> {
                                // Propagate observability context into this child thread
                                ObservabilityContext.Snapshot threadSnapshot = parentSnapshot;
                                threadSnapshot.restore();
                                try {
                                    publishEvent(SwarmEvent.Type.TASK_STARTED,
                                            "Starting parallel task: " + task.getId(), swarmId);

                                    logger.info("[PARALLEL] Starting: {} -> agent: {}",
                                            truncate(task.getDescription(), 60),
                                            task.getAgent() != null ? task.getAgent().getRole() : "unassigned");

                                    TaskOutput output = task.execute(context);

                                    logger.info("[PARALLEL] Completed: {} ({} chars, {} ms)",
                                            truncate(task.getDescription(), 40),
                                            output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                                            output.getExecutionTimeMs());

                                    // Record budget usage (thread-safe via AtomicLong in tracker)
                                    BudgetTracker pbt = inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
                                    String pbsId = inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                                    recordBudgetUsage(pbt, pbsId, output, task.getAgent() != null ? task.getAgent().getModelName() : null);

                                    publishEvent(SwarmEvent.Type.TASK_COMPLETED,
                                            "Completed parallel task: " + task.getId(), swarmId);
                                    return output;
                                } finally {
                                    ObservabilityContext.clear();
                                }
                            }, executor);

                            futures.add(future);
                        }

                        // Wait for all tasks in this layer to complete
                        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                                futures.toArray(new CompletableFuture[0]));

                        try {
                            allDone.get(600, TimeUnit.SECONDS); // 10 min max per layer
                        } catch (TimeoutException e) {
                            throw new ProcessExecutionException(
                                    "Parallel layer " + layerIdx + " timed out", e, ProcessType.PARALLEL, swarmId, null);
                        }

                        // Collect results in order
                        for (CompletableFuture<TaskOutput> future : futures) {
                            allOutputs.add(future.get());
                        }
                    } else {
                        // Single task — run sequentially
                        Task task = layer.get(0);
                        List<TaskOutput> context = taskContexts.get(task.getId());

                        publishEvent(SwarmEvent.Type.TASK_STARTED,
                                "Starting task: " + task.getId(), swarmId);

                        logger.info("Starting: {} -> agent: {}",
                                truncate(task.getDescription(), 60),
                                task.getAgent() != null ? task.getAgent().getRole() : "unassigned");

                        TaskOutput output = task.execute(context);

                        logger.info("Completed: {} ({} chars, {} ms)",
                                truncate(task.getDescription(), 40),
                                output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                                output.getExecutionTimeMs());

                        allOutputs.add(output);

                        BudgetTracker sbt = inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
                        String sbsId = inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                        recordBudgetUsage(sbt, sbsId, output, task.getAgent() != null ? task.getAgent().getModelName() : null);

                        publishEvent(SwarmEvent.Type.TASK_COMPLETED,
                                "Completed task: " + task.getId(), swarmId);
                    }
                }
            } finally {
                executor.shutdown();
            }

            LocalDateTime endTime = LocalDateTime.now();
            String finalOutput = allOutputs.isEmpty() ? "No outputs" :
                    allOutputs.get(allOutputs.size() - 1).getRawOutput();

            SwarmOutput.Builder outputBuilder = SwarmOutput.builder()
                    .swarmId(swarmId)
                    .taskOutputs(allOutputs)
                    .finalOutput(finalOutput)
                    .rawOutput(finalOutput)
                    .startTime(startTime)
                    .endTime(endTime)
                    .successful(allOutputs.stream().allMatch(TaskOutput::isSuccessful))
                    .usageMetric("totalTasks", allOutputs.size())
                    .usageMetric("layers", buildLayers(tasks).size())
                    .usageMetric("parallelTasks",
                            buildLayers(tasks).stream().mapToInt(List::size).max().orElse(0));
            aggregateReactiveMetrics(outputBuilder, allOutputs);
            return outputBuilder.build();

        } catch (ProcessExecutionException e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED,
                    "Parallel process failed: " + e.getMessage(), swarmId);
            throw e;
        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED,
                    "Parallel process failed: " + e.getMessage(), swarmId);
            throw new ProcessExecutionException("Parallel process execution failed", e, ProcessType.PARALLEL, swarmId, null);
        }
    }

    /**
     * Builds execution layers using topological sort.
     * Each layer contains tasks whose dependencies are all in earlier layers.
     * Tasks within the same layer can run in parallel.
     */
    private List<List<Task>> buildLayers(List<Task> tasks) {
        Map<String, Task> taskMap = tasks.stream()
                .collect(Collectors.toMap(Task::getId, t -> t));
        Set<String> allTaskIds = taskMap.keySet();
        Set<String> processed = new HashSet<>();
        List<List<Task>> layers = new ArrayList<>();

        while (processed.size() < tasks.size()) {
            List<Task> currentLayer = new ArrayList<>();

            for (Task task : tasks) {
                if (processed.contains(task.getId())) continue;

                // Check if all dependencies are satisfied (in processed set)
                boolean ready = true;
                for (String depId : task.getDependencyTaskIds()) {
                    if (allTaskIds.contains(depId) && !processed.contains(depId)) {
                        ready = false;
                        break;
                    }
                }

                if (ready) {
                    currentLayer.add(task);
                }
            }

            if (currentLayer.isEmpty()) {
                throw new IllegalStateException("Circular dependency detected — cannot build layers");
            }

            layers.add(currentLayer);
            for (Task task : currentLayer) {
                processed.add(task.getId());
            }
        }

        return layers;
    }

    private List<TaskOutput> getContextForTask(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return allOutputs;
        }
        return allOutputs.stream()
                .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
                .collect(Collectors.toList());
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, swarmId);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.PARALLEL;
    }

    @Override
    public boolean isAsync() {
        return true;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks list cannot be empty");
        }

        Set<String> allTaskIds = tasks.stream()
                .map(Task::getId)
                .collect(Collectors.toSet());

        for (Task task : tasks) {
            for (String depId : task.getDependencyTaskIds()) {
                if (!allTaskIds.contains(depId)) {
                    throw new IllegalArgumentException(
                            "Task " + task.getId() + " depends on non-existent task: " + depId);
                }
            }
            if (task.getAgent() == null) {
                throw new IllegalArgumentException(
                        "Task " + task.getId() + " has no agent assigned");
            }
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
