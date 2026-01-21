package ai.intelliswarm.swarmai.observability.config;

import ai.intelliswarm.swarmai.observability.aspect.ObservabilityAspect;
import ai.intelliswarm.swarmai.observability.core.ObservabilityHelper;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.logging.StructuredLogger;
import ai.intelliswarm.swarmai.observability.metrics.SwarmMetricsRegistry;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.InMemoryEventStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Auto-configuration for the SwarmAI Observability system.
 *
 * This configuration sets up:
 * - Structured logging with correlation IDs
 * - Metrics collection (Micrometer/Prometheus)
 * - Decision tracing for understanding agent behavior
 * - Event storage for workflow replay
 * - AOP aspects for automatic instrumentation
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(prefix = "swarmai.observability", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableAspectJAutoProxy
public class ObservabilityAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    /**
     * Creates the StructuredLogger bean for MDC-based logging.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.observability", name = "structured-logging-enabled", havingValue = "true", matchIfMissing = true)
    public StructuredLogger structuredLogger(ObservabilityProperties properties) {
        logger.info("Initializing StructuredLogger for observability");
        return new StructuredLogger(properties);
    }

    /**
     * Creates the SwarmMetricsRegistry bean for Micrometer metrics.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.observability", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    public SwarmMetricsRegistry swarmMetricsRegistry(MeterRegistry meterRegistry, ObservabilityProperties properties) {
        logger.info("Initializing SwarmMetricsRegistry for observability metrics");
        return new SwarmMetricsRegistry(meterRegistry, properties);
    }

    /**
     * Creates the EventStore bean for workflow replay functionality.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.observability", name = "replay-enabled", havingValue = "true", matchIfMissing = true)
    public EventStore eventStore(ObservabilityProperties properties) {
        logger.info("Initializing InMemoryEventStore for workflow replay");
        return new InMemoryEventStore(properties);
    }

    /**
     * Creates the DecisionTracer bean for understanding agent decisions.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.observability", name = "decision-tracing-enabled", havingValue = "true", matchIfMissing = false)
    public DecisionTracer decisionTracer(ObservabilityProperties properties) {
        logger.info("Initializing DecisionTracer for agent decision tracking");
        return new DecisionTracer(properties);
    }

    /**
     * Creates the ObservabilityHelper bean for manual instrumentation.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservabilityHelper observabilityHelper(
            SwarmMetricsRegistry metricsRegistry,
            StructuredLogger structuredLogger,
            ObservabilityProperties properties) {
        logger.info("Initializing ObservabilityHelper for manual instrumentation");
        return new ObservabilityHelper(metricsRegistry, structuredLogger, properties);
    }

    /**
     * Creates the ObservabilityAspect bean for automatic tool instrumentation.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.observability", name = "tool-tracing-enabled", havingValue = "true", matchIfMissing = true)
    public ObservabilityAspect observabilityAspect(
            SwarmMetricsRegistry metricsRegistry,
            ObservabilityProperties properties,
            StructuredLogger structuredLogger) {
        logger.info("Initializing ObservabilityAspect for automatic tool instrumentation");
        return new ObservabilityAspect(metricsRegistry, properties, structuredLogger);
    }
}
