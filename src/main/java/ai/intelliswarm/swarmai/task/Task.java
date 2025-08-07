package ai.intelliswarm.swarmai.task;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class Task {

    @NotNull
    private final String id;
    
    @NotBlank
    private final String description;
    
    private final String expectedOutput;
    private final List<BaseTool> tools;
    private final Agent agent;
    private final List<String> dependencyTaskIds;
    private final boolean asyncExecution;
    private final OutputFormat outputFormat;
    private final String outputFile;
    private final Integer maxExecutionTime;
    private final Predicate<String> condition;
    private final Map<String, Object> context;
    
    @JsonIgnore
    private TaskOutput output;
    private TaskStatus status = TaskStatus.PENDING;
    private final LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String failureReason;

    private Task(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.description = builder.description;
        this.expectedOutput = builder.expectedOutput;
        this.tools = new ArrayList<>(builder.tools);
        this.agent = builder.agent;
        this.dependencyTaskIds = new ArrayList<>(builder.dependencyTaskIds);
        this.asyncExecution = builder.asyncExecution;
        this.outputFormat = builder.outputFormat;
        this.outputFile = builder.outputFile;
        this.maxExecutionTime = builder.maxExecutionTime;
        this.condition = builder.condition;
        this.context = new HashMap<>(builder.context);
        this.createdAt = LocalDateTime.now();
    }

    public CompletableFuture<TaskOutput> executeAsync(List<TaskOutput> contextOutputs) {
        return CompletableFuture.supplyAsync(() -> execute(contextOutputs));
    }

    public TaskOutput execute(List<TaskOutput> contextOutputs) {
        if (status != TaskStatus.PENDING) {
            throw new IllegalStateException("Task " + id + " has already been executed or is in progress");
        }
        
        setStatus(TaskStatus.RUNNING);
        startedAt = LocalDateTime.now();
        
        try {
            if (condition != null) {
                String contextString = buildContextString(contextOutputs);
                if (!condition.test(contextString)) {
                    setStatus(TaskStatus.SKIPPED);
                    return createSkippedOutput();
                }
            }
            
            if (agent == null) {
                throw new IllegalStateException("Agent is required for task execution");
            }
            
            List<TaskOutput> filteredContext = filterContextByDependencies(contextOutputs);
            TaskOutput result = agent.executeTask(this, filteredContext);
            
            this.output = result;
            setStatus(TaskStatus.COMPLETED);
            completedAt = LocalDateTime.now();
            
            if (outputFile != null) {
                saveOutputToFile(result);
            }
            
            return result;
            
        } catch (Exception e) {
            setStatus(TaskStatus.FAILED);
            failureReason = e.getMessage();
            completedAt = LocalDateTime.now();
            throw new RuntimeException("Task execution failed: " + id, e);
        }
    }

    private List<TaskOutput> filterContextByDependencies(List<TaskOutput> contextOutputs) {
        if (dependencyTaskIds.isEmpty()) {
            return contextOutputs;
        }
        
        return contextOutputs.stream()
            .filter(output -> dependencyTaskIds.contains(output.getTaskId()))
            .toList();
    }

    private String buildContextString(List<TaskOutput> contextOutputs) {
        return contextOutputs.stream()
            .map(output -> output.getRawOutput())
            .reduce("", (acc, output) -> acc + " " + output)
            .trim();
    }

    private TaskOutput createSkippedOutput() {
        return TaskOutput.builder()
            .taskId(id)
            .agentId(agent != null ? agent.getId() : null)
            .rawOutput("Task skipped due to condition")
            .description(description)
            .summary("Task was skipped")
            .build();
    }

    private void saveOutputToFile(TaskOutput result) {
        // Implementation for saving output to file
        // This could integrate with Spring's file handling utilities
    }

    private void setStatus(TaskStatus newStatus) {
        this.status = newStatus;
    }

    public boolean isReady(Set<String> completedTaskIds) {
        return dependencyTaskIds.isEmpty() || completedTaskIds.containsAll(dependencyTaskIds);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String description;
        private String expectedOutput;
        private List<BaseTool> tools = new ArrayList<>();
        private Agent agent;
        private List<String> dependencyTaskIds = new ArrayList<>();
        private boolean asyncExecution = false;
        private OutputFormat outputFormat = OutputFormat.TEXT;
        private String outputFile;
        private Integer maxExecutionTime;
        private Predicate<String> condition;
        private Map<String, Object> context = new HashMap<>();

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder expectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
            return this;
        }

        public Builder tools(List<BaseTool> tools) {
            this.tools = new ArrayList<>(tools);
            return this;
        }

        public Builder tool(BaseTool tool) {
            this.tools.add(tool);
            return this;
        }

        public Builder agent(Agent agent) {
            this.agent = agent;
            return this;
        }

        public Builder dependsOn(String taskId) {
            this.dependencyTaskIds.add(taskId);
            return this;
        }

        public Builder dependsOn(Task task) {
            this.dependencyTaskIds.add(task.getId());
            return this;
        }

        public Builder asyncExecution(boolean asyncExecution) {
            this.asyncExecution = asyncExecution;
            return this;
        }

        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public Builder outputFile(String outputFile) {
            this.outputFile = outputFile;
            return this;
        }

        public Builder maxExecutionTime(Integer maxExecutionTime) {
            this.maxExecutionTime = maxExecutionTime;
            return this;
        }

        public Builder condition(Predicate<String> condition) {
            this.condition = condition;
            return this;
        }

        public Builder context(String key, Object value) {
            this.context.put(key, value);
            return this;
        }

        public Task build() {
            Objects.requireNonNull(description, "Description cannot be null");
            
            return new Task(this);
        }
    }

    // Getters
    public String getId() { return id; }
    public String getDescription() { return description; }
    public String getExpectedOutput() { return expectedOutput; }
    public List<BaseTool> getTools() { return new ArrayList<>(tools); }
    public Agent getAgent() { return agent; }
    public List<String> getDependencyTaskIds() { return new ArrayList<>(dependencyTaskIds); }
    public boolean isAsyncExecution() { return asyncExecution; }
    public OutputFormat getOutputFormat() { return outputFormat; }
    public String getOutputFile() { return outputFile; }
    public Integer getMaxExecutionTime() { return maxExecutionTime; }
    public Predicate<String> getCondition() { return condition; }
    public Map<String, Object> getContext() { return new HashMap<>(context); }
    public TaskOutput getOutput() { return output; }
    public TaskStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getFailureReason() { return failureReason; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Task)) return false;
        Task task = (Task) o;
        return Objects.equals(id, task.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", description='" + description + '\'' +
                ", status=" + status +
                '}';
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}