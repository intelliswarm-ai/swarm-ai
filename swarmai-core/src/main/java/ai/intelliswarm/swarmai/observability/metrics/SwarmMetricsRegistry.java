package ai.intelliswarm.swarmai.observability.metrics;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import io.micrometer.core.instrument.*;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Registry for SwarmAI metrics using Micrometer.
 * Provides metrics for agent execution, task execution, tool usage,
 * token consumption, and error tracking.
 * Bean created by ObservabilityAutoConfiguration when observability is enabled.
 */
public class SwarmMetricsRegistry {

    private final MeterRegistry meterRegistry;
    private final ObservabilityProperties properties;
    private final String prefix;

    // Caches for dynamic meters to avoid recreating them
    private final ConcurrentHashMap<String, Timer> agentTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> taskTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> toolTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> tokenCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    // Base counters
    private final Counter swarmStartedCounter;
    private final Counter swarmCompletedCounter;
    private final Counter swarmFailedCounter;
    private final Counter totalExecutionsCounter;
    private final Counter totalErrorsCounter;

    public SwarmMetricsRegistry(MeterRegistry meterRegistry, ObservabilityProperties properties) {
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.prefix = properties.getMetrics().getPrefix();

        // Initialize base counters
        this.swarmStartedCounter = Counter.builder(prefix + ".swarm.started")
                .description("Number of swarm executions started")
                .register(meterRegistry);

        this.swarmCompletedCounter = Counter.builder(prefix + ".swarm.completed")
                .description("Number of swarm executions completed successfully")
                .register(meterRegistry);

        this.swarmFailedCounter = Counter.builder(prefix + ".swarm.failed")
                .description("Number of swarm executions that failed")
                .register(meterRegistry);

        this.totalExecutionsCounter = Counter.builder(prefix + ".executions.total")
                .description("Total number of executions (agents, tasks, tools)")
                .register(meterRegistry);

        this.totalErrorsCounter = Counter.builder(prefix + ".errors.total")
                .description("Total number of errors across all components")
                .register(meterRegistry);
    }

    /**
     * Checks if metrics are enabled.
     */
    public boolean isEnabled() {
        return properties.isMetricsEnabled();
    }

    // ==================== Swarm Metrics ====================

    /**
     * Records that a swarm execution started.
     */
    public void recordSwarmStarted(String swarmId) {
        if (!isEnabled()) return;
        swarmStartedCounter.increment();
    }

