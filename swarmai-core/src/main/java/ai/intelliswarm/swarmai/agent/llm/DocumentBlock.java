package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * A document attached to an LLM request — the substrate that
 * {@link InlineCitation}s point into. Sealed so the citation renderer can
 * exhaustively pattern-match.
 *
 * <p>Three flavours mirror Anthropic's {@code document} content block:
 * <ul>
 *   <li>{@link Pdf}        — bytes or URL of a PDF (page-anchored citations)</li>
 *   <li>{@link PlainText}  — UTF-8 text body (char-anchored citations)</li>
 *   <li>{@link Custom}     — ordered list of opaque content blocks (block-anchored citations)</li>
 * </ul>
 *
 * <p>Every variant carries a stable {@code id} and optional {@code title} so
 * the same document can be referenced across turns and audit records can
 * resolve the original source.
 */
@PublicApi(since = "1.0.24")
public sealed interface DocumentBlock permits DocumentBlock.Pdf, DocumentBlock.PlainText, DocumentBlock.Custom {

    String id();
    String title();
    /** Whether the model should be asked to cite this document. */
    boolean citationsEnabled();

    /** PDF document, supplied either as base64 bytes or as a URL. */
    @PublicApi(since = "1.0.24")
    record Pdf(String id, String title, byte[] bytes, String url, boolean citationsEnabled)
            implements DocumentBlock {
        public Pdf {
            Objects.requireNonNull(id, "id must not be null");
            if ((bytes == null) == (url == null)) {
                throw new IllegalArgumentException("exactly one of bytes or url must be provided");
            }
        }

        public static Pdf ofBytes(String id, String title, byte[] bytes, boolean citationsEnabled) {
            return new Pdf(id, title, bytes, null, citationsEnabled);
        }

        public static Pdf ofUrl(String id, String title, String url, boolean citationsEnabled) {
            return new Pdf(id, title, null, url, citationsEnabled);
        }
    }

    /** Plain UTF-8 text. Citations anchor on character offsets. */
    @PublicApi(since = "1.0.24")
    record PlainText(String id, String title, String text, boolean citationsEnabled)
            implements DocumentBlock {
        public PlainText {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /**
     * Custom document broken into discrete content blocks. Use this when you
     * want granular citation anchors (one per logical section, paragraph, or row).
     */
    @PublicApi(since = "1.0.24")
    record Custom(String id, String title, List<String> contentBlocks, boolean citationsEnabled)
            implements DocumentBlock {
        public Custom {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(contentBlocks, "contentBlocks must not be null");
            if (contentBlocks.isEmpty()) {
                throw new IllegalArgumentException("contentBlocks must not be empty");
            }
            contentBlocks = List.copyOf(contentBlocks);
        }
    }
}
