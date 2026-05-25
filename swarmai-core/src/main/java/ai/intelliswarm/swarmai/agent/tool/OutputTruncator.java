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
 * <p>Line accounting: a line is content terminated by a {@code \n}; a final
 * trailing {@code \n} is treated as the end of the last line, not as its
 * own empty line. So {@code "a\nb\n"} is 2 lines, {@code "a\nb"} is also
 * 2 lines, {@code ""} is 0 lines.
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
     * satisfied. Otherwise truncates so that exactly {@code maxLines} lines
     * are kept (when line-cap binds) AND at most {@code maxBytes} bytes
     * remain (when byte-cap binds), appending a single-line marker.
     */
    public static String truncateTail(String s, int maxBytes, int maxLines) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;

        int byteCap = budget(maxBytes, DEFAULT_MAX_BYTES);
        int lineCap = budget(maxLines, DEFAULT_MAX_LINES);

        int totalLines = countLines(s);
        boolean overBytes = s.length() > byteCap;
        boolean overLines = totalLines > lineCap;
        if (!overBytes && !overLines) return s;

        // End-exclusive cut position. The contract is "keep at most lineCap
        // lines" so the cut goes to the START of line `lineCap + 1` — that
        // preserves all of lines 1..lineCap including their trailing \n.
        int byteCut = overBytes ? byteCap : s.length();
        int lineCut = overLines ? startOfLine(s, lineCap + 1) : s.length();
        int cut = Math.min(byteCut, lineCut);

        // If the binding cut came from the byte cap it may slice mid-line —
        // snap BACK to the previous newline so we never emit a partial line.
        // When the cut came from lineCut it's already aligned (right after
        // a newline) so the snap is a no-op.
        if (cut > 0 && cut < s.length() && s.charAt(cut - 1) != '\n') {
            int prevNl = s.lastIndexOf('\n', cut - 1);
            cut = prevNl >= 0 ? prevNl + 1 : 0;
        }

        if (cut <= 0) {
            // Pathological: byte cap is below the first line. Drop everything.
            return "[truncated: " + s.length() + " bytes / "
                    + totalLines + " lines dropped]\n";
        }

        int droppedBytes = s.length() - cut;
        int droppedLines = totalLines - countLines(s.substring(0, cut));
        String head = s.substring(0, cut);
        // head ends with \n (cut sits right after a newline by construction),
        // so the marker starts on its own line.
        return head + "... [truncated: " + droppedBytes + " bytes / "
                + droppedLines + " lines dropped]\n";
    }

    /** Truncate keeping the tail; default 50KB / 2000 lines. */
    public static String truncateHead(String s) {
        return truncateHead(s, DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES);
    }

    /**
     * Truncate keeping the tail. Useful for log output where the final
     * lines (errors, summaries) carry the signal. Keeps exactly the last
     * {@code maxLines} lines when the line cap binds.
     */
    public static String truncateHead(String s, int maxBytes, int maxLines) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;

        int byteCap = budget(maxBytes, DEFAULT_MAX_BYTES);
        int lineCap = budget(maxLines, DEFAULT_MAX_LINES);

        int totalLines = countLines(s);
        boolean overBytes = s.length() > byteCap;
        boolean overLines = totalLines > lineCap;
        if (!overBytes && !overLines) return s;

        // Start-inclusive position where the kept (last) region begins.
        int byteCut = overBytes ? s.length() - byteCap : 0;
        int lineCut = overLines ? startOfLastLines(s, lineCap, totalLines) : 0;
        int cut = Math.max(byteCut, lineCut);

        // If byteCut won and put us mid-line, snap FORWARD to the next \n
        // so we don't emit a partial leading line. When lineCut won it's
        // already aligned so this is a no-op.
        if (cut > 0 && cut < s.length() && s.charAt(cut - 1) != '\n') {
            int nextNl = s.indexOf('\n', cut);
            if (nextNl >= 0 && nextNl < s.length() - 1) cut = nextNl + 1;
        }

        int droppedBytes = cut;
        int droppedLines = countLines(s.substring(0, cut));
        // Marker on its own line, then ellipsis on its own line, then the
        // kept content — keeps each piece line-addressable for the consumer.
        return "[truncated: " + droppedBytes + " bytes / " + droppedLines
                + " lines dropped from start]\n...\n" + s.substring(cut);
    }

    private static int budget(int requested, int dflt) {
        return requested <= 0 ? dflt : requested;
    }

    /**
     * Number of lines in {@code s}. A trailing {@code \n} is treated as the
     * end of the last line, not as its own empty line. Empty string → 0.
     */
    static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return s.charAt(s.length() - 1) == '\n' ? n - 1 : n;
    }

    /**
     * Byte offset where line {@code n} (1-indexed) begins. {@code n == 1}
     * returns 0; {@code n == 2} returns the position right after the first
     * {@code \n}; and so on. If {@code n} exceeds the line count, returns
     * {@code s.length()}.
     */
    static int startOfLine(String s, int n) {
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

    /**
     * Byte offset where the last {@code n} lines begin. If {@code n} is at
     * least the total line count, returns 0 (keep everything). If
     * {@code n <= 0}, returns {@code s.length()} (keep nothing).
     */
    static int startOfLastLines(String s, int n, int totalLines) {
        if (n <= 0) return s.length();
        if (n >= totalLines) return 0;
        return startOfLine(s, totalLines - n + 1);
    }
}
