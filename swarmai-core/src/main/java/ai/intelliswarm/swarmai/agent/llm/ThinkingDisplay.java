package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * How thinking blocks are surfaced in the response.
 *
 * <ul>
 *   <li>{@link #FULL} — raw thinking deltas are streamed and stored. Most visibility,
 *       largest payload. Required for multi-turn continuity with signature verification.</li>
 *   <li>{@link #SUMMARIZED} — Claude returns a short summary instead of full chain.
 *       Default; balances cost and observability.</li>
 *   <li>{@link #OMITTED} — thinking happens but is not returned. Smallest payload,
 *       cannot be replayed.</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum ThinkingDisplay {
    FULL,
    SUMMARIZED,
    OMITTED
}
