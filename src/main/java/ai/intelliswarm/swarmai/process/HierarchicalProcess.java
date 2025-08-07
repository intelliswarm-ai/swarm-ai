package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class HierarchicalProcess implements Process {

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
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Hierarchical process execution started");
        
        try {
            validateTasks(tasks);
            
            List<TaskOutput> allOutputs = new ArrayList<>();
            
            // Create coordination task for manager
            Task coordinationTask = createCoordinationTask(tasks, inputs);
            
            publishEvent(SwarmEvent.Type.TASK_STARTED, "Manager coordination task started");
            TaskOutput coordinationOutput = managerAgent.executeTask(coordinationTask, Collections.emptyList());
            allOutputs.add(coordinationOutput);
            
            // Execute delegated tasks
            List<Task> delegatedTasks = parseDelegatedTasks(coordinationOutput, tasks);
            
            for (Task task : delegatedTasks) {
                Agent assignedAgent = selectAgentForTask(task);
                
                publishEvent(SwarmEvent.Type.TASK_STARTED, 
                    "Delegated task started: " + task.getId() + " assigned to: " + assignedAgent.getRole());
                
                // Create a new task with the assigned agent
                Task assignedTask = Task.builder()
                    .description(task.getDescription())
                    .expectedOutput(task.getExpectedOutput())
                    .tools(task.getTools())
                    .agent(assignedAgent)
                    .build();
                
                List<TaskOutput> contextOutputs = getRelevantContext(task, allOutputs);
                TaskOutput output = assignedTask.execute(contextOutputs);
                
                allOutputs.add(output);
                publishEvent(SwarmEvent.Type.TASK_COMPLETED, "Delegated task completed: " + task.getId());
            }
            
            // Final coordination by manager
            Task finalTask = createFinalCoordinationTask(allOutputs);
            TaskOutput finalOutput = managerAgent.executeTask(finalTask, allOutputs);
            allOutputs.add(finalOutput);
            
            LocalDateTime endTime = LocalDateTime.now();
            
            return SwarmOutput.builder()
                .swarmId("hierarchical-" + UUID.randomUUID().toString())
                .taskOutputs(allOutputs)
                .finalOutput(finalOutput.getRawOutput())
                .rawOutput(finalOutput.getRawOutput())
                .startTime(startTime)
                .endTime(endTime)
                .successful(allOutputs.stream().allMatch(TaskOutput::isSuccessful))
                .usageMetric("totalTasks", allOutputs.size())
                .usageMetric("delegatedTasks", delegatedTasks.size())
                .usageMetric("managerTasks", 2) // coordination + final
                .build();
                
        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Hierarchical process failed: " + e.getMessage());
            throw new RuntimeException("Hierarchical process execution failed", e);
        }
    }

    private Task createCoordinationTask(List<Task> tasks, Map<String, Object> inputs) {
        StringBuilder description = new StringBuilder();
        description.append("You are the manager coordinating the following tasks:\n\n");
        
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            description.append(String.format("%d. %s\n", i + 1, task.getDescription()));
            if (task.getExpectedOutput() != null) {
                description.append("   Expected Output: ").append(task.getExpectedOutput()).append("\n");
            }
        }
        
        description.append("\nAvailable worker agents:\n");
        for (Agent agent : workerAgents) {
            description.append("- ").append(agent.getRole()).append(": ").append(agent.getGoal()).append("\n");
        }
        
        description.append("\nCreate a task delegation plan. For each task, specify which agent should handle it and any specific instructions.");
        
        if (!inputs.isEmpty()) {
            description.append("\nInput context: ").append(inputs.toString());
        }
        
        return Task.builder()
            .description(description.toString())
            .expectedOutput("A delegation plan mapping tasks to agents with specific instructions")
            .agent(managerAgent)
            .build();
    }

    private List<Task> parseDelegatedTasks(TaskOutput coordinationOutput, List<Task> originalTasks) {
        // This is a simplified parsing - in a real implementation, you might use
        // more sophisticated NLP or structured output parsing
        
        List<Task> delegatedTasks = new ArrayList<>(originalTasks);
        
        // For now, just assign agents in round-robin fashion
        for (int i = 0; i < delegatedTasks.size(); i++) {
            Agent assignedAgent = workerAgents.get(i % workerAgents.size());
            // Note: Task is immutable, so we'll handle agent assignment in execution
        }
        
        return delegatedTasks;
    }

    private Agent selectAgentForTask(Task task) {
        // Simple selection strategy - could be enhanced with ML/matching algorithms
        
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
        
        // Default: round-robin assignment
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
        
        // Simple keyword matching - could be enhanced with semantic similarity
        String[] keywords = taskDescription.split("\\s+");
        for (String keyword : keywords) {
            if (agentRole.contains(keyword) || agentGoal.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }

    private List<TaskOutput> getRelevantContext(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return allOutputs; // All available context
        }
        
        return allOutputs.stream()
            .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
            .collect(Collectors.toList());
    }

    private Task createFinalCoordinationTask(List<TaskOutput> allOutputs) {
        StringBuilder description = new StringBuilder();
        description.append("As the manager, review all completed tasks and provide a final coordinated output:\n\n");
        
        for (TaskOutput output : allOutputs) {
            description.append("Task: ").append(output.getDescription()).append("\n");
            description.append("Result: ").append(output.getSummary()).append("\n\n");
        }
        
        description.append("Provide a comprehensive summary and final output that incorporates all task results.");
        
        return Task.builder()
            .description(description.toString())
            .expectedOutput("A comprehensive final output incorporating all task results")
            .agent(managerAgent)
            .build();
    }

    private void publishEvent(SwarmEvent.Type type, String message) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, null);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.HIERARCHICAL;
    }

    @Override
    public boolean isAsync() {
        return false; // Hierarchical process is primarily synchronous
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
        
        // Validate that manager agent allows delegation
        if (!managerAgent.isAllowDelegation()) {
            throw new IllegalArgumentException("Manager agent must allow delegation for hierarchical process");
        }
    }
}