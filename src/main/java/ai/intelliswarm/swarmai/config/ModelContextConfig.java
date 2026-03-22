package ai.intelliswarm.swarmai.config;

/**
 * Model context window configuration.
 * Provides context window sizes for known models and calculates
 * optimal budget allocations for different prompt sections.
 *
 * Token-to-char ratio: approximately 1 token ≈ 4 characters (for English text).
 */
public class ModelContextConfig {

    private static final int CHARS_PER_TOKEN = 4;

    private final int contextWindowTokens;
    private final int contextWindowChars;

    // Budget allocation percentages
    private static final double SYSTEM_PROMPT_BUDGET = 0.10;   // 10%
    private static final double TOOL_RESPONSE_BUDGET = 0.30;   // 30%
    private static final double PRIOR_CONTEXT_BUDGET = 0.30;   // 30%
    private static final double TASK_DESCRIPTION_BUDGET = 0.20; // 20%
    private static final double COMPLETION_RESERVE = 0.10;      // 10% reserved for output

    private ModelContextConfig(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
        this.contextWindowChars = contextWindowTokens * CHARS_PER_TOKEN;
    }

    /**
     * Creates a ModelContextConfig based on the model name.
     * Looks up known context window sizes for popular models.
     */
    public static ModelContextConfig forModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return new ModelContextConfig(16_000); // conservative default
        }

        String model = modelName.toLowerCase();

        // OpenAI models
        if (model.contains("gpt-4o-mini")) return new ModelContextConfig(128_000);
        if (model.contains("gpt-4o")) return new ModelContextConfig(128_000);
        if (model.contains("gpt-4-turbo")) return new ModelContextConfig(128_000);
        if (model.contains("gpt-4")) return new ModelContextConfig(8_192);
        if (model.contains("gpt-3.5-turbo-16k")) return new ModelContextConfig(16_384);
        if (model.contains("gpt-3.5")) return new ModelContextConfig(4_096);
        if (model.contains("o1") || model.contains("o3")) return new ModelContextConfig(200_000);

        // Anthropic models
        if (model.contains("claude-3") || model.contains("claude-4")) return new ModelContextConfig(200_000);
        if (model.contains("claude-2")) return new ModelContextConfig(100_000);
        if (model.contains("claude")) return new ModelContextConfig(200_000);

        // Ollama / local models
        if (model.contains("llama3.2") || model.contains("llama-3.2")) return new ModelContextConfig(128_000);
        if (model.contains("llama3.1") || model.contains("llama-3.1")) return new ModelContextConfig(128_000);
        if (model.contains("llama3") || model.contains("llama-3")) return new ModelContextConfig(8_192);
        if (model.contains("llama2") || model.contains("llama-2")) return new ModelContextConfig(4_096);
        if (model.contains("mistral")) return new ModelContextConfig(32_000);
        if (model.contains("mixtral")) return new ModelContextConfig(32_000);
        if (model.contains("phi3") || model.contains("phi-3")) return new ModelContextConfig(128_000);
        if (model.contains("gemma")) return new ModelContextConfig(8_192);
        if (model.contains("codellama")) return new ModelContextConfig(16_384);
        if (model.contains("deepseek")) return new ModelContextConfig(64_000);
        if (model.contains("qwen")) return new ModelContextConfig(32_000);

        // Google models
        if (model.contains("gemini-1.5-pro")) return new ModelContextConfig(1_000_000);
        if (model.contains("gemini-1.5-flash")) return new ModelContextConfig(1_000_000);
        if (model.contains("gemini")) return new ModelContextConfig(32_000);

        // Conservative default for unknown models
        return new ModelContextConfig(16_000);
    }

    /**
     * Creates a config with a specific context window size.
     */
    public static ModelContextConfig withTokens(int contextWindowTokens) {
        return new ModelContextConfig(contextWindowTokens);
    }

    // Budget calculations (in characters)

    public int getMaxSystemPromptChars() {
        return (int) (contextWindowChars * SYSTEM_PROMPT_BUDGET);
    }

    public int getMaxToolResponseChars() {
        return (int) (contextWindowChars * TOOL_RESPONSE_BUDGET);
    }

    public int getMaxPriorContextChars() {
        return (int) (contextWindowChars * PRIOR_CONTEXT_BUDGET);
    }

    public int getMaxTaskDescriptionChars() {
        return (int) (contextWindowChars * TASK_DESCRIPTION_BUDGET);
    }

    public int getMaxTotalPromptChars() {
        return (int) (contextWindowChars * (1.0 - COMPLETION_RESERVE));
    }

    // Raw accessors

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public int getContextWindowChars() {
        return contextWindowChars;
    }

    @Override
    public String toString() {
        return String.format("ModelContextConfig{tokens=%,d, chars=%,d, maxPrompt=%,d chars}",
                contextWindowTokens, contextWindowChars, getMaxTotalPromptChars());
    }
}
