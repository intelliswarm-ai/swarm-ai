package ai.intelliswarm.swarmai.observability.core;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.logging.StructuredLogger;
import ai.intelliswarm.swarmai.observability.metrics.SwarmMetricsRegistry;

import java.util.function.Supplier;

/**
 * Helper class for manual observability instrumentation.
 * Use this for instrumenting non-Spring-managed classes like Agent and Task
 * which use the builder pattern.
 *
 * Bean created by ObservabilityAutoConfiguration when observability is enabled.
 *
 * Example usage in Agent.executeTask():
 * <pre>
 * return observabilityHelper.instrumentAgentExecution(
 *     this.role,
 *     this.id,
 *     task.getId(),
 *     task.getDescription(),
 *     () -> {
 *         // Original execution logic
 *         return TaskOutput.builder()...build();
 *     }
 * );
 * </pre>
 */
public class ObservabilityHelper {

    private static ObservabilityHelper instance;

    private final SwarmMetricsRegistry metricsRegistry;
    private final StructuredLogger structuredLogger;
    private final ObservabilityProperties properties;

    public ObservabilityHelper(
            SwarmMetricsRegistry metricsRegistry,
            StructuredLogger structuredLogger,
            ObservabilityProperties properties) {
        this.metricsRegistry = metricsRegistry;
        this.structuredLogger = structuredLogger;
        this.properties = properties;
        ObservabilityHelper.instance = this;
    }

    /**
     * Gets the singleton instance. Useful for non-Spring-managed classes.
     * Returns null if Spring context hasn't initialized this bean yet.
     */
    public static ObservabilityHelper getInstance() {
        return instance;
    }

