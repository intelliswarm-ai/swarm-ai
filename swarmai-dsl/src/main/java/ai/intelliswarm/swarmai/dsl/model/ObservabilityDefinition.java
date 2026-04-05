package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for observability configuration.
 *
 * <pre>{@code
 * observability:
 *   enabled: true
 *   structuredLogging: true
 *   toolTracing: true
 *   decisionTracing: false
 *   metricsEnabled: true
 *   replay:
 *     enabled: true
 *     storeType: in-memory
 *     retentionDays: 7
 *     maxEvents: 10000
 *   decision:
 *     capturePrompts: true
 *     captureResponses: true
 *     maxPromptLength: 10000
 *     maxResponseLength: 10000
 * }</pre>
 */
public class ObservabilityDefinition {

    private boolean enabled = true;

    @JsonProperty("structuredLogging")
    private boolean structuredLogging = true;

    @JsonProperty("toolTracing")
    private boolean toolTracing = true;

    @JsonProperty("decisionTracing")
    private boolean decisionTracing = false;

    @JsonProperty("metricsEnabled")
    private boolean metricsEnabled = true;

    private ReplayDefinition replay;
    private DecisionDefinition decision;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isStructuredLogging() { return structuredLogging; }
    public void setStructuredLogging(boolean v) { this.structuredLogging = v; }

    public boolean isToolTracing() { return toolTracing; }
    public void setToolTracing(boolean v) { this.toolTracing = v; }

    public boolean isDecisionTracing() { return decisionTracing; }
    public void setDecisionTracing(boolean v) { this.decisionTracing = v; }

    public boolean isMetricsEnabled() { return metricsEnabled; }
    public void setMetricsEnabled(boolean v) { this.metricsEnabled = v; }

    public ReplayDefinition getReplay() { return replay; }
    public void setReplay(ReplayDefinition replay) { this.replay = replay; }

    public DecisionDefinition getDecision() { return decision; }
    public void setDecision(DecisionDefinition decision) { this.decision = decision; }

    public static class ReplayDefinition {
        private boolean enabled = true;

        @JsonProperty("storeType")
        private String storeType = "in-memory";

        @JsonProperty("retentionDays")
        private int retentionDays = 7;

        @JsonProperty("maxEvents")
        private int maxEvents = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public String getStoreType() { return storeType; }
        public void setStoreType(String v) { this.storeType = v; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int v) { this.retentionDays = v; }

        public int getMaxEvents() { return maxEvents; }
        public void setMaxEvents(int v) { this.maxEvents = v; }
    }

    public static class DecisionDefinition {
        @JsonProperty("capturePrompts")
        private boolean capturePrompts = true;

        @JsonProperty("captureResponses")
        private boolean captureResponses = true;

        @JsonProperty("maxPromptLength")
        private int maxPromptLength = 10000;

        @JsonProperty("maxResponseLength")
        private int maxResponseLength = 10000;

        public boolean isCapturePrompts() { return capturePrompts; }
        public void setCapturePrompts(boolean v) { this.capturePrompts = v; }

        public boolean isCaptureResponses() { return captureResponses; }
        public void setCaptureResponses(boolean v) { this.captureResponses = v; }

        public int getMaxPromptLength() { return maxPromptLength; }
        public void setMaxPromptLength(int v) { this.maxPromptLength = v; }

        public int getMaxResponseLength() { return maxResponseLength; }
        public void setMaxResponseLength(int v) { this.maxResponseLength = v; }
    }
}
