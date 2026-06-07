package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Per-response token accounting. Superset of Anthropic's {@code usage} object
 * — also carries {@link #cacheUsage} and a server-side-tool token bucket so
 * the framework's {@code BudgetTracker} can charge each turn correctly.
 *
 * @param inputTokens             non-cached input tokens charged at 1×
 * @param outputTokens            response tokens (including thinking, when surfaced)
 * @param cacheUsage              cache hit/miss telemetry (never {@code null}; use {@link CacheUsage#NONE})
 * @param serverToolInputTokens   tokens charged for server-side tool inputs (web_search, code_execution, etc.)
 * @param serverToolOutputTokens  tokens charged for server-side tool outputs
 */
@PublicApi(since = "1.0.24")
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        CacheUsage cacheUsage,
        long serverToolInputTokens,
        long serverToolOutputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, CacheUsage.NONE, 0, 0);

    public TokenUsage {
        if (cacheUsage == null) {
            cacheUsage = CacheUsage.NONE;
        }
    }

    /** Total billable tokens after applying cache-read discount at 0.1× (rounded). */
    public long effectiveInputTokens() {
        long cacheRead = cacheUsage.cacheReadInputTokens();
        long cacheWrite5m = cacheUsage.cacheCreationFiveMinuteInputTokens();
        long cacheWrite1h = cacheUsage.cacheCreationOneHourInputTokens();
        // standard input at 1× + 5m writes at 1.25× + 1h writes at 2× + cache reads at 0.1×
        double weighted = inputTokens
                + 1.25 * cacheWrite5m
                + 2.0 * cacheWrite1h
                + 0.1 * cacheRead;
        return Math.round(weighted);
    }
}
