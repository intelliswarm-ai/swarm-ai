package ai.intelliswarm.swarmai.budget;

/**
 * Static utility class for LLM token cost estimation.
 * Extracted from SwarmOutput.estimateCostUsd() to provide a single source of truth
 * for model pricing across the framework.
 *
 * <p>Pricing is per 1M tokens. Local/Ollama models are free by default.
 *
 * <p>From <b>1.0.25</b>, pricing tracks four tiers per model (input, cache-write,
 * cache-read, output) so that Anthropic prompt-caching savings show up correctly.
 * Older two-arg {@link #calculateCost(long, long, String)} continues to work and
 * ignores the cache tiers (treats them as zero).
 */
public final class LlmPricingModel {

    private LlmPricingModel() {
        // Static utility class - no instantiation
    }

    /**
     * Immutable pricing record for a given model tier.
     *
     * @param inputPer1M       cost in USD per 1 million uncached input tokens
     * @param cacheWritePer1M  cost in USD per 1 million tokens written to the prompt cache.
     *                         Anthropic typically charges ~1.25× input. Set to 0 for providers
     *                         that don't bill cache writes separately (OpenAI today).
     * @param cacheReadPer1M   cost in USD per 1 million tokens served from the prompt cache.
     *                         Anthropic typically charges ~0.10× input. Set to 0 when caching
     *                         is unsupported.
     * @param outputPer1M      cost in USD per 1 million output (completion) tokens
     */
    public record LlmPricing(double inputPer1M, double cacheWritePer1M,
                             double cacheReadPer1M, double outputPer1M) {

        /** Convenience ctor for providers without cache tiers (cache prices default to 0). */
        public LlmPricing(double inputPer1M, double outputPer1M) {
            this(inputPer1M, 0.0, 0.0, outputPer1M);
        }
    }

    /** Free tier for local / Ollama models. */
    private static final LlmPricing FREE = new LlmPricing(0.00, 0.00, 0.00, 0.00);

    /**
     * Returns the pricing tier that matches the given model name.
     * Matching is case-insensitive and substring-based so that names like
     * "gpt-4o-mini-2024-07-18" still resolve correctly.
     *
     * @param modelName the model identifier (may be null)
     * @return the corresponding {@link LlmPricing}, never null
     */
    public static LlmPricing getPricing(String modelName) {
        if (modelName == null) {
            return FREE;
        }
        String model = modelName.toLowerCase();

        // OpenAI models -- order matters: more specific first.
        // OpenAI doesn't bill cache tiers separately as of 2026; cache fields are 0.
        if (model.contains("gpt-4.1-nano")) {
            return new LlmPricing(0.10, 0.0, 0.0, 0.40);
        }
        if (model.contains("gpt-4.1-mini")) {
            return new LlmPricing(0.40, 0.0, 0.0, 1.60);
        }
        if (model.contains("gpt-4.1")) {
            return new LlmPricing(2.00, 0.0, 0.0, 8.00);
        }
        // Legacy gpt-4o-mini ($0.15/$0.60) — kept distinct from 4.1-mini.
        if (model.contains("gpt-4o-mini")) {
            return new LlmPricing(0.15, 0.0, 0.0, 0.60);
        }
        if (model.contains("gpt-4o")) {
            return new LlmPricing(2.50, 0.0, 0.0, 10.00);
        }
        if (model.contains("gpt-4-turbo") || model.contains("gpt-4")) {
            return new LlmPricing(10.00, 0.0, 0.0, 30.00);
        }
        if (model.contains("gpt-3.5")) {
            return new LlmPricing(0.50, 0.0, 0.0, 1.50);
        }

        // Anthropic models — full four-tier pricing for current generations,
        // legacy 2-tier for claude-3-haiku (the only model whose dollar rate
        // changed materially between generations).
        if (model.contains("claude-3-haiku") || model.contains("claude-haiku-3")) {
            return new LlmPricing(0.25, 0.0, 0.0, 1.25);
        }
        if (model.contains("claude") && model.contains("haiku")) {
            return new LlmPricing(1.00, 1.25, 0.10, 5.00);
        }
        if (model.contains("claude") && model.contains("sonnet")) {
            return new LlmPricing(3.00, 3.75, 0.30, 15.00);
        }
        if (model.contains("claude") && model.contains("opus")) {
            return new LlmPricing(15.00, 18.75, 1.50, 75.00);
        }

        // Ollama / local models
        if (model.contains("ollama") || model.contains("llama")
                || model.contains("mistral") || model.contains("phi")
                || model.contains("gemma") || model.contains("codellama")
                || model.contains("qwen") || model.contains("glm")
                || model.contains("deepseek")) {
            return FREE;
        }

        // Default: free (unknown model, assume local)
        return FREE;
    }

    /**
     * Calculates the estimated cost in USD for a single LLM call.
     * Two-tier overload kept for backward compatibility — assumes no prompt caching.
     *
     * @param promptTokens     number of input tokens consumed
     * @param completionTokens number of output tokens generated
     * @param modelName        the model identifier used for pricing lookup
     * @return estimated cost in USD ({@code >= 0})
     */
    public static double calculateCost(long promptTokens, long completionTokens, String modelName) {
        return calculateCost(promptTokens, 0, 0, completionTokens, modelName);
    }

    /**
     * Calculates the estimated cost in USD with four-tier billing
     * (uncached-input / cache-write / cache-read / output).
     *
     * @since 1.0.25
     */
    public static double calculateCost(long promptTokens, long cacheWriteTokens,
                                       long cacheReadTokens, long completionTokens,
                                       String modelName) {
        LlmPricing p = getPricing(modelName);
        return (promptTokens     / 1_000_000.0) * p.inputPer1M()
             + (cacheWriteTokens / 1_000_000.0) * p.cacheWritePer1M()
             + (cacheReadTokens  / 1_000_000.0) * p.cacheReadPer1M()
             + (completionTokens / 1_000_000.0) * p.outputPer1M();
    }

    /**
     * Total billable input as if nothing were cached — useful for fair
     * model-to-model comparisons that should ignore the caching advantage.
     *
     * @since 1.0.25
     */
    public static long effectiveInputTokens(long promptTokens, long cacheWriteTokens, long cacheReadTokens) {
        return promptTokens + cacheWriteTokens + cacheReadTokens;
    }

    /**
     * Cache hit rate: fraction of total input tokens served from the prompt cache.
     * Returns 0.0 when no input tokens were spent.
     *
     * @since 1.0.25
     */
    public static double cacheHitRate(long promptTokens, long cacheWriteTokens, long cacheReadTokens) {
        long total = promptTokens + cacheWriteTokens + cacheReadTokens;
        return total == 0 ? 0.0 : (double) cacheReadTokens / total;
    }

    /**
     * Estimated USD saved by prompt caching, vs the all-uncached counterfactual.
     * Returns 0.0 when the model has no cache tier configured.
     *
     * @since 1.0.25
     */
    public static double savedByCaching(long cacheWriteTokens, long cacheReadTokens, String modelName) {
        LlmPricing p = getPricing(modelName);
        double cachedCost   = (cacheReadTokens  / 1_000_000.0) * p.cacheReadPer1M()
                            + (cacheWriteTokens / 1_000_000.0) * p.cacheWritePer1M();
        double uncachedCost = (cacheReadTokens + cacheWriteTokens) / 1_000_000.0 * p.inputPer1M();
        return Math.max(0.0, uncachedCost - cachedCost);
    }
}
