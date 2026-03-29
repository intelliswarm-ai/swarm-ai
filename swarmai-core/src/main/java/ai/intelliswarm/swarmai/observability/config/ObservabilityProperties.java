package ai.intelliswarm.swarmai.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SwarmAI observability features.
 * All features can be individually toggled for performance optimization.
 * Bound via @EnableConfigurationProperties in ObservabilityAutoConfiguration.
 */
@ConfigurationProperties(prefix = "swarmai.observability")
public class ObservabilityProperties {

    /**
     * Master switch for all observability features.
     */
    private boolean enabled = true;

    /**
     * Enable structured logging with MDC context (correlation IDs, trace IDs).
     */
    private boolean structuredLoggingEnabled = true;

    /**
     * Enable tool execution tracing.
     */
    private boolean toolTracingEnabled = true;

    /**
     * Enable decision tracing - captures WHY agents made decisions.
     * Has higher overhead, recommended for debugging only.
     */
    private boolean decisionTracingEnabled = false;

    /**
     * Enable workflow replay capabilities.
     */
    private boolean replayEnabled = true;

    /**
     * Enable Micrometer metrics export.
     */
    private boolean metricsEnabled = true;

    /**
     * Replay/event store configuration.
     */
    private ReplayProperties replay = new ReplayProperties();

    /**
     * Decision tracing configuration.
     */
    private DecisionProperties decision = new DecisionProperties();

    /**
     * Metrics configuration.
     */
    private MetricsProperties metrics = new MetricsProperties();

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isStructuredLoggingEnabled() {
        return enabled && structuredLoggingEnabled;
    }

    public void setStructuredLoggingEnabled(boolean structuredLoggingEnabled) {
        this.structuredLoggingEnabled = structuredLoggingEnabled;
    }

    public boolean isToolTracingEnabled() {
        return enabled && toolTracingEnabled;
    }

    public void setToolTracingEnabled(boolean toolTracingEnabled) {
        this.toolTracingEnabled = toolTracingEnabled;
    }

    public boolean isDecisionTracingEnabled() {
        return enabled && decisionTracingEnabled;
    }

    public void setDecisionTracingEnabled(boolean decisionTracingEnabled) {
        this.decisionTracingEnabled = decisionTracingEnabled;
    }

    public boolean isReplayEnabled() {
        return enabled && replayEnabled;
    }

    public void setReplayEnabled(boolean replayEnabled) {
        this.replayEnabled = replayEnabled;
    }

    public boolean isMetricsEnabled() {
        return enabled && metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public ReplayProperties getReplay() {
        return replay;
    }

    public void setReplay(ReplayProperties replay) {
        this.replay = replay;
    }

    public DecisionProperties getDecision() {
        return decision;
    }

    public void setDecision(DecisionProperties decision) {
        this.decision = decision;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    /**
     * Replay/event store configuration.
     */
    public static class ReplayProperties {

        /**
         * Type of event store: in-memory, file
         */
        private String storeType = "in-memory";

        /**
         * Directory for file-based event store.
         */
        private String storeDirectory = "./observability/events";

        /**
         * Number of days to retain events.
         */
        private int retentionDays = 7;

        /**
         * Maximum events to keep in memory (for in-memory store).
         */
        private int maxEventsInMemory = 10000;

        public String getStoreType() {
            return storeType;
        }

        public void setStoreType(String storeType) {
            this.storeType = storeType;
        }

        public String getStoreDirectory() {
            return storeDirectory;
        }

        public void setStoreDirectory(String storeDirectory) {
            this.storeDirectory = storeDirectory;
        }

        public int getRetentionDays() {
            return retentionDays;
        }

        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        public int getMaxEventsInMemory() {
            return maxEventsInMemory;
        }

        public void setMaxEventsInMemory(int maxEventsInMemory) {
            this.maxEventsInMemory = maxEventsInMemory;
        }
    }

    /**
     * Decision tracing configuration.
     */
    public static class DecisionProperties {

        /**
         * Capture the full prompts sent to LLM.
         */
        private boolean capturePrompts = true;

        /**
         * Capture the full LLM responses.
         */
        private boolean captureResponses = true;

        /**
         * Maximum prompt length to capture (truncate if longer).
         */
        private int maxPromptLength = 10000;

        /**
         * Maximum response length to capture (truncate if longer).
         */
        private int maxResponseLength = 10000;

        public boolean isCapturePrompts() {
            return capturePrompts;
        }

        public void setCapturePrompts(boolean capturePrompts) {
            this.capturePrompts = capturePrompts;
        }

        public boolean isCaptureResponses() {
            return captureResponses;
        }

        public void setCaptureResponses(boolean captureResponses) {
            this.captureResponses = captureResponses;
        }

        public int getMaxPromptLength() {
            return maxPromptLength;
        }

        public void setMaxPromptLength(int maxPromptLength) {
            this.maxPromptLength = maxPromptLength;
        }

        public int getMaxResponseLength() {
            return maxResponseLength;
        }

        public void setMaxResponseLength(int maxResponseLength) {
            this.maxResponseLength = maxResponseLength;
        }
    }

    /**
     * Metrics configuration.
     */
    public static class MetricsProperties {

        /**
         * Prefix for all SwarmAI metrics.
         */
        private String prefix = "swarm";

        /**
         * Enable histogram percentiles for latency metrics.
         */
        private boolean histogramPercentilesEnabled = true;

        /**
         * Percentiles to compute (e.g., 0.5, 0.9, 0.95, 0.99).
         */
        private double[] percentiles = {0.5, 0.9, 0.95, 0.99};

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public boolean isHistogramPercentilesEnabled() {
            return histogramPercentilesEnabled;
        }

        public void setHistogramPercentilesEnabled(boolean histogramPercentilesEnabled) {
            this.histogramPercentilesEnabled = histogramPercentilesEnabled;
        }

        public double[] getPercentiles() {
            return percentiles;
        }

        public void setPercentiles(double[] percentiles) {
            this.percentiles = percentiles;
        }
    }
}
