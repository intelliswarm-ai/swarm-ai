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

    // ============================================
    // Token Usage Aggregation
    // ============================================

    public long getTotalPromptTokens() {
        return taskOutputs.stream()
                .mapToLong(o -> o.getPromptTokens() != null ? o.getPromptTokens() : 0)
                .sum();
    }

    public long getTotalCompletionTokens() {
        return taskOutputs.stream()
                .mapToLong(o -> o.getCompletionTokens() != null ? o.getCompletionTokens() : 0)
                .sum();
    }

    public long getTotalTokens() {
        return taskOutputs.stream()
                .mapToLong(o -> o.getTotalTokens() != null ? o.getTotalTokens() : 0)
                .sum();
    }

    /**
     * Estimates cost in USD based on model pricing.
     * Pricing per 1M tokens:
     *   gpt-4o-mini: $0.15 input, $0.60 output
     *   gpt-4o: $2.50 input, $10.00 output
     *   claude-3-sonnet: $3.00 input, $15.00 output
     *   claude-3-haiku: $0.25 input, $1.25 output
     */
    public double estimateCostUsd(String modelName) {
        double inputPricePer1M;
        double outputPricePer1M;

        if (modelName == null) modelName = "";
        String model = modelName.toLowerCase();

        if (model.contains("gpt-4o-mini")) {
            inputPricePer1M = 0.15;
            outputPricePer1M = 0.60;
        } else if (model.contains("gpt-4o")) {
            inputPricePer1M = 2.50;
            outputPricePer1M = 10.00;
        } else if (model.contains("gpt-4-turbo") || model.contains("gpt-4")) {
            inputPricePer1M = 10.00;
            outputPricePer1M = 30.00;
        } else if (model.contains("gpt-3.5")) {
            inputPricePer1M = 0.50;
            outputPricePer1M = 1.50;
        } else if (model.contains("claude") && model.contains("sonnet")) {
            inputPricePer1M = 3.00;
            outputPricePer1M = 15.00;
        } else if (model.contains("claude") && model.contains("haiku")) {
            inputPricePer1M = 0.25;
            outputPricePer1M = 1.25;
        } else if (model.contains("claude") && model.contains("opus")) {
            inputPricePer1M = 15.00;
            outputPricePer1M = 75.00;
        } else {
            // Default: assume cheap model
            inputPricePer1M = 0.15;
            outputPricePer1M = 0.60;
        }

        double inputCost = (getTotalPromptTokens() / 1_000_000.0) * inputPricePer1M;
        double outputCost = (getTotalCompletionTokens() / 1_000_000.0) * outputPricePer1M;
        return inputCost + outputCost;
    }

    /**
     * Returns a formatted token usage summary string.
     */
    public String getTokenUsageSummary(String modelName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Token Usage:\n");
        sb.append(String.format("  Prompt tokens:     %,d\n", getTotalPromptTokens()));
        sb.append(String.format("  Completion tokens: %,d\n", getTotalCompletionTokens()));
        sb.append(String.format("  Total tokens:      %,d\n", getTotalTokens()));
        sb.append(String.format("  Estimated cost:    $%.4f (%s)\n", estimateCostUsd(modelName), modelName));

        sb.append("\n  Per-task breakdown:\n");
        for (TaskOutput task : taskOutputs) {
            String desc = task.getDescription() != null ? task.getDescription() : "Task";
            if (desc.length() > 40) desc = desc.substring(0, 37) + "...";
            sb.append(String.format("    %-40s  %6d prompt, %6d completion\n",
                    desc,
                    task.getPromptTokens() != null ? task.getPromptTokens() : 0,
                    task.getCompletionTokens() != null ? task.getCompletionTokens() : 0));
        }
        return sb.toString();
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

    // ============================================
    // Typed Usage Metric Accessors (Phase 1)
    // ============================================

    /**
     * Returns a typed value from usage metrics.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> usageMetric(String key) {
        return Optional.ofNullable((T) usageMetrics.get(key));
    }

    /**
     * Returns a typed value from metadata.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> metadataValue(String key) {
        return Optional.ofNullable((T) metadata.get(key));
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