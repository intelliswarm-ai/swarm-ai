package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
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
import java.util.stream.Collectors;

public class HierarchicalProcess implements Process {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalProcess.class);

    private final List<Agent> workerAgents;
    private final Agent managerAgent;
    private final ApplicationEventPublisher eventPublisher;

    public HierarchicalProcess(List<Agent> agents, Agent managerAgent, ApplicationEventPublisher eventPublisher) {
        this.workerAgents = new ArrayList<>(agents);
        this.managerAgent = Objects.requireNonNull(managerAgent, "Manager agent is required");
        this.eventPublisher = eventPublisher;

        // Remove manager from workers if present
        this.workerAgents.removeIf(agent -> agent.equals(managerAgent));
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Hierarchical process execution started", swarmId);

        try {
            validateTasks(tasks);

            // Interpolate inputs into task descriptions
            if (inputs != null && !inputs.isEmpty()) {
                for (Task task : tasks) {
                    task.interpolateDescription(inputs);
                }
            }

            List<TaskOutput> allOutputs = new ArrayList<>();

            // Step 1: Manager creates an execution plan/guidance
            Task planningTask = createPlanningTask(tasks, inputs);
            publishEvent(SwarmEvent.Type.TASK_STARTED, "Manager planning task started", swarmId);
            TaskOutput planOutput = managerAgent.executeTask(planningTask, Collections.emptyList());
            allOutputs.add(planOutput);

            logger.info("Manager created execution plan ({} chars)",
                    planOutput.getRawOutput() != null ? planOutput.getRawOutput().length() : 0);

            // Step 2: Execute worker tasks in dependency order with manager's plan as context
            List<Task> orderedTasks = orderTasks(tasks);

            logger.info("Hierarchical Process: Executing {} worker tasks", orderedTasks.size());

            for (Task task : orderedTasks) {
                // Update observability context
                ObservabilityContext ctx = ObservabilityContext.currentOrNull();
                if (ctx != null) {
                    ctx.withTaskId(task.getId());
                }

                // Select the best agent for this task
                Agent assignedAgent = task.getAgent() != null ? task.getAgent() : selectAgentForTask(task);

                if (ctx != null) {
                    ctx.withAgentId(assignedAgent.getId());
                }

                logger.info("Starting task: {} -> assigned to: {} (tools: {})",
                        truncateForLog(task.getDescription(), 80),
                        assignedAgent.getRole(),
                        assignedAgent.getTools().stream()
                                .map(t -> t.getFunctionName())
                                .collect(Collectors.joining(", ")));

                publishEvent(SwarmEvent.Type.TASK_STARTED,
                    "Delegated task started: " + task.getId() + " assigned to: " + assignedAgent.getRole(), swarmId);

                // Build context: manager's plan + outputs from dependency tasks
                List<TaskOutput> contextOutputs = new ArrayList<>();
                contextOutputs.add(planOutput); // Always include manager's guidance
                contextOutputs.addAll(getRelevantContext(task, allOutputs));

                // If the task already has an agent, execute directly; otherwise create assigned task
                TaskOutput output;
                if (task.getAgent() != null) {
                    output = task.execute(contextOutputs);
                } else {
                    // Create a new task with the assigned agent for tasks without one
                    Task assignedTask = Task.builder()
                        .description(task.getDescription())
                        .expectedOutput(task.getExpectedOutput())
                        .tools(task.getTools())
                        .agent(assignedAgent)
                        .build();
                    output = assignedTask.execute(contextOutputs);
                }

                logger.info("Task completed: {} (output: {} chars, exec time: {} ms)",
                        truncateForLog(task.getDescription(), 50),
                        output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                        output.getExecutionTimeMs());

                allOutputs.add(output);
                publishEvent(SwarmEvent.Type.TASK_COMPLETED, "Delegated task completed: " + task.getId(), swarmId);
            }

            // Step 3: Manager synthesizes all results into final output
            // Don't pass context separately — the synthesis task description already contains worker outputs
            // This avoids duplication that would eat up the context window
            Task synthesisTask = createSynthesisTask(allOutputs);
            TaskOutput finalOutput = managerAgent.executeTask(synthesisTask, Collections.emptyList());
            allOutputs.add(finalOutput);

            LocalDateTime endTime = LocalDateTime.now();

            return SwarmOutput.builder()
                .swarmId(swarmId)
                .taskOutputs(allOutputs)
                .finalOutput(finalOutput.getRawOutput())
                .rawOutput(finalOutput.getRawOutput())
                .startTime(startTime)
                .endTime(endTime)
                .successful(allOutputs.stream().allMatch(TaskOutput::isSuccessful))
                .usageMetric("totalTasks", allOutputs.size())
                .usageMetric("workerTasks", orderedTasks.size())
                .usageMetric("managerTasks", 2)
                .build();

        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Hierarchical process failed: " + e.getMessage(), swarmId);
            throw new RuntimeException("Hierarchical process execution failed", e);
        }
    }

    private Task createPlanningTask(List<Task> tasks, Map<String, Object> inputs) {
        StringBuilder description = new StringBuilder();
        description.append("You are the manager coordinating the following tasks.\n");
        description.append("Review each task and provide strategic guidance, priorities, and any specific instructions for the agents executing them.\n\n");

        description.append("Tasks to coordinate:\n");
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            // Truncate task descriptions for the manager — it needs summaries, not full evidence
            String taskDesc = task.getDescription();
            if (taskDesc.length() > 500) {
                taskDesc = taskDesc.substring(0, 500) + "...";
            }
            description.append(String.format("%d. %s\n", i + 1, taskDesc));
            if (task.getExpectedOutput() != null) {
                description.append("   Expected Output: ").append(task.getExpectedOutput()).append("\n");
            }
            if (task.getAgent() != null) {
                description.append("   Assigned Agent: ").append(task.getAgent().getRole()).append("\n");
            }
        }

        description.append("\nAvailable worker agents:\n");
        for (Agent agent : workerAgents) {
            description.append("- ").append(agent.getRole()).append(": ").append(agent.getGoal());
            if (!agent.getTools().isEmpty()) {
                description.append(" (tools: ")
                        .append(agent.getTools().stream().map(t -> t.getFunctionName()).collect(Collectors.joining(", ")))
                        .append(")");
            }
            description.append("\n");
        }

        if (inputs != null && !inputs.isEmpty()) {
            description.append("\nInput context:\n");
            inputs.forEach((k, v) -> description.append("- ").append(k).append(": ").append(v).append("\n"));
        }

        description.append("\nProvide an execution plan with specific guidance for each task.");

        return Task.builder()
            .description(description.toString())
            .expectedOutput("An execution plan with strategic guidance for each task and agent")
            .agent(managerAgent)
            .build();
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

    private Agent selectAgentForTask(Task task) {
        // Look for agent with relevant tools
        for (Agent agent : workerAgents) {
            if (hasRelevantTools(agent, task)) {
                return agent;
            }
        }

        // Look for agent with relevant role/goal keywords
        String taskDescription = task.getDescription().toLowerCase();
        for (Agent agent : workerAgents) {
            if (hasRelevantExpertise(agent, taskDescription)) {
                return agent;
            }
        }

        // Default: hash-based assignment for consistency
        return workerAgents.get(Math.abs(task.getId().hashCode()) % workerAgents.size());
    }

    private boolean hasRelevantTools(Agent agent, Task task) {
        Set<String> taskToolNames = task.getTools().stream()
            .map(tool -> tool.getFunctionName().toLowerCase())
            .collect(Collectors.toSet());

        Set<String> agentToolNames = agent.getTools().stream()
            .map(tool -> tool.getFunctionName().toLowerCase())
            .collect(Collectors.toSet());

        return !Collections.disjoint(taskToolNames, agentToolNames);
    }

    private boolean hasRelevantExpertise(Agent agent, String taskDescription) {
        String agentRole = agent.getRole().toLowerCase();
        String agentGoal = agent.getGoal().toLowerCase();

        String[] keywords = taskDescription.split("\\s+");
        for (String keyword : keywords) {
            if (keyword.length() > 3 && (agentRole.contains(keyword) || agentGoal.contains(keyword))) {
                return true;
            }
        }

        return false;
    }

    private List<TaskOutput> getRelevantContext(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            // Return worker task outputs only (skip the planning output at index 0)
            return allOutputs.size() > 1 ? allOutputs.subList(1, allOutputs.size()) : Collections.emptyList();
        }

        return allOutputs.stream()
            .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
            .collect(Collectors.toList());
    }

    private Task createSynthesisTask(List<TaskOutput> allOutputs) {
        StringBuilder description = new StringBuilder();
        description.append("As the manager, review all completed task results and provide a final coordinated output.\n\n");
        description.append("IMPORTANT: Your final output MUST be based on the actual task results provided below. ");
        description.append("Do NOT generate new information or make up data. Use ONLY the information from the completed tasks.\n\n");

        // Skip the first output (planning task) and show worker results
        int taskNum = 1;
        for (int i = 1; i < allOutputs.size(); i++) {
            TaskOutput output = allOutputs.get(i);
            description.append("=== TASK ").append(taskNum++).append(" ===\n");
            if (output.getDescription() != null) {
                description.append("Description: ").append(output.getDescription()).append("\n");
            }
            description.append("Agent: ").append(output.getAgentId()).append("\n");
            String rawOutput = output.getRawOutput();
            if (rawOutput != null && rawOutput.length() > 3000) {
                description.append("Result:\n").append(rawOutput, 0, 3000).append("...[truncated]\n\n");
            } else {
                description.append("Result:\n").append(rawOutput != null ? rawOutput : "No output").append("\n\n");
            }
        }

        description.append("Based on the above task results, provide a comprehensive final output that:\n");
        description.append("1. Synthesizes all the findings from the completed tasks\n");
        description.append("2. Presents the information in a clear, organized manner\n");
        description.append("3. Includes all relevant data and insights from the task outputs\n");
        description.append("4. Does NOT introduce new information not found in the task results\n");

        return Task.builder()
            .description(description.toString())
            .expectedOutput("A comprehensive final output that synthesizes all task results accurately")
            .agent(managerAgent)
            .build();
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, swarmId);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.HIERARCHICAL;
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

        if (workerAgents.isEmpty()) {
            throw new IllegalArgumentException("At least one worker agent is required for hierarchical process");
        }

        if (managerAgent == null) {
            throw new IllegalArgumentException("Manager agent is required for hierarchical process");
        }
    }

    private String truncateForLog(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
