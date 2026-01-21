package ai.intelliswarm.swarmai.observability.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ThreadLocal context holder for observability data.
 * Provides correlation IDs, trace/span hierarchy, and timing information
 * that flows through the entire workflow execution.
 */
public class ObservabilityContext {

    private static final ThreadLocal<ObservabilityContext> CONTEXT = new ThreadLocal<>();

    private final String correlationId;
    private final String traceId;
    private String spanId;
    private String parentSpanId;
    private String swarmId;
    private String agentId;
    private String taskId;
    private String toolName;
    private final Instant startTime;
    private final Map<String, Object> attributes;
    private final Map<String, Long> timings;

    private ObservabilityContext() {
        this.correlationId = UUID.randomUUID().toString();
        this.traceId = UUID.randomUUID().toString();
        this.spanId = UUID.randomUUID().toString();
        this.startTime = Instant.now();
        this.attributes = new ConcurrentHashMap<>();
        this.timings = new ConcurrentHashMap<>();
    }

    private ObservabilityContext(ObservabilityContext parent) {
        this.correlationId = parent.correlationId;
        this.traceId = parent.traceId;
        this.parentSpanId = parent.spanId;
        this.spanId = UUID.randomUUID().toString();
        this.swarmId = parent.swarmId;
        this.agentId = parent.agentId;
        this.taskId = parent.taskId;
        this.toolName = parent.toolName;
        this.startTime = Instant.now();
        this.attributes = new ConcurrentHashMap<>(parent.attributes);
        this.timings = new ConcurrentHashMap<>();
    }

    /**
     * Creates a new observability context and sets it as current.
     */
    public static ObservabilityContext create() {
        ObservabilityContext ctx = new ObservabilityContext();
        CONTEXT.set(ctx);
        return ctx;
    }

    /**
     * Creates a child context inheriting correlation/trace IDs from current context.
     * Used when entering a new span (agent/task/tool execution).
     */
    public static ObservabilityContext createChild() {
        ObservabilityContext parent = CONTEXT.get();
        if (parent == null) {
            return create();
        }
        ObservabilityContext child = new ObservabilityContext(parent);
        CONTEXT.set(child);
        return child;
    }

    /**
     * Gets the current context, creating one if none exists.
     */
    public static ObservabilityContext current() {
        ObservabilityContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = create();
        }
        return ctx;
    }

    /**
     * Gets the current context or null if none exists.
     */
    public static ObservabilityContext currentOrNull() {
        return CONTEXT.get();
    }

    /**
     * Clears the current context. Should be called in finally blocks.
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Restores a parent context after child span completes.
     */
    public static void restore(ObservabilityContext ctx) {
        if (ctx != null) {
            CONTEXT.set(ctx);
        } else {
            CONTEXT.remove();
        }
    }

    // Fluent setters

    public ObservabilityContext withSwarmId(String swarmId) {
        this.swarmId = swarmId;
        return this;
    }

    public ObservabilityContext withAgentId(String agentId) {
        this.agentId = agentId;
        return this;
    }

    public ObservabilityContext withTaskId(String taskId) {
        this.taskId = taskId;
        return this;
    }

    public ObservabilityContext withToolName(String toolName) {
        this.toolName = toolName;
        return this;
    }

    public ObservabilityContext withAttribute(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }

    // Timing operations

    /**
     * Records a timing for a named phase.
     */
    public void recordTiming(String phase, long durationMs) {
        this.timings.put(phase, durationMs);
    }

    /**
     * Gets elapsed time since context creation in milliseconds.
     */
    public long getElapsedMs() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
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

    public String getSwarmId() {
        return swarmId;
    }

    public String getAgentId() {
        return agentId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getToolName() {
        return toolName;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    public Map<String, Long> getTimings() {
        return new HashMap<>(timings);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Returns a map of all context data suitable for MDC/structured logging.
     */
    public Map<String, String> toMdcMap() {
        Map<String, String> mdc = new HashMap<>();
        mdc.put("correlationId", correlationId);
        mdc.put("traceId", traceId);
        mdc.put("spanId", spanId);
        if (parentSpanId != null) {
            mdc.put("parentSpanId", parentSpanId);
        }
        if (swarmId != null) {
            mdc.put("swarmId", swarmId);
        }
        if (agentId != null) {
            mdc.put("agentId", agentId);
        }
        if (taskId != null) {
            mdc.put("taskId", taskId);
        }
        if (toolName != null) {
            mdc.put("toolName", toolName);
        }
        return mdc;
    }

    @Override
    public String toString() {
        return "ObservabilityContext{" +
                "correlationId='" + correlationId + '\'' +
                ", traceId='" + traceId + '\'' +
                ", spanId='" + spanId + '\'' +
                ", swarmId='" + swarmId + '\'' +
                ", agentId='" + agentId + '\'' +
                ", taskId='" + taskId + '\'' +
                '}';
    }
}
