package ai.intelliswarm.swarmai.state.question;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Default {@link UserQuestionResolver} that reads from stdin and writes to
 * stdout. Suitable for CLI runs and interactive examples; not appropriate
 * for automated regression suites (where input piping is fragile) — use
 * {@link ScriptedUserQuestionResolver} there.
 *
 * <h2>Display</h2>
 * <pre>
 *   ? Which environment to deploy to?
 *     1) staging
 *     2) production
 *     3) (custom — type your own)
 *   &gt;
 * </pre>
 *
 * <p>The user enters either a 1-based option number or, if {@code allowFreeform}
 * is true, presses {@code C} (or any non-numeric input) to switch to
 * freeform mode. A blank line is rejected and re-prompted.
 */
public final class ConsoleUserQuestionResolver implements UserQuestionResolver {

    private final BufferedReader reader;
    private final PrintStream out;

    public ConsoleUserQuestionResolver() {
        this(System.in, System.out);
    }

    public ConsoleUserQuestionResolver(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(in, "in"), StandardCharsets.UTF_8));
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public UserAnswer ask(UserQuestion question) {
        Objects.requireNonNull(question, "question");
        out.println();
        out.println("? " + question.question());
        List<String> options = question.options();
        if (!options.isEmpty()) {
            for (int i = 0; i < options.size(); i++) {
                out.println("  " + (i + 1) + ") " + options.get(i));
            }
            if (question.allowFreeform()) {
                out.println("  C) (custom — type 'C' then your answer)");
            }
        }
        if (options.isEmpty() || question.allowFreeform()) {
            out.println("  (or type your answer directly)");
        }

        while (true) {
            out.print("> ");
            out.flush();
            String line;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                throw new UserQuestionException("failed reading from stdin", e);
            }
            if (line == null) {
                throw new UserQuestionException("stdin closed without an answer");
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.println("  (please enter an answer)");
                continue;
            }

            // Numeric → option index
            if (!options.isEmpty() && isAllDigits(trimmed)) {
                int idx;
                try {
                    idx = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    out.println("  (not a valid option number — try again)");
                    continue;
                }
                if (idx >= 1 && idx <= options.size()) {
                    return UserAnswer.ofOption(question.id(), options.get(idx - 1));
                }
                out.println("  (option " + idx + " is out of range — try again)");
                continue;
            }

            // 'C' or 'c' → switch to freeform mode (only when allowed)
            if (!options.isEmpty() && trimmed.equalsIgnoreCase("C")) {
                if (!question.allowFreeform()) {
                    out.println("  (this question requires picking one of the listed options)");
                    continue;
                }
                out.print("  freeform> ");
                out.flush();
                String freeform;
                try {
                    freeform = reader.readLine();
                } catch (IOException e) {
                    throw new UserQuestionException("failed reading freeform from stdin", e);
                }
                if (freeform == null || freeform.trim().isEmpty()) {
                    out.println("  (empty answer — try again)");
                    continue;
                }
                return UserAnswer.ofFreeform(question.id(), freeform.trim());
            }

            // Otherwise: freeform answer (if allowed)
            if (options.isEmpty() || question.allowFreeform()) {
                return UserAnswer.ofFreeform(question.id(), trimmed);
            }

            // Pure-options mode and the input is neither a valid number nor 'C'
            out.println("  (please enter an option number 1-" + options.size() + ")");
        }
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Unused — kept for forward compatibility if locale-aware option matching is ever added. */
    @SuppressWarnings("unused")
    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).trim();
    }
}
