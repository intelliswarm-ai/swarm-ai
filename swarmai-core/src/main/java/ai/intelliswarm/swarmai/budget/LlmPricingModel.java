package ai.intelliswarm.swarmai.budget;

/**
 * Static utility class for LLM token cost estimation.
 * Extracted from SwarmOutput.estimateCostUsd() to provide a single source of truth
 * for model pricing across the framework.
 *
 * Pricing is per 1M tokens. Local/Ollama models are free by default.
 */
public final class LlmPricingModel {

    private LlmPricingModel() {
        // Static utility class - no instantiation
    }

    /**
     * Immutable pricing record for a given model tier.
     *
     * @param inputPer1M  cost in USD per 1 million input (prompt) tokens
     * @param outputPer1M cost in USD per 1 million output (completion) tokens
     */
    public record LlmPricing(double inputPer1M, double outputPer1M) {}

    /** Free tier for local / Ollama models. */
    private static final LlmPricing FREE = new LlmPricing(0.00, 0.00);

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

        // OpenAI models -- order matters: more specific first
        if (model.contains("gpt-4o-mini")) {
            return new LlmPricing(0.15, 0.60);
        }
        if (model.contains("gpt-4o")) {
            return new LlmPricing(2.50, 10.00);
        }
        if (model.contains("gpt-4-turbo") || model.contains("gpt-4")) {
            return new LlmPricing(10.00, 30.00);
        }
        if (model.contains("gpt-3.5")) {
            return new LlmPricing(0.50, 1.50);
        }

        // Anthropic models
        if (model.contains("claude") && model.contains("sonnet")) {
            return new LlmPricing(3.00, 15.00);
        }
        if (model.contains("claude") && model.contains("haiku")) {
            return new LlmPricing(0.25, 1.25);
        }
        if (model.contains("claude") && model.contains("opus")) {
            return new LlmPricing(15.00, 75.00);
        }

        // Ollama / local models
        if (model.contains("ollama") || model.contains("llama")
                || model.contains("mistral") || model.contains("phi")
                || model.contains("gemma") || model.contains("codellama")) {
            return FREE;
        }

        // Default: free (unknown model, assume local)
        return FREE;
    }

    /**
     * Calculates the estimated cost in USD for a single LLM call.
     *
     * @param promptTokens     number of input tokens consumed
     * @param completionTokens number of output tokens generated
     * @param modelName        the model identifier used for pricing lookup
     * @return estimated cost in USD (>= 0)
     */
    public static double calculateCost(long promptTokens, long completionTokens, String modelName) {
        LlmPricing pricing = getPricing(modelName);
        double inputCost = (promptTokens / 1_000_000.0) * pricing.inputPer1M();
        double outputCost = (completionTokens / 1_000_000.0) * pricing.outputPer1M();
        return inputCost + outputCost;
    }
}
