package ai.intelliswarm.swarmai.budget;

/**
 * Immutable point-in-time snapshot of budget usage for a workflow.
 * Contains both raw token/cost counters and computed utilization percentages.
 *
 * @param promptTokensUsed        total prompt (input) tokens consumed
 * @param completionTokensUsed    total completion (output) tokens consumed
 * @param totalTokensUsed         sum of prompt + completion tokens
 * @param estimatedCostUsd        estimated total cost in USD
 * @param tokenUtilizationPercent percentage of the token budget consumed (0-100+)
 * @param costUtilizationPercent  percentage of the cost budget consumed (0-100+)
 * @param tokenBudgetExceeded     true if totalTokensUsed >= policy maxTotalTokens
 * @param costBudgetExceeded      true if estimatedCostUsd >= policy maxCostUsd
 */
public record BudgetSnapshot(
        long promptTokensUsed,
        long completionTokensUsed,
        long totalTokensUsed,
        double estimatedCostUsd,
        double tokenUtilizationPercent,
        double costUtilizationPercent,
        boolean tokenBudgetExceeded,
        boolean costBudgetExceeded
) {

    /**
     * Returns true if either the token budget or cost budget has been exceeded.
     */
    public boolean isExceeded() {
        return tokenBudgetExceeded || costBudgetExceeded;
    }

    /**
     * Returns true if either utilization percentage exceeds the given warning threshold.
     *
     * @param thresholdPercent the warning threshold (e.g. 80.0 for 80%)
     * @return true if the workflow is approaching its budget limit
     */
    public boolean isWarning(double thresholdPercent) {
        return tokenUtilizationPercent > thresholdPercent
                || costUtilizationPercent > thresholdPercent;
    }
}
