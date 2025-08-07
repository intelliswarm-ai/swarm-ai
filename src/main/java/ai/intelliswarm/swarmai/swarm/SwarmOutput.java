package ai.intelliswarm.swarmai.swarm;

import ai.intelliswarm.swarmai.task.output.TaskOutput;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwarmOutput {

    private final String swarmId;
    private final String rawOutput;
    private final List<TaskOutput> taskOutputs;
    private final String finalOutput;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final Duration executionTime;
    private final Map<String, Object> usageMetrics;
    private final Map<String, Object> metadata;
    private final boolean successful;

    private SwarmOutput(Builder builder) {
        this.swarmId = builder.swarmId;
        this.rawOutput = builder.rawOutput;
        this.taskOutputs = new ArrayList<>(builder.taskOutputs);
        this.finalOutput = builder.finalOutput;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.executionTime = builder.executionTime;
        this.usageMetrics = new HashMap<>(builder.usageMetrics);
        this.metadata = new HashMap<>(builder.metadata);
        this.successful = builder.successful;
    }

    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules(); // For LocalDateTime support
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert SwarmOutput to JSON", e);
        }
    }

    public <T> T parseAs(Class<T> clazz) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(finalOutput != null ? finalOutput : rawOutput, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse output as " + clazz.getSimpleName(), e);
        }
    }

    public String getSummary() {
        if (taskOutputs.isEmpty()) {
            return rawOutput != null ? rawOutput : "No output available";
        }
        
        return taskOutputs.stream()
            .map(TaskOutput::getSummary)
            .filter(Objects::nonNull)
            .reduce((first, second) -> first + "\n" + second)
            .orElse("No summary available");
    }

    public TaskOutput getTaskOutput(String taskId) {
        return taskOutputs.stream()
            .filter(output -> taskId.equals(output.getTaskId()))
            .findFirst()
            .orElse(null);
    }

    public List<TaskOutput> getSuccessfulOutputs() {
        return taskOutputs.stream()
            .filter(TaskOutput::isSuccessful)
            .toList();
    }

    public List<TaskOutput> getFailedOutputs() {
        return taskOutputs.stream()
            .filter(output -> !output.isSuccessful())
            .toList();
    }

    public double getSuccessRate() {
        if (taskOutputs.isEmpty()) {
            return successful ? 1.0 : 0.0;
        }
        
        long successfulCount = getSuccessfulOutputs().size();
        return (double) successfulCount / taskOutputs.size();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String swarmId;
        private String rawOutput;
        private List<TaskOutput> taskOutputs = new ArrayList<>();
        private String finalOutput;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Duration executionTime;
        private Map<String, Object> usageMetrics = new HashMap<>();
        private Map<String, Object> metadata = new HashMap<>();
        private boolean successful = true;

        public Builder swarmId(String swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        public Builder rawOutput(String rawOutput) {
            this.rawOutput = rawOutput;
            return this;
        }

        public Builder taskOutputs(List<TaskOutput> taskOutputs) {
            this.taskOutputs = new ArrayList<>(taskOutputs);
            return this;
        }

        public Builder addTaskOutput(TaskOutput taskOutput) {
            this.taskOutputs.add(taskOutput);
            return this;
        }

        public Builder finalOutput(String finalOutput) {
            this.finalOutput = finalOutput;
            return this;
        }

        public Builder startTime(LocalDateTime startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(LocalDateTime endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder executionTime(Duration executionTime) {
            this.executionTime = executionTime;
            return this;
        }

        public Builder usageMetric(String key, Object value) {
            this.usageMetrics.put(key, value);
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder successful(boolean successful) {
            this.successful = successful;
            return this;
        }

        public SwarmOutput build() {
            Objects.requireNonNull(swarmId, "Swarm ID cannot be null");
            
            if (executionTime == null && startTime != null && endTime != null) {
                executionTime = Duration.between(startTime, endTime);
            }
            
            return new SwarmOutput(this);
        }
    }

    // Getters
    public String getSwarmId() { return swarmId; }
    public String getRawOutput() { return rawOutput; }
    public List<TaskOutput> getTaskOutputs() { return new ArrayList<>(taskOutputs); }
    public String getFinalOutput() { return finalOutput; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public Duration getExecutionTime() { return executionTime; }
    public Map<String, Object> getUsageMetrics() { return new HashMap<>(usageMetrics); }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public boolean isSuccessful() { return successful; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SwarmOutput)) return false;
        SwarmOutput that = (SwarmOutput) o;
        return Objects.equals(swarmId, that.swarmId) &&
                Objects.equals(startTime, that.startTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(swarmId, startTime);
    }

    @Override
    public String toString() {
        return "SwarmOutput{" +
                "swarmId='" + swarmId + '\'' +
                ", successful=" + successful +
                ", taskCount=" + taskOutputs.size() +
                ", executionTime=" + executionTime +
                '}';
    }
}