    /**
     * Records that a swarm execution completed successfully.
     */
    public void recordSwarmCompleted(String swarmId, long durationMs) {
        if (!isEnabled()) return;
        swarmCompletedCounter.increment();

        Timer.builder(prefix + ".swarm.duration")
                .description("Duration of swarm executions")
                .tag("swarm.id", swarmId != null ? swarmId : "unknown")
                .tag("status", "success")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records that a swarm execution failed.
     */
    public void recordSwarmFailed(String swarmId, long durationMs, String errorType) {
        if (!isEnabled()) return;
        swarmFailedCounter.increment();
        totalErrorsCounter.increment();

        Timer.builder(prefix + ".swarm.duration")
                .description("Duration of swarm executions")
                .tag("swarm.id", swarmId != null ? swarmId : "unknown")
                .tag("status", "failed")
                .tag("error.type", errorType != null ? errorType : "unknown")
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ==================== Agent Metrics ====================

    /**
     * Records agent execution duration.
     */
    public void recordAgentExecution(String agentRole, String status, long durationMs) {
        if (!isEnabled()) return;
        totalExecutionsCounter.increment();

        String key = agentRole + ":" + status;
        Timer timer = agentTimers.computeIfAbsent(key, k ->
                createTimer(prefix + ".agent.execution.duration",
                        "Duration of agent task executions",
                        "agent.role", sanitizeTag(agentRole),
                        "status", status));

        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Times an agent execution using a supplier.
     */
    public <T> T timeAgentExecution(String agentRole, Supplier<T> execution) {
        if (!isEnabled()) {
            return execution.get();
        }

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            T result = execution.get();
            success = true;
            return result;
        } finally {
            long duration = System.currentTimeMillis() - start;
            recordAgentExecution(agentRole, success ? "success" : "failed", duration);
        }
    }

    // ==================== Task Metrics ====================

    /**
     * Records task execution duration.
     */
    public void recordTaskExecution(String taskDescription, String status, long durationMs) {
        if (!isEnabled()) return;
        totalExecutionsCounter.increment();

        String key = "task:" + status;
        Timer timer = taskTimers.computeIfAbsent(key, k ->
                createTimer(prefix + ".task.execution.duration",
                        "Duration of task executions",
                        "status", status));

        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ==================== Tool Metrics ====================

    /**
     * Records tool execution duration.
     */
    public void recordToolExecution(String toolName, String status, long durationMs) {
        if (!isEnabled()) return;
        totalExecutionsCounter.increment();

        String key = toolName + ":" + status;
        Timer timer = toolTimers.computeIfAbsent(key, k ->
                createTimer(prefix + ".tool.execution.duration",
                        "Duration of tool executions",
                        "tool.name", sanitizeTag(toolName),
                        "status", status));

        timer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Times a tool execution using a supplier.
     */
    public <T> T timeToolExecution(String toolName, Supplier<T> execution) {
        if (!isEnabled()) {
            return execution.get();
        }

        long start = System.currentTimeMillis();
        boolean success = false;
        try {
            T result = execution.get();
            success = true;
            return result;
        } finally {
            long duration = System.currentTimeMillis() - start;
            recordToolExecution(toolName, success ? "success" : "failed", duration);
        }
    }

    // ==================== Token Metrics ====================

    /**
     * Records prompt tokens used.
     */
    public void recordPromptTokens(String agentRole, long tokens) {
        if (!isEnabled()) return;

        String key = "prompt:" + agentRole;
        Counter counter = tokenCounters.computeIfAbsent(key, k ->
                Counter.builder(prefix + ".tokens.prompt")
                        .description("Number of prompt tokens used")
                        .tag("agent.role", sanitizeTag(agentRole))
                        .register(meterRegistry));

        counter.increment(tokens);
    }

    /**
     * Records completion tokens generated.
     */
    public void recordCompletionTokens(String agentRole, long tokens) {
        if (!isEnabled()) return;

        String key = "completion:" + agentRole;
        Counter counter = tokenCounters.computeIfAbsent(key, k ->
                Counter.builder(prefix + ".tokens.completion")
                        .description("Number of completion tokens generated")
                        .tag("agent.role", sanitizeTag(agentRole))
                        .register(meterRegistry));

        counter.increment(tokens);
    }

    /**
     * Records total tokens (prompt + completion).
     */
    public void recordTotalTokens(String agentRole, long promptTokens, long completionTokens) {
        recordPromptTokens(agentRole, promptTokens);
        recordCompletionTokens(agentRole, completionTokens);
    }

    // ==================== Error Metrics ====================

    /**
     * Records an error occurrence.
     */
    public void recordError(String component, String errorType) {
        if (!isEnabled()) return;
        totalErrorsCounter.increment();

        String key = component + ":" + errorType;
        Counter counter = errorCounters.computeIfAbsent(key, k ->
                Counter.builder(prefix + ".errors")
                        .description("Number of errors by component and type")
                        .tag("component", sanitizeTag(component))
                        .tag("error.type", sanitizeTag(errorType))
                        .register(meterRegistry));

        counter.increment();
    }

    // ==================== Gauge Metrics ====================

    /**
     * Registers a gauge for tracking in-flight operations.
     */
    public void registerInFlightGauge(String name, String description, Supplier<Number> valueSupplier) {
        if (!isEnabled()) return;

        Gauge.builder(prefix + "." + name, valueSupplier)
                .description(description)
                .register(meterRegistry);
    }

    // ==================== Utility Methods ====================

    private Timer createTimer(String name, String description, String... tags) {
        Timer.Builder builder = Timer.builder(name)
                .description(description);

        for (int i = 0; i < tags.length; i += 2) {
            builder.tag(tags[i], tags[i + 1]);
        }

        if (properties.getMetrics().isHistogramPercentilesEnabled()) {
            builder.publishPercentiles(properties.getMetrics().getPercentiles());
        }

        return builder.register(meterRegistry);
    }

    private String sanitizeTag(String value) {
        if (value == null) {
            return "unknown";
        }
        // Replace characters that could cause issues in metric tags
        return value.toLowerCase()
                .replaceAll("[^a-z0-9_.-]", "_")
                .replaceAll("_{2,}", "_");
    }

    /**
     * Gets the underlying Micrometer registry for advanced use cases.
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }
}
