package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Extended-thinking mode. {@link #DISABLED} skips thinking entirely (cheapest);
 * {@link #ENABLED} dedicates {@link ThinkingBudget#budgetTokens()} to a
 * pre-response reasoning pass; {@link #INTERLEAVED} additionally allows the
 * model to think between tool calls (Anthropic beta
 * {@code interleaved-thinking-2025-05-14}).
 */
@PublicApi(since = "1.0.24")
public enum ThinkingMode {
    DISABLED,
    ENABLED,
    INTERLEAVED
}
