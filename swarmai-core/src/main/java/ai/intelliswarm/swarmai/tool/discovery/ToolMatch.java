package ai.intelliswarm.swarmai.tool.discovery;

import java.util.Objects;

/**
 * A single search result from {@link ToolSearchService#search(String, int)}.
 *
 * @param entry the catalogued tool that matched
 * @param score relevance score; higher means more relevant. Scoring is internal
 *              to {@link ToolSearchService} — agents should treat it as opaque
 *              ordering signal, not a probability or confidence.
 */
public record ToolMatch(CatalogEntry entry, double score) {
    public ToolMatch {
        Objects.requireNonNull(entry, "entry");
        if (Double.isNaN(score) || score < 0) {
            throw new IllegalArgumentException("score must be a non-negative finite double, got: " + score);
        }
    }
}
