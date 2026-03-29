package ai.intelliswarm.swarmai.budget.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SwarmAI token/cost budget tracker.
 *
 * <pre>
 * swarmai:
 *   budget:
 *     enabled: true
 *     default-max-tokens: 1000000
 *     default-max-cost-usd: 10.0
 *     default-action: WARN
 *     warning-threshold-percent: 80.0
 * </pre>
 */
@ConfigurationProperties(prefix = "swarmai.budget")
public class BudgetProperties {

    /**
     * Master switch for budget tracking. When false, no BudgetTracker bean is created.
     */
    private boolean enabled = false;

    /**
     * Default maximum total tokens (prompt + completion) per workflow.
     */
    private long defaultMaxTokens = 1_000_000L;

    /**
     * Default maximum estimated cost in USD per workflow.
     */
    private double defaultMaxCostUsd = 10.0;

    /**
     * Default action when budget is exceeded: WARN or HARD_STOP.
     */
    private String defaultAction = "WARN";

    /**
     * Utilization percentage at which budget warnings are emitted.
     */
    private double warningThresholdPercent = 80.0;

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    public void setDefaultMaxTokens(long defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
    }

    public double getDefaultMaxCostUsd() {
        return defaultMaxCostUsd;
    }

    public void setDefaultMaxCostUsd(double defaultMaxCostUsd) {
        this.defaultMaxCostUsd = defaultMaxCostUsd;
    }

    public String getDefaultAction() {
        return defaultAction;
    }

    public void setDefaultAction(String defaultAction) {
        this.defaultAction = defaultAction;
    }

    public double getWarningThresholdPercent() {
        return warningThresholdPercent;
    }

    public void setWarningThresholdPercent(double warningThresholdPercent) {
        this.warningThresholdPercent = warningThresholdPercent;
    }
}
