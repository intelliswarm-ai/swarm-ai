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

    /** Default timeout per task in a parallel layer (seconds). Total layer timeout = this * layer size / concurrency. */
    private static final int DEFAULT_PER_TASK_TIMEOUT_SECONDS = 600; // 10 minutes per task

    private final List<Agent> agents;
    private final ApplicationEventPublisher eventPublisher;
    private final int threadPoolSize;
    private final int perTaskTimeoutSeconds;
    private final int maxConcurrentLlmCalls;

    public ParallelProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher) {
        this(agents, eventPublisher, Runtime.getRuntime().availableProcessors(),
                DEFAULT_PER_TASK_TIMEOUT_SECONDS, 0);
    }

    public ParallelProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher, int threadPoolSize) {
        this(agents, eventPublisher, threadPoolSize, DEFAULT_PER_TASK_TIMEOUT_SECONDS, 0);
    }

    /**
     * @param perTaskTimeoutSeconds timeout per task in a layer; total layer timeout = this * layer size
     * @param maxConcurrentLlmCalls max concurrent LLM calls (0 = unlimited, uses threadPoolSize)
     */
    public ParallelProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher,
                           int threadPoolSize, int perTaskTimeoutSeconds, int maxConcurrentLlmCalls) {
        this.agents = new ArrayList<>(agents);
        this.eventPublisher = eventPublisher;
        this.threadPoolSize = Math.max(2, threadPoolSize);
        this.perTaskTimeoutSeconds = perTaskTimeoutSeconds > 0 ? perTaskTimeoutSeconds : DEFAULT_PER_TASK_TIMEOUT_SECONDS;
        // Default concurrency to 2 (not threadPoolSize) — most LLM providers serialize requests
        // internally, so high concurrency just causes timeouts without speedup
        this.maxConcurrentLlmCalls = maxConcurrentLlmCalls > 0 ? maxConcurrentLlmCalls : 2;
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
            // Limit concurrency to avoid overwhelming single-instance LLM providers (e.g., Ollama)
            int effectiveConcurrency = Math.min(threadPoolSize, maxConcurrentLlmCalls);
            ExecutorService executor = Executors.newFixedThreadPool(effectiveConcurrency);

            try {
                for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
                    List<Task> layer = layers.get(layerIdx);
                    boolean isParallel = layer.size() > 1;

                    if (isParallel) {
                        logger.info("Executing layer {} with {} tasks in PARALLEL (concurrency: {})",
                                layerIdx, layer.size(), effectiveConcurrency);
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

                        // Wait for all tasks in this layer to complete (don't throw on individual failures)
                        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                                futures.toArray(new CompletableFuture[0]));

                        // Scale timeout with layer size: if tasks are queued behind a concurrency
                        // limit, they run in batches, so total time = (tasks / concurrency) * perTaskTimeout
                        int batches = (int) Math.ceil((double) layer.size() / effectiveConcurrency);
                        long layerTimeoutSeconds = (long) batches * perTaskTimeoutSeconds;
                        logger.info("Layer {} timeout: {} seconds ({} batches x {} sec/task)",
                                layerIdx, layerTimeoutSeconds, batches, perTaskTimeoutSeconds);

                        try {
                            allDone.get(layerTimeoutSeconds, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            logger.error("Parallel layer {} timed out after {} seconds ({} tasks, {} concurrent)",
                                    layerIdx, layerTimeoutSeconds, layer.size(), effectiveConcurrency);
                            throw new ProcessExecutionException(
                                    "Parallel layer " + layerIdx + " timed out after " + layerTimeoutSeconds + "s " +
                                    "(" + layer.size() + " tasks, " + effectiveConcurrency + " concurrent)",
                                    e, ProcessType.PARALLEL, swarmId, null);
                        } catch (ExecutionException e) {
                            // At least one task failed — collect partial results below
                            logger.warn("One or more parallel tasks in layer {} failed; collecting partial results", layerIdx);
                        }

                        // Collect results in order, tolerating individual task failures
                        List<String> failedTasks = new ArrayList<>();

                        for (int i = 0; i < futures.size(); i++) {
                            try {
                                allOutputs.add(futures.get(i).get());
                            } catch (Exception e) {
                                String taskDesc = i < layer.size() ? truncate(layer.get(i).getDescription(), 60) : "unknown";
                                failedTasks.add(taskDesc);
                                logger.warn("Parallel task failed (continuing with partial results): {}",
                                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                            }
                        }

                        if (allOutputs.isEmpty() && !failedTasks.isEmpty()) {
                            throw new ProcessExecutionException(
                                    "Parallel layer " + layerIdx + " failed: all " + failedTasks.size() + " tasks failed",
                                    null, ProcessType.PARALLEL, swarmId, null);
                        }

                        if (!failedTasks.isEmpty()) {
                            logger.warn("Parallel layer {}: {}/{} tasks succeeded, {}/{} failed",
                                    layerIdx, futures.size() - failedTasks.size(), futures.size(),
                                    failedTasks.size(), futures.size());
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
                            taskLabel(task) + " depends on non-existent task id=" + depId);
                }
            }
            if (task.getAgent() == null) {
                throw new IllegalArgumentException(
                        "Task " + task.getId() + " has no agent assigned");
            }
        }
    }

    private String taskLabel(Task task) {
        String description = task.getDescription();
        String descriptionLabel = (description == null || description.isBlank())
                ? ""
                : " \"" + truncate(description, 60) + "\"";
        String agentRoleLabel = task.getAgent() == null || task.getAgent().getRole() == null
                ? ""
                : " [agentRole=" + task.getAgent().getRole() + "]";
        return "Task" + descriptionLabel + " (id=" + task.getId() + ")" + agentRoleLabel;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
