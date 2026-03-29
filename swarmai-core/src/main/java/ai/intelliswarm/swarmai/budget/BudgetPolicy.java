package ai.intelliswarm.swarmai.budget;

import java.util.Objects;

/**
 * Immutable policy that governs token and cost budget limits for a workflow.
 * Create instances via {@link BudgetPolicy#builder()}.
 *
 * @param maxTotalTokens          maximum total tokens (prompt + completion) allowed
 * @param maxCostUsd              maximum estimated cost in USD allowed
 * @param modelName               default model name used for cost calculation
 * @param onExceeded              action to take when the budget is exceeded
 * @param warningThresholdPercent utilization percentage at which warnings are emitted
 */
public record BudgetPolicy(
        long maxTotalTokens,
        double maxCostUsd,
        String modelName,
        BudgetAction onExceeded,
        double warningThresholdPercent
) {

    /**
     * Action to take when a budget limit is exceeded.
     */
    public enum BudgetAction {
        /** Log a warning but allow execution to continue. */
        WARN,
        /** Throw a {@link BudgetExceededException} to halt execution. */
        HARD_STOP
    }

    /** Default maximum total tokens: 1 million. */
    public static final long DEFAULT_MAX_TOTAL_TOKENS = 1_000_000L;

    /** Default maximum cost: $10 USD. */
    public static final double DEFAULT_MAX_COST_USD = 10.0;

    /** Default warning threshold: 80%. */
    public static final double DEFAULT_WARNING_THRESHOLD_PERCENT = 80.0;

    /**
     * Creates a new builder pre-populated with sensible defaults.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link BudgetPolicy}.
     */
    public static final class Builder {
        private long maxTotalTokens = DEFAULT_MAX_TOTAL_TOKENS;
        private double maxCostUsd = DEFAULT_MAX_COST_USD;
        private String modelName = null;
        private BudgetAction onExceeded = BudgetAction.WARN;
        private double warningThresholdPercent = DEFAULT_WARNING_THRESHOLD_PERCENT;

        private Builder() {}

        public Builder maxTotalTokens(long maxTotalTokens) {
            this.maxTotalTokens = maxTotalTokens;
            return this;
        }

        public Builder maxCostUsd(double maxCostUsd) {
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder onExceeded(BudgetAction onExceeded) {
            this.onExceeded = Objects.requireNonNull(onExceeded, "onExceeded must not be null");
            return this;
        }

        public Builder warningThresholdPercent(double warningThresholdPercent) {
            this.warningThresholdPercent = warningThresholdPercent;
            return this;
        }

        public BudgetPolicy build() {
            return new BudgetPolicy(
                    maxTotalTokens,
                    maxCostUsd,
                    modelName,
                    onExceeded,
                    warningThresholdPercent
            );
        }
    }
}
