package ai.intelliswarm.swarmai.observability.decision;

import java.time.Instant;
import java.util.*;

/**
 * Represents a single decision point in an agent's execution.
 * Captures the context, reasoning, and outcome of a decision.
 */
public class DecisionNode {

    private final String id;
    private final String correlationId;
    private final String spanId;
    private final String parentSpanId;

    // Agent context
    private final String agentId;
    private final String agentRole;
    private final String agentGoal;
    private final String agentBackstory;

    // Task context
    private final String taskId;
    private final String taskDescription;
    private final String expectedOutput;

    // Input
    private final String inputContext;
    private final String prompt;

    // Output
    private final String rawResponse;
    private final String decision;
    private final String reasoning;
    private final List<String> toolsUsed;

    // Timing
    private final Instant timestamp;
    private final long latencyMs;

    // Analysis metadata
    private final Map<String, Object> analysisMetadata;

    private DecisionNode(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.correlationId = builder.correlationId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.agentId = builder.agentId;
        this.agentRole = builder.agentRole;
        this.agentGoal = builder.agentGoal;
        this.agentBackstory = builder.agentBackstory;
        this.taskId = builder.taskId;
        this.taskDescription = builder.taskDescription;
        this.expectedOutput = builder.expectedOutput;
        this.inputContext = builder.inputContext;
        this.prompt = builder.prompt;
        this.rawResponse = builder.rawResponse;
        this.decision = builder.decision;
        this.reasoning = builder.reasoning;
        this.toolsUsed = builder.toolsUsed != null ? new ArrayList<>(builder.toolsUsed) : new ArrayList<>();
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.latencyMs = builder.latencyMs;
        this.analysisMetadata = builder.analysisMetadata != null ? new HashMap<>(builder.analysisMetadata) : new HashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String correlationId;
        private String spanId;
        private String parentSpanId;
        private String agentId;
        private String agentRole;
        private String agentGoal;
        private String agentBackstory;
        private String taskId;
        private String taskDescription;
        private String expectedOutput;
        private String inputContext;
        private String prompt;
        private String rawResponse;
        private String decision;
        private String reasoning;
        private List<String> toolsUsed;
        private Instant timestamp;
        private long latencyMs;
        private Map<String, Object> analysisMetadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder spanId(String spanId) {
            this.spanId = spanId;
            return this;
        }

        public Builder parentSpanId(String parentSpanId) {
            this.parentSpanId = parentSpanId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder agentRole(String agentRole) {
            this.agentRole = agentRole;
            return this;
        }

        public Builder agentGoal(String agentGoal) {
            this.agentGoal = agentGoal;
            return this;
        }

        public Builder agentBackstory(String agentBackstory) {
            this.agentBackstory = agentBackstory;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskDescription(String taskDescription) {
            this.taskDescription = taskDescription;
            return this;
        }

        public Builder expectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
            return this;
        }

        public Builder inputContext(String inputContext) {
            this.inputContext = inputContext;
            return this;
        }

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder rawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
            return this;
        }

        public Builder decision(String decision) {
            this.decision = decision;
            return this;
        }

        public Builder reasoning(String reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder toolsUsed(List<String> toolsUsed) {
            this.toolsUsed = toolsUsed;
            return this;
        }

        public Builder toolUsed(String toolName) {
            if (this.toolsUsed == null) {
                this.toolsUsed = new ArrayList<>();
            }
            this.toolsUsed.add(toolName);
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder analysisMetadata(Map<String, Object> metadata) {
            this.analysisMetadata = metadata;
            return this;
        }

        public Builder addAnalysis(String key, Object value) {
            if (this.analysisMetadata == null) {
                this.analysisMetadata = new HashMap<>();
            }
            this.analysisMetadata.put(key, value);
            return this;
        }

        public DecisionNode build() {
            return new DecisionNode(this);
        }
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public String getAgentGoal() {
        return agentGoal;
    }

    public String getAgentBackstory() {
        return agentBackstory;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public String getExpectedOutput() {
        return expectedOutput;
    }

    public String getInputContext() {
        return inputContext;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public String getDecision() {
        return decision;
    }

    public String getReasoning() {
        return reasoning;
    }

    public List<String> getToolsUsed() {
        return new ArrayList<>(toolsUsed);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Map<String, Object> getAnalysisMetadata() {
        return new HashMap<>(analysisMetadata);
    }

    /**
     * Converts this node to a map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("correlationId", correlationId);
        map.put("spanId", spanId);
        map.put("parentSpanId", parentSpanId);
        map.put("agentId", agentId);
        map.put("agentRole", agentRole);
        map.put("agentGoal", agentGoal);
        map.put("taskId", taskId);
        map.put("taskDescription", taskDescription);
        map.put("decision", decision);
        map.put("reasoning", reasoning);
        map.put("toolsUsed", toolsUsed);
        map.put("timestamp", timestamp != null ? timestamp.toString() : null);
        map.put("latencyMs", latencyMs);
        map.put("analysisMetadata", analysisMetadata);
        return map;
    }

    @Override
    public String toString() {
        return "DecisionNode{" +
                "id='" + id + '\'' +
                ", agentRole='" + agentRole + '\'' +
                ", taskId='" + taskId + '\'' +
                ", decision='" + truncate(decision, 50) + '\'' +
                ", latencyMs=" + latencyMs +
                '}';
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
