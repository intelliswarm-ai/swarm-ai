package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * One source citation extracted from a {@link RagAnswer}. Parsed from the
 * {@code [source: FILENAME #N]} tag the synthesis prompt asks the model to
 * append to every factual sentence.
 *
 * @param sourceId   logical source name (typically the file name)
 * @param chunkIndex zero-based chunk index inside that source
 * @param passage    the chunk's text content (truncated to ~240 chars for UI)
 * @param page       page number when the source has page tracking (e.g. PDFs); {@code null} otherwise
 */
@PublicApi(since = "1.0.9")
public record Citation(String sourceId, int chunkIndex, String passage, Integer page) {
    public Citation(String sourceId, int chunkIndex, String passage) {
        this(sourceId, chunkIndex, passage, null);
    }
}