    /**
     * Checks if observability is enabled.
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    // ==================== Swarm Instrumentation ====================

    /**
     * Instruments a swarm kickoff operation.
     */
    public <T> T instrumentSwarmExecution(
            String swarmId,
            int agentCount,
            int taskCount,
            Supplier<T> execution) {

        if (!isEnabled()) {
            return execution.get();
        }

        ObservabilityContext ctx = ObservabilityContext.create()
                .withSwarmId(swarmId);

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            structuredLogger.logSwarmStart(swarmId, agentCount, taskCount);
            metricsRegistry.recordSwarmStarted(swarmId);

            T result = execution.get();
            success = true;
            return result;

        } catch (RuntimeException e) {
            metricsRegistry.recordSwarmFailed(swarmId,
                    System.currentTimeMillis() - startTime,
                    e.getClass().getSimpleName());
            structuredLogger.logSwarmError(swarmId, e);
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            ctx.recordTiming("swarm_execution", durationMs);

            if (success) {
                metricsRegistry.recordSwarmCompleted(swarmId, durationMs);
            }
            structuredLogger.logSwarmComplete(swarmId, success, durationMs);
            ObservabilityContext.clear();
        }
    }

    // ==================== Agent Instrumentation ====================

    /**
     * Instruments an agent task execution.
     */
    public <T> T instrumentAgentExecution(
            String agentRole,
            String agentId,
            String taskId,
            String taskDescription,
            Supplier<T> execution) {

        if (!isEnabled()) {
            return execution.get();
        }

        ObservabilityContext parentCtx = ObservabilityContext.currentOrNull();
        ObservabilityContext ctx = ObservabilityContext.createChild()
                .withAgentId(agentId)
                .withTaskId(taskId);

        long startTime = System.currentTimeMillis();
        boolean success = false;

        try {
            structuredLogger.logAgentTaskStart(agentId, agentRole, taskId, taskDescription);

            T result = execution.get();
            success = true;
            return result;

        } catch (RuntimeException e) {
            metricsRegistry.recordError("agent", e.getClass().getSimpleName());
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            ctx.recordTiming("agent_execution", durationMs);

            metricsRegistry.recordAgentExecution(agentRole, success ? "success" : "failed", durationMs);
            structuredLogger.logAgentTaskComplete(agentId, taskId, success, durationMs);

            ObservabilityContext.restore(parentCtx);
        }
    }

    // ==================== Task Instrumentation ====================

    /**
     * Instruments a task execution.
     */
    public <T> T instrumentTaskExecution(
            String taskId,
            String taskDescription,
            Supplier<T> execution) {

        if (!isEnabled()) {
            return execution.get();
        }

        ObservabilityContext parentCtx = ObservabilityContext.currentOrNull();
        ObservabilityContext ctx = ObservabilityContext.createChild()
                .withTaskId(taskId);

        long startTime = System.currentTimeMillis();
        boolean success = false;
        String status = "failed";

        try {
            structuredLogger.logTaskStart(taskId, taskDescription);

            T result = execution.get();
            success = true;
            status = "success";
            return result;

        } catch (RuntimeException e) {
            metricsRegistry.recordError("task", e.getClass().getSimpleName());
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            ctx.recordTiming("task_execution", durationMs);

            metricsRegistry.recordTaskExecution(taskDescription, status, durationMs);
            structuredLogger.logTaskComplete(taskId, status, durationMs);

            ObservabilityContext.restore(parentCtx);
        }
    }

    // ==================== Tool Instrumentation ====================

    /**
     * Instruments a tool execution.
     */
    public <T> T instrumentToolExecution(
            String toolName,
            Supplier<T> execution) {

        if (!isEnabled()) {
            return execution.get();
        }

        ObservabilityContext parentCtx = ObservabilityContext.currentOrNull();
        ObservabilityContext ctx = ObservabilityContext.createChild()
                .withToolName(toolName);

        long startTime = System.currentTimeMillis();
        boolean success = false;
        String errorType = null;

        try {
            structuredLogger.logToolStart(toolName, null);

            T result = execution.get();
            success = true;
            return result;

        } catch (RuntimeException e) {
            errorType = e.getClass().getSimpleName();
            metricsRegistry.recordError("tool", errorType);
            throw e;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            ctx.recordTiming("tool_execution", durationMs);

            metricsRegistry.recordToolExecution(toolName, success ? "success" : "failed", durationMs);
            structuredLogger.logToolComplete(toolName, success, durationMs, errorType);

            ObservabilityContext.restore(parentCtx);
        }
    }

    // ==================== Token Tracking ====================

    /**
     * Records token usage for an agent.
     */
    public void recordTokenUsage(String agentRole, long promptTokens, long completionTokens) {
        if (!isEnabled()) return;

        metricsRegistry.recordTotalTokens(agentRole, promptTokens, completionTokens);
        structuredLogger.logTokenUsage(agentRole, promptTokens, completionTokens);
    }

    // ==================== Decision Tracking ====================

    /**
     * Records a decision made by an agent.
     */
    public void recordDecision(String agentId, String taskId, String decision, String reasoning) {
        if (!isEnabled() || !properties.isDecisionTracingEnabled()) return;

        structuredLogger.logDecision(agentId, taskId, decision, reasoning);
    }

    /**
     * Records a task delegation.
     */
    public void recordDelegation(String fromAgentId, String toAgentId, String taskId, String reason) {
        if (!isEnabled()) return;

        structuredLogger.logDelegation(fromAgentId, toAgentId, taskId, reason);
    }

    // ==================== Context Access ====================

    /**
     * Gets the current correlation ID.
     */
    public String getCurrentCorrelationId() {
        ObservabilityContext ctx = ObservabilityContext.currentOrNull();
        return ctx != null ? ctx.getCorrelationId() : null;
    }

    /**
     * Gets the current trace ID.
     */
    public String getCurrentTraceId() {
        ObservabilityContext ctx = ObservabilityContext.currentOrNull();
        return ctx != null ? ctx.getTraceId() : null;
    }

    /**
     * Gets the structured logger for custom logging.
     */
    public StructuredLogger getStructuredLogger() {
        return structuredLogger;
    }

    /**
     * Gets the metrics registry for custom metrics.
     */
    public SwarmMetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }
}
