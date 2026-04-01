package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.config.ModelContextConfig;

/**
 * Configuration for auto-compaction of conversation history during reactive agent execution.
 * When cumulative tokens exceed the threshold, older turns are summarized and replaced
 * with a compact context, while recent turns are preserved verbatim.
 *
 * @param preserveRecentTurns       number of most recent turns to keep verbatim (default: 4)
 * @param compactionThresholdTokens cumulative token count that triggers compaction
 * @param enabled                   whether auto-compaction is active
 */
public record CompactionConfig(
        int preserveRecentTurns,
        long compactionThresholdTokens,
        boolean enabled
) {
    private static final int DEFAULT_PRESERVE_RECENT = 4;

    /**
     * Creates a config tuned for the given model's context window.
     * Triggers compaction at 80% of the context window.
     */
    public static CompactionConfig forModel(ModelContextConfig contextConfig) {
        long threshold = (long) (contextConfig.getContextWindowTokens() * 0.8);
        return new CompactionConfig(DEFAULT_PRESERVE_RECENT, threshold, true);
    }

    /**
     * Creates a config with a specific token threshold.
     */
    public static CompactionConfig withThreshold(long thresholdTokens) {
        return new CompactionConfig(DEFAULT_PRESERVE_RECENT, thresholdTokens, true);
    }

    /**
     * Creates a config with custom settings.
     */
    public static CompactionConfig of(int preserveRecent, long thresholdTokens) {
        return new CompactionConfig(preserveRecent, thresholdTokens, true);
    }

    /**
     * Returns a disabled config (compaction never triggers).
     */
    public static CompactionConfig disabled() {
        return new CompactionConfig(DEFAULT_PRESERVE_RECENT, Long.MAX_VALUE, false);
    }
}
