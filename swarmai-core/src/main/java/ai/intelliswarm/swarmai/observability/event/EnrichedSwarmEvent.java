package ai.intelliswarm.swarmai.observability.event;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Extended SwarmEvent with full observability data.
 * Captures correlation IDs, trace hierarchy, timing, and context
 * for comprehensive event analysis and replay.
 */
public class EnrichedSwarmEvent extends SwarmEvent {

    // Correlation and tracing
    private final String correlationId;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;

    // Entity identifiers
    private final String agentId;
    private final String agentRole;
    private final String taskId;
    private final String toolName;

    // Timing
    private final Instant eventInstant;
    private final Long durationMs;
    private final Long elapsedSinceStartMs;

    // Execution details
    private final String status;
    private final String errorType;
    private final String errorMessage;

    // Token usage (if applicable)
    private final Long promptTokens;
    private final Long completionTokens;

    // Custom attributes
    private final Map<String, Object> attributes;

    private EnrichedSwarmEvent(Builder builder) {
        super(builder.source, builder.type, builder.message, builder.swarmId, builder.baseMetadata);

        this.correlationId = builder.correlationId;
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.agentId = builder.agentId;
        this.agentRole = builder.agentRole;
        this.taskId = builder.taskId;
        this.toolName = builder.toolName;
        this.eventInstant = builder.eventInstant;
        this.durationMs = builder.durationMs;
        this.elapsedSinceStartMs = builder.elapsedSinceStartMs;
        this.status = builder.status;
        this.errorType = builder.errorType;
        this.errorMessage = builder.errorMessage;
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.attributes = new HashMap<>(builder.attributes);
    }

    /**
     * Creates a builder initialized from the current ObservabilityContext.
     */
    public static Builder builder(Object source, Type type, String message) {
        return new Builder(source, type, message);
    }

    /**
     * Creates an EnrichedSwarmEvent from a regular SwarmEvent.
     */
    public static EnrichedSwarmEvent fromSwarmEvent(SwarmEvent event) {
        ObservabilityContext ctx = ObservabilityContext.currentOrNull();

        Builder builder = new Builder(event.getSource(), event.getType(), event.getMessage())
                .swarmId(event.getSwarmId())
                .baseMetadata(event.getMetadata());

        if (ctx != null) {
            builder.fromContext(ctx);
        }

        return builder.build();
    }

    public static class Builder {
        private final Object source;
        private final Type type;
        private final String message;

        private String swarmId;
        private Map<String, Object> baseMetadata = new HashMap<>();

        private String correlationId;
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String agentId;
        private String agentRole;
        private String taskId;
        private String toolName;
        private Instant eventInstant = Instant.now();
        private Long durationMs;
        private Long elapsedSinceStartMs;
        private String status;
        private String errorType;
        private String errorMessage;
        private Long promptTokens;
        private Long completionTokens;
        private Map<String, Object> attributes = new HashMap<>();

        public Builder(Object source, Type type, String message) {
            this.source = source;
            this.type = type;
            this.message = message;
        }

        /**
         * Populates builder from ObservabilityContext.
         */
        public Builder fromContext(ObservabilityContext ctx) {
            if (ctx == null) return this;

            this.correlationId = ctx.getCorrelationId();
            this.traceId = ctx.getTraceId();
            this.spanId = ctx.getSpanId();
            this.parentSpanId = ctx.getParentSpanId();
            this.swarmId = ctx.getSwarmId();
            this.agentId = ctx.getAgentId();
            this.taskId = ctx.getTaskId();
            this.toolName = ctx.getToolName();
            this.elapsedSinceStartMs = ctx.getElapsedMs();
            this.attributes.putAll(ctx.getAttributes());
            return this;
        }

        public Builder swarmId(String swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        public Builder baseMetadata(Map<String, Object> metadata) {
            this.baseMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
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

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder eventInstant(Instant eventInstant) {
            this.eventInstant = eventInstant;
            return this;
        }

        public Builder durationMs(Long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder elapsedSinceStartMs(Long elapsedSinceStartMs) {
            this.elapsedSinceStartMs = elapsedSinceStartMs;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder success() {
            this.status = "success";
            return this;
        }

        public Builder failed(String errorType, String errorMessage) {
            this.status = "failed";
            this.errorType = errorType;
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder failed(Throwable t) {
            this.status = "failed";
            this.errorType = t.getClass().getSimpleName();
            this.errorMessage = t.getMessage();
            return this;
        }

        public Builder tokenUsage(Long promptTokens, Long completionTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes.putAll(attributes);
            return this;
        }

        public EnrichedSwarmEvent build() {
            return new EnrichedSwarmEvent(this);
        }
    }

    // Getters

    public String getCorrelationId() {
        return correlationId;
    }

    public String getTraceId() {
        return traceId;
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

    public String getTaskId() {
        return taskId;
    }

    public String getToolName() {
        return toolName;
    }

    public Instant getEventInstant() {
        return eventInstant;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Long getElapsedSinceStartMs() {
        return elapsedSinceStartMs;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Long getPromptTokens() {
        return promptTokens;
    }

    public Long getCompletionTokens() {
        return completionTokens;
    }

    public Long getTotalTokens() {
        if (promptTokens != null && completionTokens != null) {
            return promptTokens + completionTokens;
        }
        return null;
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    /**
     * Returns a map of all event data suitable for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        // Base event data
        map.put("type", getType().name());
        map.put("message", getMessage());
        map.put("swarmId", getSwarmId());
        map.put("eventTime", getEventTime().toString());

        // Observability data
        if (correlationId != null) map.put("correlationId", correlationId);
        if (traceId != null) map.put("traceId", traceId);
        if (spanId != null) map.put("spanId", spanId);
        if (parentSpanId != null) map.put("parentSpanId", parentSpanId);

        // Entity data
        if (agentId != null) map.put("agentId", agentId);
        if (agentRole != null) map.put("agentRole", agentRole);
        if (taskId != null) map.put("taskId", taskId);
        if (toolName != null) map.put("toolName", toolName);

        // Timing
        if (eventInstant != null) map.put("eventInstant", eventInstant.toString());
        if (durationMs != null) map.put("durationMs", durationMs);
        if (elapsedSinceStartMs != null) map.put("elapsedSinceStartMs", elapsedSinceStartMs);

        // Status
        if (status != null) map.put("status", status);
        if (errorType != null) map.put("errorType", errorType);
        if (errorMessage != null) map.put("errorMessage", errorMessage);

        // Tokens
        if (promptTokens != null) map.put("promptTokens", promptTokens);
        if (completionTokens != null) map.put("completionTokens", completionTokens);

        // Attributes
        if (!attributes.isEmpty()) map.put("attributes", attributes);

        return map;
    }

    @Override
    public String toString() {
        return "EnrichedSwarmEvent{" +
                "type=" + getType() +
                ", correlationId='" + correlationId + '\'' +
                ", swarmId='" + getSwarmId() + '\'' +
                ", agentId='" + agentId + '\'' +
                ", taskId='" + taskId + '\'' +
                ", status='" + status + '\'' +
                ", durationMs=" + durationMs +
                '}';
    }
}
