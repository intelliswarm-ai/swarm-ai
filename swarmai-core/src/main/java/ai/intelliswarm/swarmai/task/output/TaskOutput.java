package ai.intelliswarm.swarmai.task.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskOutput {

    private final String taskId;
    private final String agentId;
    private final String rawOutput;
    private final String description;
    private final String summary;
    private final OutputFormat format;
    private final Map<String, Object> metadata;
    private final LocalDateTime timestamp;
    private final Long executionTimeMs;

    // Token usage tracking
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;

    private TaskOutput(Builder builder) {
        this.taskId = builder.taskId;
        this.agentId = builder.agentId;
        this.rawOutput = builder.rawOutput;
        this.description = builder.description;
        this.summary = builder.summary;
        this.format = builder.format;
        this.metadata = new HashMap<>(builder.metadata);
        this.timestamp = LocalDateTime.now();
        this.executionTimeMs = builder.executionTimeMs;
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.totalTokens = builder.totalTokens;
    }

    public <T> T parseAsType(Class<T> type) {
        if (format == OutputFormat.JSON) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue(rawOutput, type);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse output as " + type.getSimpleName(), e);
            }
        }
        throw new IllegalStateException("Output format is not JSON");
    }

    public Map<String, Object> parseAsMap() {
        return parseAsType(Map.class);
    }

    public boolean isSuccessful() {
        return rawOutput != null && !rawOutput.trim().isEmpty();
    }

    public String getFormattedOutput() {
        return switch (format) {
            case JSON -> formatAsJson();
            case MARKDOWN -> formatAsMarkdown();
            case TEXT -> rawOutput;
        };
    }

    private String formatAsJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Object json = mapper.readValue(rawOutput, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return rawOutput; // Return raw if JSON parsing fails
        }
    }

    private String formatAsMarkdown() {
        // Basic markdown formatting - could be enhanced
        return "## " + description + "\n\n" + rawOutput;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String taskId;
        private String agentId;
        private String rawOutput;
        private String description;
        private String summary;
        private OutputFormat format = OutputFormat.TEXT;
        private Map<String, Object> metadata = new HashMap<>();
        private Long executionTimeMs;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder rawOutput(String rawOutput) {
            this.rawOutput = rawOutput;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder format(OutputFormat format) {
            this.format = format;
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder executionTimeMs(Long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public Builder promptTokens(Long promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder completionTokens(Long completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder totalTokens(Long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public TaskOutput build() {
            Objects.requireNonNull(taskId, "Task ID cannot be null");
            Objects.requireNonNull(rawOutput, "Raw output cannot be null");
            
            return new TaskOutput(this);
        }
    }

    // Getters
    public String getTaskId() { return taskId; }
    public String getAgentId() { return agentId; }
    public String getRawOutput() { return rawOutput; }
    public String getDescription() { return description; }
    public String getSummary() { return summary; }
    public OutputFormat getFormat() { return format; }
    public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Long getExecutionTimeMs() { return executionTimeMs; }
    public Long getPromptTokens() { return promptTokens; }
    public Long getCompletionTokens() { return completionTokens; }
    public Long getTotalTokens() { return totalTokens; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaskOutput)) return false;
        TaskOutput that = (TaskOutput) o;
        return Objects.equals(taskId, that.taskId) &&
                Objects.equals(agentId, that.agentId) &&
                Objects.equals(timestamp, that.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId, agentId, timestamp);
    }

    @Override
    public String toString() {
        return "TaskOutput{" +
                "taskId='" + taskId + '\'' +
                ", agentId='" + agentId + '\'' +
                ", format=" + format +
                ", timestamp=" + timestamp +
                '}';
    }
}