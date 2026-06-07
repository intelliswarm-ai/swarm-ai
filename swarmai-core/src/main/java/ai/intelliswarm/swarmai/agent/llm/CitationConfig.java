package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Citation-mode toggle. When {@code enabled} is true and at least one
 * {@link DocumentBlock} is attached, the model is asked to emit
 * {@link InlineCitation}s for each factual claim.
 *
 * @param enabled true to request citations; defaults to false
 */
@PublicApi(since = "1.0.24")
public record CitationConfig(boolean enabled) {

    public static final CitationConfig DISABLED = new CitationConfig(false);
    public static final CitationConfig ENABLED = new CitationConfig(true);
}
