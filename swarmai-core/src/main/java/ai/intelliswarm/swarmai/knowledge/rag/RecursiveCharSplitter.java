package ai.intelliswarm.swarmai.knowledge.rag;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive character text splitter — same algorithm as
 * langchain's {@code RecursiveCharacterTextSplitter}. Tries paragraph breaks
 * first, then line breaks, then sentence boundaries, then words. Respects a
 * target chunk size and bleeds {@code overlap} chars from the previous chunk
 * into the next so formula/code spans aren't sliced mid-token.
 *
 * <p>Why recursive over fixed-size: the 2026-04-26 IntelliDoc eval showed
 * paragraph splitting under-segments slide-style PDFs (some chunks too big to
 * embed cleanly), while fixed-size cuts mid-formula. The recursive separator
 * cascade is the practical compromise.
 */
@PublicApi(since = "1.0.9")
public final class RecursiveCharSplitter {

    private static final String[] DEFAULT_SEPARATORS = {"\n\n", "\n", ". ", "; ", " "};

    private final int chunkSize;
    private final int overlap;
    private final String[] separators;

    public RecursiveCharSplitter(int chunkSize, int overlap) {
        this(chunkSize, overlap, DEFAULT_SEPARATORS);
    }

    public RecursiveCharSplitter(int chunkSize, int overlap, String[] separators) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be > 0");
        if (overlap < 0 || overlap >= chunkSize) throw new IllegalArgumentException("overlap must be in [0, chunkSize)");
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.separators = separators;
    }

    public List<String> split(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> chunks = new ArrayList<>();
        splitRecursive(text, 0, chunks);
        // Add overlap by re-emitting the tail of each previous chunk at the
        // start of the next one. Skip first chunk (no previous).
        if (overlap == 0 || chunks.size() < 2) return chunks;
        List<String> withOverlap = new ArrayList<>(chunks.size());
        withOverlap.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String tail = prev.length() > overlap ? prev.substring(prev.length() - overlap) : prev;
            withOverlap.add(tail + " " + chunks.get(i));
        }
        return withOverlap;
    }

    private void splitRecursive(String text, int sepIdx, List<String> out) {
        if (text.length() <= chunkSize) {
            if (!text.isBlank()) out.add(text);
            return;
        }
        if (sepIdx >= separators.length) {
            // No separators left — hard cut at chunkSize.
            for (int i = 0; i < text.length(); i += chunkSize) {
                String piece = text.substring(i, Math.min(text.length(), i + chunkSize));
                if (!piece.isBlank()) out.add(piece);
            }
            return;
        }
        String sep = separators[sepIdx];
        String[] pieces = text.split(java.util.regex.Pattern.quote(sep));
        StringBuilder buf = new StringBuilder();
        for (String piece : pieces) {
            if (buf.length() + piece.length() + sep.length() > chunkSize) {
                if (buf.length() > 0) {
                    if (buf.length() <= chunkSize) out.add(buf.toString());
                    else splitRecursive(buf.toString(), sepIdx + 1, out);
                    buf.setLength(0);
                }
                if (piece.length() > chunkSize) {
                    splitRecursive(piece, sepIdx + 1, out);
                    continue;
                }
            }
            if (buf.length() > 0) buf.append(sep);
            buf.append(piece);
        }
        if (buf.length() > 0) {
            if (buf.length() <= chunkSize) out.add(buf.toString());
            else splitRecursive(buf.toString(), sepIdx + 1, out);
        }
    }
}
