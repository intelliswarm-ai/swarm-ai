package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Duration;

/**
 * Prompt-cache TTL tier. Anthropic exposes two tiers; the cost model differs:
 * a {@link #FIVE_MINUTES} write costs 1.25× a normal write while a {@link #ONE_HOUR}
 * write costs 2×. Cache reads are 0.1× the input token price regardless of tier.
 */
@PublicApi(since = "1.0.24")
public enum CacheTier {

    FIVE_MINUTES(Duration.ofMinutes(5), "5m"),
    ONE_HOUR(Duration.ofHours(1), "1h");

    private final Duration ttl;
    private final String wireValue;

    CacheTier(Duration ttl, String wireValue) {
        this.ttl = ttl;
        this.wireValue = wireValue;
    }

    public Duration ttl() {
        return ttl;
    }

    /** The string Anthropic's {@code cache_control.ttl} field expects. */
    public String wireValue() {
        return wireValue;
    }
}
