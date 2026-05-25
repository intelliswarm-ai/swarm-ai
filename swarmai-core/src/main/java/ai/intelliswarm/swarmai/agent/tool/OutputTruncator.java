package ai.intelliswarm.swarmai.agent.tool;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Uniform truncation policy for tool output. Caps a string at the smaller of
 * a byte budget OR a line budget so a runaway grep / file_read can't blow
 * through a model's context window on a single call. Pi documents the same
 * "50KB / 2000 lines, whichever hits first" rule for its extension authors.
 *
 * <p>Two flavors:
 * <ul>
 *   <li>{@link #truncateTail(String, int, int)} — keep the head; drop the
 *       tail. Good default for tool output where the start usually carries
 *       the most relevant context.</li>
 *   <li>{@link #truncateHead(String, int, int)} — keep the tail; drop the
 *       head. Right for log tails (test failures, error stacks) where the
 *       latest lines matter most.</li>
 * </ul>
 *
 * <p>Both append a single-line marker noting how much was dropped so the
 * LLM can decide to re-fetch with a tighter query.
 *
 * @since 1.0.28
 */
@PublicApi(since = "1.0.28")
public final class OutputTruncator {

    /** Default byte budget — roughly 10k tokens of plain text. */
    public static final int DEFAULT_MAX_BYTES = 50_000;
    /** Default line budget — covers most file_reads + grep batches. */
    public static final int DEFAULT_MAX_LINES = 2_000;

    private OutputTruncator() {}

    /** Truncate keeping the head; default 50KB / 2000 lines. */
    public static String truncateTail(String s) {
        return truncateTail(s, DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES);
    }

    /**
     * Truncate keeping the head. Returns the original when both budgets are
     * satisfied; otherwise truncates at the tighter of the two limits and
     * appends a single-line marker noting how much was dropped.
     */
    public static String truncateTail(String s, int maxBytes, int maxLines) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;

        int byteCap = budget(maxBytes, DEFAULT_MAX_BYTES);
        int lineCap = budget(maxLines, DEFAULT_MAX_LINES);

        int totalLines = countLines(s);
        boolean overBytes = s.length() > byteCap;
        boolean overLines = totalLines > lineCap;
        if (!overBytes && !overLines) return s;

        // Find the cut point: min of the byte cap and the start of line (lineCap + 1).
        int byteCut = overBytes ? byteCap : s.length();
        int lineCut = overLines ? indexOfNthLineStart(s, lineCap) : s.length();
        int cut = Math.min(byteCut, lineCut);
        // Snap back to the previous newline so we don't slice mid-line.
        int snap = s.lastIndexOf('\n', cut - 1);
        if (snap > 0) cut = snap + 1;

        int droppedBytes = s.length() - cut;
        int droppedLines = totalLines - countLines(s.substring(0, cut));
        return s.substring(0, cut)
                + "\n... [truncated: " + droppedBytes + " bytes / "
                + droppedLines + " lines dropped]\n";
    }

    /** Truncate keeping the tail; default 50KB / 2000 lines. */
    public static String truncateHead(String s) {
        return truncateHead(s, DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES);
    }

    /**
     * Truncate keeping the tail. Useful for log output where the final
     * lines (errors, summaries) carry the signal.
     */
    public static String truncateHead(String s, int maxBytes, int maxLines) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;

        int byteCap = budget(maxBytes, DEFAULT_MAX_BYTES);
        int lineCap = budget(maxLines, DEFAULT_MAX_LINES);

        int totalLines = countLines(s);
        boolean overBytes = s.length() > byteCap;
        boolean overLines = totalLines > lineCap;
        if (!overBytes && !overLines) return s;

        int byteCut = overBytes ? s.length() - byteCap : 0;
        int lineCut = overLines ? indexOfNthLineStartFromEnd(s, lineCap) : 0;
        int cut = Math.max(byteCut, lineCut);
        int snap = s.indexOf('\n', cut);
        if (snap >= 0 && snap < s.length() - 1) cut = snap + 1;

        int droppedBytes = cut;
        int droppedLines = countLines(s.substring(0, cut));
        return "[truncated: " + droppedBytes + " bytes / " + droppedLines
                + " lines dropped from start]\n..." + s.substring(cut);
    }

    private static int budget(int requested, int dflt) {
        return requested <= 0 ? dflt : requested;
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        // Don't count trailing newline as an extra empty line.
        return s.charAt(s.length() - 1) == '\n' ? n - 1 : n;
    }

    /** Byte offset of the start of line number {@code n} (1-indexed). */
    private static int indexOfNthLineStart(String s, int n) {
        if (n <= 1) return 0;
        int seen = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                seen++;
                if (seen == n - 1) return i + 1;
            }
        }
        return s.length();
    }

    /** Byte offset where the LAST {@code n} lines begin. */
    private static int indexOfNthLineStartFromEnd(String s, int n) {
        if (n <= 0) return s.length();
        int seen = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            if (s.charAt(i) == '\n') {
                seen++;
                if (seen == n) return i + 1;
            }
        }
        return 0;
    }
}
