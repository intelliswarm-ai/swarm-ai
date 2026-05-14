package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Per-response cache telemetry returned by the LLM. All fields are token counts.
 * A non-zero {@link #cacheReadInputTokens} indicates a cache hit; a non-zero
 * {@link #cacheCreationFiveMinuteInputTokens} or
 * {@link #cacheCreationOneHourInputTokens} indicates the request paid the
 * write premium to seed the cache for later turns.
 *
 * <p>Use {@link #savingsPercent(long)} for a quick effective-cost view in dashboards.
 */
@PublicApi(since = "1.0.24")
public record CacheUsage(
        long cacheReadInputTokens,
        long cacheCreationFiveMinuteInputTokens,
        long cacheCreationOneHourInputTokens) {

    public static final CacheUsage NONE = new CacheUsage(0, 0, 0);

    public boolean hadCacheHit() {
        return cacheReadInputTokens > 0;
    }

    public long totalCacheCreationTokens() {
        return cacheCreationFiveMinuteInputTokens + cacheCreationOneHourInputTokens;
    }

    /**
     * Rough effective-savings calculation: tokens read from cache (charged at
     * 0.1×) versus the same tokens at standard input pricing. Returns 0 when
     * {@code totalInputTokens} is 0 to avoid divide-by-zero.
     *
     * @param totalInputTokens total input tokens for the request
     * @return percentage in [0,90] representing how much of the input cost the cache absorbed
     */
    public double savingsPercent(long totalInputTokens) {
        if (totalInputTokens <= 0 || cacheReadInputTokens <= 0) {
            return 0.0;
        }
        // cache reads are 0.1× — savings = 0.9 × (cacheRead / total)
        double share = (double) cacheReadInputTokens / (double) totalInputTokens;
        return Math.min(90.0, share * 90.0);
    }
}
