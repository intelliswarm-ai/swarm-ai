package ai.intelliswarm.swarmai.observability.aspect;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.observability.logging.StructuredLogger;
import ai.intelliswarm.swarmai.observability.metrics.SwarmMetricsRegistry;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * AOP aspect for non-invasive observability instrumentation.
 * Intercepts Spring-managed tool executions to record metrics and logs.
 *
 * Bean created by ObservabilityAutoConfiguration when observability is enabled.
 *
 * For Agent and Task (which use builder pattern and are not Spring beans),
 * use ObservabilityHelper for manual instrumentation.
 */
@Aspect
public class ObservabilityAspect {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityAspect.class);

    private final SwarmMetricsRegistry metricsRegistry;
    private final ObservabilityProperties properties;
    private final StructuredLogger structuredLogger;

    public ObservabilityAspect(
            SwarmMetricsRegistry metricsRegistry,
            ObservabilityProperties properties,
            StructuredLogger structuredLogger) {
        this.metricsRegistry = metricsRegistry;
        this.properties = properties;
        this.structuredLogger = structuredLogger;
    }

    /**
     * Pointcut for BaseTool.execute() implementations.
     * Matches any Spring bean that implements BaseTool.
     */
    @Pointcut("execution(* ai.intelliswarm.swarmai.tool.base.BaseTool+.execute(..))")
    public void toolExecution() {}

    /**
     * Intercepts tool executions to record metrics and structured logs.
     */
    @Around("toolExecution()")
    @SuppressWarnings("unchecked")
    public Object aroundToolExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!properties.isToolTracingEnabled()) {
            return joinPoint.proceed();
        }

        Object target = joinPoint.getTarget();
        String toolName = "unknown";

        if (target instanceof BaseTool) {
            toolName = ((BaseTool) target).getFunctionName();
        }

        // Create child context for this tool execution
        ObservabilityContext parentContext = ObservabilityContext.currentOrNull();
        ObservabilityContext toolContext = ObservabilityContext.createChild()
                .withToolName(toolName);

        // Extract parameters for logging
        Object[] args = joinPoint.getArgs();
        Map<String, Object> parameters = null;
        if (args.length > 0 && args[0] instanceof Map) {
            parameters = (Map<String, Object>) args[0];
        }

        long startTime = System.currentTimeMillis();
        boolean success = false;
        String errorType = null;

        try {
            structuredLogger.logToolStart(toolName, parameters);

            Object result = joinPoint.proceed();
            success = true;

            return result;

        } catch (Throwable t) {
            errorType = t.getClass().getSimpleName();
            metricsRegistry.recordError("tool", errorType);
            throw t;

        } finally {
            long durationMs = System.currentTimeMillis() - startTime;

            // Record metrics
            metricsRegistry.recordToolExecution(toolName, success ? "success" : "failed", durationMs);
            toolContext.recordTiming("tool_execution", durationMs);

            // Log completion
            structuredLogger.logToolComplete(toolName, success, durationMs, errorType);

            // Restore parent context
            ObservabilityContext.restore(parentContext);
        }
    }
}
