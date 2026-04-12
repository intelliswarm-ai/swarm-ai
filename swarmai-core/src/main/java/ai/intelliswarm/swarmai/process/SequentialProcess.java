package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.exception.ProcessExecutionException;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SequentialProcess implements Process {

    private static final Logger logger = LoggerFactory.getLogger(SequentialProcess.class);

    private final List<Agent> agents;
    private final ApplicationEventPublisher eventPublisher;

    public SequentialProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher) {
        this.agents = new ArrayList<>(agents);
        this.eventPublisher = eventPublisher;
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Sequential process execution started", swarmId);

        try {
            validateTasks(tasks);

            // Interpolate inputs into task descriptions
            interpolateTaskDescriptions(tasks, inputs);

            List<Task> orderedTasks = orderTasks(tasks);
            List<TaskOutput> allOutputs = new ArrayList<>();
            Set<String> completedTaskIds = new HashSet<>();

            logger.info("Sequential Process: Executing {} tasks", orderedTasks.size());

            for (Task task : orderedTasks) {
                // Update observability context
                ObservabilityContext ctx = ObservabilityContext.currentOrNull();
                if (ctx != null) {
                    ctx.withTaskId(task.getId());
                    if (task.getAgent() != null) {
                        ctx.withAgentId(task.getAgent().getId());
                    }
                }

                Agent agent = task.getAgent();
                String agentInfo = agent != null ?
                        String.format("%s (tools: %s)", agent.getRole(),
                                agent.getTools().stream().map(t -> t.getFunctionName()).collect(Collectors.joining(", ")))
                        : "no agent assigned";

                logger.info("Starting task: {} -> agent: {}",
                        truncateForLog(task.getDescription(), 80), agentInfo);

                publishEvent(SwarmEvent.Type.TASK_STARTED, "Starting task: " + task.getId(), swarmId);

                List<TaskOutput> contextOutputs = getContextForTask(task, allOutputs);
                TaskOutput output;

                if (task.isAsyncExecution() && isLastInSequence(task, orderedTasks)) {
                    output = executeAsyncTask(task, contextOutputs);
                } else {
                    output = task.execute(contextOutputs);
                }

                logger.info("Task completed: {} (output: {} chars, exec time: {} ms)",
                        truncateForLog(task.getDescription(), 50),
                        output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                        output.getExecutionTimeMs());

                allOutputs.add(output);
                completedTaskIds.add(task.getId());

                // Record budget usage from this task
                BudgetTracker tracker = inputs.get("__budgetTracker") instanceof BudgetTracker bt ? bt : null;
                String budgetSwarmId = inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                String modelName = agent != null ? agent.getModelName() : null;
                recordBudgetUsage(tracker, budgetSwarmId, output, modelName);

                publishEvent(SwarmEvent.Type.TASK_COMPLETED, "Completed task: " + task.getId(), swarmId);

                if (!output.isSuccessful()) {
                    publishEvent(SwarmEvent.Type.TASK_FAILED, "Task failed: " + task.getId(), swarmId);
                }
            }

            LocalDateTime endTime = LocalDateTime.now();
            String finalOutput = generateFinalOutput(allOutputs);

            SwarmOutput.Builder outputBuilder = SwarmOutput.builder()
                .swarmId(swarmId)
                .taskOutputs(allOutputs)
                .finalOutput(finalOutput)
                .rawOutput(finalOutput)
                .startTime(startTime)
                .endTime(endTime)
                .successful(allOutputs.stream().allMatch(TaskOutput::isSuccessful))
                .usageMetric("totalTasks", allOutputs.size())
                .usageMetric("successfulTasks", allOutputs.stream().mapToInt(o -> o.isSuccessful() ? 1 : 0).sum());
            aggregateReactiveMetrics(outputBuilder, allOutputs);
            return outputBuilder.build();

        } catch (ai.intelliswarm.swarmai.budget.BudgetExceededException e) {
            // Budget exceptions propagate directly — the Swarm catches these
            // specifically to set BUDGET_EXCEEDED status. Don't wrap them.
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Sequential process stopped: budget exceeded", swarmId);
            throw e;
        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Sequential process failed: " + e.getMessage(), swarmId);
            throw new ProcessExecutionException("Sequential process execution failed", e, ProcessType.SEQUENTIAL);
        }
    }

    private void interpolateTaskDescriptions(List<Task> tasks, Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) return;
        for (Task task : tasks) {
            task.interpolateDescription(inputs);
        }
    }

    private List<Task> orderTasks(List<Task> tasks) {
        List<Task> ordered = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Set<String> queued = new HashSet<>();

        Queue<Task> queue = new LinkedList<>();

        for (Task task : tasks) {
            if (task.getDependencyTaskIds().isEmpty()) {
                queue.offer(task);
                queued.add(task.getId());
            }
        }

        while (!queue.isEmpty()) {
            Task current = queue.poll();
            ordered.add(current);
            processed.add(current.getId());

            for (Task task : tasks) {
                if (!queued.contains(task.getId()) &&
                    processed.containsAll(task.getDependencyTaskIds())) {
                    queue.offer(task);
                    queued.add(task.getId());
                }
            }
        }

        if (ordered.size() != tasks.size()) {
            throw new IllegalStateException("Circular dependency detected in tasks");
        }

        return ordered;
    }

    private List<TaskOutput> getContextForTask(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return allOutputs;
        }

        return allOutputs.stream()
            .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
            .collect(Collectors.toList());
    }

    private boolean isLastInSequence(Task task, List<Task> orderedTasks) {
        return orderedTasks.indexOf(task) == orderedTasks.size() - 1;
    }

    private TaskOutput executeAsyncTask(Task task, List<TaskOutput> contextOutputs) {
        try {
            CompletableFuture<TaskOutput> future = task.executeAsync(contextOutputs);
            return future.get();
        } catch (Exception e) {
            throw new ProcessExecutionException("Async task execution failed: " + task.getId(), e, ProcessType.SEQUENTIAL);
        }
    }

    private String generateFinalOutput(List<TaskOutput> outputs) {
        if (outputs.isEmpty()) {
            return "No outputs generated";
        }

        TaskOutput lastOutput = outputs.get(outputs.size() - 1);
        return lastOutput.getRawOutput();
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, swarmId);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.SEQUENTIAL;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks list cannot be empty");
        }

        Set<String> allTaskIds = tasks.stream()
            .map(Task::getId)
            .collect(Collectors.toSet());

        java.util.Map<String, Task> tasksById = tasks.stream()
            .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));
        for (Task task : tasks) {
            for (String depId : task.getDependencyTaskIds()) {
                if (!allTaskIds.contains(depId)) {
                    throw new IllegalArgumentException(describeDependencyError(task, depId, tasksById));
                }
            }
        }

        List<Task> tasksWithoutAgents = tasks.stream()
            .filter(task -> task.getAgent() == null)
            .collect(Collectors.toList());

        if (!tasksWithoutAgents.isEmpty() && agents.isEmpty()) {
            throw new IllegalArgumentException("Tasks without agents found, but no agents available");
        }
    }

    private String truncateForLog(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Build a developer-friendly error message for an invalid task dependency.
     * Includes the description and role of the offending task (not just the UUID)
     * and suggests the most common root cause (cross-swarm .dependsOn()).
     */
    static String describeDependencyError(Task task, String depId, java.util.Map<String, Task> tasksById) {
        String taskDesc = task.getDescription() != null && !task.getDescription().isBlank()
            ? "\"" + truncate(task.getDescription(), 60) + "\"" : "(no description)";
        String taskRole = task.getAgent() != null ? task.getAgent().getRole() : "unassigned";
        Task dep = tasksById.get(depId);
        String depDesc = dep != null && dep.getDescription() != null
            ? "\"" + truncate(dep.getDescription(), 60) + "\"" : "(unknown)";
        return String.format(
            "Task %s (id=%s, agent=%s) depends on non-existent task %s (id=%s). " +
            "The dependency is not in this swarm. If the dependency is in a different " +
            "swarm, remove the .dependsOn() call and pass data via inputs or shared memory instead.",
            taskDesc, shortId(task.getId()), taskRole, depDesc, shortId(depId));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String shortId(String id) {
        if (id == null) return "null";
        return id.length() > 8 ? id.substring(0, 8) + "…" : id;
    }
}
