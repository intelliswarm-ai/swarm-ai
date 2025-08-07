package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SequentialProcess implements Process {

    private final List<Agent> agents;
    private final ApplicationEventPublisher eventPublisher;

    public SequentialProcess(List<Agent> agents, ApplicationEventPublisher eventPublisher) {
        this.agents = new ArrayList<>(agents);
        this.eventPublisher = eventPublisher;
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Sequential process execution started");
        
        try {
            validateTasks(tasks);
            
            List<Task> orderedTasks = orderTasks(tasks);
            List<TaskOutput> allOutputs = new ArrayList<>();
            Set<String> completedTaskIds = new HashSet<>();
            
            for (Task task : orderedTasks) {
                publishEvent(SwarmEvent.Type.TASK_STARTED, "Starting task: " + task.getId());
                
                List<TaskOutput> contextOutputs = getContextForTask(task, allOutputs);
                TaskOutput output;
                
                if (task.isAsyncExecution() && isLastInSequence(task, orderedTasks)) {
                    output = executeAsyncTask(task, contextOutputs);
                } else {
                    output = task.execute(contextOutputs);
                }
                
                allOutputs.add(output);
                completedTaskIds.add(task.getId());
                
                publishEvent(SwarmEvent.Type.TASK_COMPLETED, "Completed task: " + task.getId());
                
                if (!output.isSuccessful()) {
                    publishEvent(SwarmEvent.Type.TASK_FAILED, "Task failed: " + task.getId());
                }
            }
            
            LocalDateTime endTime = LocalDateTime.now();
            String finalOutput = generateFinalOutput(allOutputs);
            
            return SwarmOutput.builder()
                .swarmId("sequential-" + UUID.randomUUID().toString())
                .taskOutputs(allOutputs)
                .finalOutput(finalOutput)
                .rawOutput(finalOutput)
                .startTime(startTime)
                .endTime(endTime)
                .successful(allOutputs.stream().allMatch(TaskOutput::isSuccessful))
                .usageMetric("totalTasks", allOutputs.size())
                .usageMetric("successfulTasks", allOutputs.stream().mapToInt(o -> o.isSuccessful() ? 1 : 0).sum())
                .build();
                
        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Sequential process failed: " + e.getMessage());
            throw new RuntimeException("Sequential process execution failed", e);
        }
    }

    private List<Task> orderTasks(List<Task> tasks) {
        List<Task> ordered = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Map<String, Task> taskMap = tasks.stream()
            .collect(Collectors.toMap(Task::getId, task -> task));

        Queue<Task> queue = new LinkedList<>(tasks.stream()
            .filter(task -> task.getDependencyTaskIds().isEmpty())
            .collect(Collectors.toList()));

        while (!queue.isEmpty()) {
            Task current = queue.poll();
            ordered.add(current);
            processed.add(current.getId());

            tasks.stream()
                .filter(task -> !processed.contains(task.getId()))
                .filter(task -> processed.containsAll(task.getDependencyTaskIds()))
                .forEach(queue::offer);
        }

        if (ordered.size() != tasks.size()) {
            throw new IllegalStateException("Circular dependency detected in tasks");
        }

        return ordered;
    }

    private List<TaskOutput> getContextForTask(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return allOutputs; // All previous outputs as context
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
            return future.get(); // Wait for completion
        } catch (Exception e) {
            throw new RuntimeException("Async task execution failed: " + task.getId(), e);
        }
    }

    private String generateFinalOutput(List<TaskOutput> outputs) {
        if (outputs.isEmpty()) {
            return "No outputs generated";
        }
        
        TaskOutput lastOutput = outputs.get(outputs.size() - 1);
        return lastOutput.getRawOutput();
    }

    private void publishEvent(SwarmEvent.Type type, String message) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, null);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.SEQUENTIAL;
    }

    @Override
    public boolean isAsync() {
        return false; // Sequential process is primarily synchronous
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks list cannot be empty");
        }

        // Check for circular dependencies
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
        }

        // Validate that all tasks have agents (if required)
        List<Task> tasksWithoutAgents = tasks.stream()
            .filter(task -> task.getAgent() == null)
            .collect(Collectors.toList());

        if (!tasksWithoutAgents.isEmpty() && agents.isEmpty()) {
            throw new IllegalArgumentException("Tasks without agents found, but no agents available");
        }
    }
}