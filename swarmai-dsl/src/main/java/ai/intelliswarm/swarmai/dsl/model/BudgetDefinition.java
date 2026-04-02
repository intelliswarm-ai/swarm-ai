package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for budget policy.
 *
 * <pre>{@code
 * budget:
 *   maxTokens: 100000
 *   maxCostUsd: 5.0
 *   onExceeded: WARN
 *   warningThresholdPercent: 80.0
 * }</pre>
 */
public class BudgetDefinition {

    @JsonProperty("maxTokens")
    private Long maxTokens;

    @JsonProperty("maxCostUsd")
    private Double maxCostUsd;

    @JsonProperty("onExceeded")
    private String onExceeded;

    @JsonProperty("warningThresholdPercent")
    private Double warningThresholdPercent;

    // --- Getters & Setters ---

    public Long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Long maxTokens) { this.maxTokens = maxTokens; }

    public Double getMaxCostUsd() { return maxCostUsd; }
    public void setMaxCostUsd(Double maxCostUsd) { this.maxCostUsd = maxCostUsd; }

    public String getOnExceeded() { return onExceeded; }
    public void setOnExceeded(String onExceeded) { this.onExceeded = onExceeded; }

    public Double getWarningThresholdPercent() { return warningThresholdPercent; }
    public void setWarningThresholdPercent(Double warningThresholdPercent) { this.warningThresholdPercent = warningThresholdPercent; }
}
