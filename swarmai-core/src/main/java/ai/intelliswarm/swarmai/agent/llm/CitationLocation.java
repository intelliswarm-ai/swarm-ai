package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Where in a source document a citation anchor points. Sealed so the
 * exhaustiveness of pattern-matching is checked at compile time.
 *
 * <p>Anthropic surfaces three location kinds:
 * <ul>
 *   <li>{@link CharLocation} — for plain-text documents, character offsets in source</li>
 *   <li>{@link PageLocation} — for PDFs, 1-based page range</li>
 *   <li>{@link ContentBlockLocation} — for custom-content documents, block-index range</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public sealed interface CitationLocation
        permits CitationLocation.CharLocation,
                CitationLocation.PageLocation,
                CitationLocation.ContentBlockLocation {

    /** Half-open char-offset range {@code [start, end)} inside a plain-text source. */
    @PublicApi(since = "1.0.24")
    record CharLocation(int startCharIndex, int endCharIndex) implements CitationLocation {
        public CharLocation {
            if (startCharIndex < 0 || endCharIndex < startCharIndex) {
                throw new IllegalArgumentException(
                        "invalid char range: [" + startCharIndex + ", " + endCharIndex + ")");
            }
        }
    }

    /** 1-based page range {@code [startPage, endPage]} inside a PDF document. */
    @PublicApi(since = "1.0.24")
    record PageLocation(int startPageNumber, int endPageNumber) implements CitationLocation {
        public PageLocation {
            if (startPageNumber < 1 || endPageNumber < startPageNumber) {
                throw new IllegalArgumentException(
                        "invalid page range: [" + startPageNumber + ", " + endPageNumber + "]");
            }
        }
    }

    /** Half-open block-index range {@code [start, end)} inside a custom-content document. */
    @PublicApi(since = "1.0.24")
    record ContentBlockLocation(int startBlockIndex, int endBlockIndex) implements CitationLocation {
        public ContentBlockLocation {
            if (startBlockIndex < 0 || endBlockIndex < startBlockIndex) {
                throw new IllegalArgumentException(
                        "invalid block range: [" + startBlockIndex + ", " + endBlockIndex + ")");
            }
        }
    }
}
