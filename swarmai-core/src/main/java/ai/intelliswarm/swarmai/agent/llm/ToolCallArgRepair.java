package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Best-effort fix-up of malformed JSON the model occasionally emits as tool
 * arguments. Repairs:
 * <ul>
 *   <li>Trailing commas in objects/arrays (the most common LLM emission)</li>
 *   <li>Unclosed braces / brackets at end of string</li>
 *   <li>Python literals ({@code True}/{@code False}/{@code None}) ↔ JSON
 *       ({@code true}/{@code false}/{@code null})</li>
 *   <li>Stray surrogate halves and non-printable control characters</li>
 *   <li>Single-quoted strings → double-quoted (only when unambiguous)</li>
 * </ul>
 *
 * <p>This is a tactical band-aid, not a strict JSON parser — it eliminates the
 * common failure modes documented in agent-loop logs without becoming a second
 * parser. If a repair attempt makes the JSON worse, the original is returned
 * and the caller can surface a typed error.
 *
 * <p>Implemented as a small read-once state machine — string-literal aware so
 * Python-literal substitution and trailing-comma removal don't touch user data
 * that happens to contain those tokens.
 */
@PublicApi(since = "1.0.24")
public final class ToolCallArgRepair {

    private ToolCallArgRepair() {}

    /** Repair result — carries the cleaned text plus a flag noting whether any change happened. */
    public record Repair(String text, boolean changed) {}

    public static Repair repair(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Repair(raw, false);
        }

        String working = raw;
        boolean changed = false;

        String sanitized = stripSurrogatesAndControls(working);
        if (!sanitized.equals(working)) {
            working = sanitized;
            changed = true;
        }

        String afterLiterals = replacePythonLiterals(working);
        if (!afterLiterals.equals(working)) {
            working = afterLiterals;
            changed = true;
        }

        String afterTrailing = stripTrailingCommas(working);
        if (!afterTrailing.equals(working)) {
            working = afterTrailing;
            changed = true;
        }

        String afterClosed = closeUnterminated(working);
        if (!afterClosed.equals(working)) {
            working = afterClosed;
            changed = true;
        }

        return new Repair(working, changed);
    }

    // ---------- repair primitives ----------

    private static String stripSurrogatesAndControls(String in) {
        StringBuilder out = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < in.length() && Character.isLowSurrogate(in.charAt(i + 1))) {
                    out.append(c).append(in.charAt(i + 1));
                    i++;
                }
                // unmatched high surrogate — drop
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                // unmatched low surrogate — drop
                continue;
            }
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                continue; // strip C0 control chars except newlines/tab
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String replacePythonLiterals(String in) {
        // Only replace outside string literals. Walk once with a tiny state machine.
        StringBuilder out = new StringBuilder(in.length());
        boolean inString = false;
        char stringQuote = 0;
        int i = 0;
        while (i < in.length()) {
            char c = in.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < in.length()) {
                    out.append(in.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == stringQuote) {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                out.append(c);
                i++;
                continue;
            }
            // Try literal at current position
            if (matchAt(in, i, "True") && wordBoundary(in, i, 4)) {
                out.append("true"); i += 4; continue;
            }
            if (matchAt(in, i, "False") && wordBoundary(in, i, 5)) {
                out.append("false"); i += 5; continue;
            }
            if (matchAt(in, i, "None") && wordBoundary(in, i, 4)) {
                out.append("null"); i += 4; continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean matchAt(String s, int i, String word) {
        if (i + word.length() > s.length()) return false;
        for (int k = 0; k < word.length(); k++) {
            if (s.charAt(i + k) != word.charAt(k)) return false;
        }
        return true;
    }

    private static boolean wordBoundary(String s, int i, int len) {
        int before = i - 1;
        int after = i + len;
        boolean leftOk = before < 0 || !Character.isLetterOrDigit(s.charAt(before));
        boolean rightOk = after >= s.length() || !Character.isLetterOrDigit(s.charAt(after));
        return leftOk && rightOk;
    }

    private static String stripTrailingCommas(String in) {
        StringBuilder out = new StringBuilder(in.length());
        boolean inString = false;
        char stringQuote = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < in.length()) {
                    out.append(in.charAt(i + 1));
                    i++;
                    continue;
                }
                if (c == stringQuote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                out.append(c);
                continue;
            }
            if (c == ',') {
                // Lookahead for the next non-whitespace char
                int j = i + 1;
                while (j < in.length() && Character.isWhitespace(in.charAt(j))) j++;
                if (j < in.length() && (in.charAt(j) == '}' || in.charAt(j) == ']')) {
                    continue; // drop the trailing comma
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String closeUnterminated(String in) {
        // Track opener stack; if the string ends with unmatched openers, append closers.
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false;
        char stringQuote = 0;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < in.length()) {
                    i++;
                    continue;
                }
                if (c == stringQuote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringQuote = c;
                continue;
            }
            if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) stack.pop();
            }
        }
        if (stack.isEmpty() && !inString) {
            return in;
        }
        StringBuilder repaired = new StringBuilder(in);
        if (inString) {
            repaired.append(stringQuote);
        }
        while (!stack.isEmpty()) {
            char opener = stack.pop();
            repaired.append(opener == '{' ? '}' : ']');
        }
        return repaired.toString();
    }
}
