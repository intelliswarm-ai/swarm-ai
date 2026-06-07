package ai.intelliswarm.swarmai.budget;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Tracks token usage and estimated cost across workflow executions.
 * Implementations must be thread-safe.
 *
 * A null BudgetTracker reference in consuming code means budget enforcement
 * is disabled -- callers should null-check before invoking methods.
 */
@PublicApi(since = "1.0")
public interface BudgetTracker {

    /**
     * Records a single LLM call's token usage for the given workflow.
     * Cost is calculated automatically using {@link LlmPricingModel}.
     *
     * @param workflowId       identifier of the workflow
     * @param promptTokens     number of input tokens consumed
     * @param completionTokens number of output tokens generated
     * @param modelName        the model used (for cost calculation)
     * @throws BudgetExceededException if the policy action is HARD_STOP and the budget is exceeded
     */
    void recordUsage(String workflowId, long promptTokens, long completionTokens, String modelName);

    /**
     * Records a single LLM call's token usage including prompt-cache breakdown
     * (cache-write + cache-read). Cost is calculated via the 4-tier overload of
     * {@link LlmPricingModel#calculateCost(long, long, long, long, String)} so that
     * Anthropic prompt-caching savings show up correctly on the snapshot.
     *
     * <p>The default implementation forwards to the legacy 2-tier method by summing
     * all input-side tokens — implementations that want accurate cache-aware
     * accounting should override this method.
     *
     * @since 1.0.26
     */
    default void recordUsage(String workflowId, long promptTokens, long cacheWriteTokens,
                             long cacheReadTokens, long completionTokens, String modelName) {
        recordUsage(workflowId, promptTokens + cacheWriteTokens + cacheReadTokens,
                completionTokens, modelName);
    }

    /**
     * Returns a point-in-time snapshot of budget usage for the given workflow.
     *
     * @param workflowId identifier of the workflow
     * @return the current budget snapshot, never null
     */
    BudgetSnapshot getSnapshot(String workflowId);

    /**
     * Checks whether the budget has been exceeded for the given workflow.
     *
     * @param workflowId identifier of the workflow
     * @return true if either token or cost budget is exceeded
     */
    boolean isExceeded(String workflowId);

    /**
     * Sets or replaces the budget policy for the given workflow.
     *
     * @param workflowId identifier of the workflow
     * @param policy     the budget policy to enforce
     */
    void setBudgetPolicy(String workflowId, BudgetPolicy policy);

    /**
     * Resets all usage counters for the given workflow.
     * The budget policy remains in place.
     *
     * @param workflowId identifier of the workflow
     */
    void reset(String workflowId);
}
