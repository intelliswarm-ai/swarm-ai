package ai.intelliswarm.swarmai.observability.logging;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Structured logger that enriches log entries with observability context.
 * Uses MDC (Mapped Diagnostic Context) to add correlation IDs, trace IDs,
 * and entity identifiers to all log messages.
 * Bean created by ObservabilityAutoConfiguration when observability is enabled.
 */
public class StructuredLogger {

    private static final Logger logger = LoggerFactory.getLogger(StructuredLogger.class);

    private final ObservabilityProperties properties;

    public StructuredLogger(ObservabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks if structured logging is enabled.
     */
    public boolean isEnabled() {
        return properties.isStructuredLoggingEnabled();
    }

    /**
     * Sets up MDC context from ObservabilityContext.
     * Call this at the start of an operation.
     */
    public void setupMDC() {
        if (!isEnabled()) return;

        ObservabilityContext ctx = ObservabilityContext.currentOrNull();
        if (ctx != null) {
            Map<String, String> mdcMap = ctx.toMdcMap();
            mdcMap.forEach(MDC::put);
        }
    }

    /**
     * Clears MDC context.
     * Call this in a finally block after operations complete.
     */
    public void clearMDC() {
        MDC.clear();
    }

    /**
     * Executes an operation with MDC context set.
     */
    public <T> T withMDC(java.util.function.Supplier<T> operation) {
        setupMDC();
        try {
            return operation.get();
        } finally {
            clearMDC();
        }
    }

    /**
     * Executes a void operation with MDC context set.
     */
    public void withMDC(Runnable operation) {
        setupMDC();
        try {
            operation.run();
        } finally {
            clearMDC();
        }
    }

    // ==================== Swarm Logging ====================

    public void logSwarmStart(String swarmId, int agentCount, int taskCount) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info("Swarm started: swarmId={}, agents={}, tasks={}",
                    swarmId, agentCount, taskCount);
        } finally {
            clearMDC();
        }
    }

    public void logSwarmComplete(String swarmId, boolean success, long durationMs) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            if (success) {
                logger.info("Swarm completed successfully: swarmId={}, durationMs={}",
                        swarmId, durationMs);
            } else {
                logger.warn("Swarm failed: swarmId={}, durationMs={}",
                        swarmId, durationMs);
            }
        } finally {
            clearMDC();
        }
    }

    public void logSwarmError(String swarmId, Throwable error) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.error("Swarm error: swarmId={}, error={}",
                    swarmId, error.getMessage(), error);
        } finally {
            clearMDC();
        }
    }

    // ==================== Agent Logging ====================

    public void logAgentTaskStart(String agentId, String agentRole, String taskId, String taskDescription) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info("Agent starting task: agentId={}, role={}, taskId={}, task={}",
                    agentId, agentRole, taskId, truncate(taskDescription, 100));
        } finally {
            clearMDC();
        }
    }

    public void logAgentTaskComplete(String agentId, String taskId, boolean success, long durationMs) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            if (success) {
                logger.info("Agent completed task: agentId={}, taskId={}, durationMs={}",
                        agentId, taskId, durationMs);
            } else {
                logger.warn("Agent failed task: agentId={}, taskId={}, durationMs={}",
                        agentId, taskId, durationMs);
            }
        } finally {
            clearMDC();
        }
    }

    // ==================== Task Logging ====================

    public void logTaskStart(String taskId, String description) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info("Task started: taskId={}, description={}",
                    taskId, truncate(description, 100));
        } finally {
            clearMDC();
        }
    }

    public void logTaskComplete(String taskId, String status, long durationMs) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info("Task completed: taskId={}, status={}, durationMs={}",
                    taskId, status, durationMs);
        } finally {
            clearMDC();
        }
    }

    // ==================== Tool Logging ====================

    public void logToolStart(String toolName, Map<String, Object> parameters) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            if (properties.isDecisionTracingEnabled() && parameters != null) {
                logger.debug("Tool invoked: tool={}, parameters={}",
                        toolName, parameters);
            } else {
                logger.debug("Tool invoked: tool={}", toolName);
            }
        } finally {
            clearMDC();
        }
    }

    public void logToolComplete(String toolName, boolean success, long durationMs, String errorType) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            if (success) {
                logger.debug("Tool completed: tool={}, durationMs={}",
                        toolName, durationMs);
            } else {
                logger.warn("Tool failed: tool={}, durationMs={}, error={}",
                        toolName, durationMs, errorType);
            }
        } finally {
            clearMDC();
        }
    }

    // ==================== Decision Logging ====================

    public void logDecision(String agentId, String taskId, String decision, String reasoning) {
        if (!isEnabled() || !properties.isDecisionTracingEnabled()) return;
        setupMDC();
        try {
            logger.info("Agent decision: agentId={}, taskId={}, decision={}, reasoning={}",
                    agentId, taskId, truncate(decision, 200), truncate(reasoning, 500));
        } finally {
            clearMDC();
        }
    }

    public void logDelegation(String fromAgentId, String toAgentId, String taskId, String reason) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info("Task delegated: from={}, to={}, taskId={}, reason={}",
                    fromAgentId, toAgentId, taskId, truncate(reason, 200));
        } finally {
            clearMDC();
        }
    }

    // ==================== Token Logging ====================

    public void logTokenUsage(String agentId, long promptTokens, long completionTokens) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.debug("Token usage: agentId={}, promptTokens={}, completionTokens={}, total={}",
                    agentId, promptTokens, completionTokens, promptTokens + completionTokens);
        } finally {
            clearMDC();
        }
    }

    // ==================== Utility Methods ====================

    /**
     * Logs a custom info message with MDC context.
     */
    public void info(String message, Object... args) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.info(message, args);
        } finally {
            clearMDC();
        }
    }

    /**
     * Logs a custom debug message with MDC context.
     */
    public void debug(String message, Object... args) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.debug(message, args);
        } finally {
            clearMDC();
        }
    }

    /**
     * Logs a custom warning message with MDC context.
     */
    public void warn(String message, Object... args) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.warn(message, args);
        } finally {
            clearMDC();
        }
    }

    /**
     * Logs a custom error message with MDC context.
     */
    public void error(String message, Object... args) {
        if (!isEnabled()) return;
        setupMDC();
        try {
            logger.error(message, args);
        } finally {
            clearMDC();
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
