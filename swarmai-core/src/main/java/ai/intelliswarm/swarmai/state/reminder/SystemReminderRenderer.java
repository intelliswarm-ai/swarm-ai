package ai.intelliswarm.swarmai.state.reminder;

import java.util.List;
import java.util.Objects;

/**
 * Renders a list of {@link SystemReminder}s as a single
 * {@code <system-reminder>...</system-reminder>} block suitable for splicing
 * into the agent's input stream. The XML-style tag is what makes the agent
 * recognise the content as harness-injected context (vs. tool output or user
 * input), regardless of which message-role the splicer uses.
 *
 * <h2>Default format</h2>
 * <pre>
 * &lt;system-reminder&gt;
 * [task-list] You have 2 in-progress tasks: "Research X", "Plan Y".
 * [drift-budget WARN] 4/5 amendments used. Next will require human approval.
 * &lt;/system-reminder&gt;
 * </pre>
 *
 * <p>Sources are prefixed in brackets; {@link SystemReminder.Severity#WARN}
 * reminders carry an explicit "WARN" tag, INFO is unmarked (it's the common case).
 *
 * <h2>Per-reminder block mode</h2>
 * Some downstream consumers prefer one tag per reminder (e.g. when reminders
 * carry distinct semantic weight or want their own provenance). Use
 * {@link #renderEach} for that layout — each reminder gets its own
 * {@code <system-reminder>} tag, and they're concatenated with blank lines.
 */
public final class SystemReminderRenderer {

    /** Render a list of reminders as a single combined block. Empty list returns empty string. */
    public String render(List<SystemReminder> reminders) {
        Objects.requireNonNull(reminders, "reminders");
        if (reminders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        for (SystemReminder r : reminders) {
            appendLine(sb, r);
        }
        sb.append("</system-reminder>");
        return sb.toString();
    }

    /** Render each reminder as its own {@code <system-reminder>} block, joined by blank lines. */
    public String renderEach(List<SystemReminder> reminders) {
        Objects.requireNonNull(reminders, "reminders");
        if (reminders.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (SystemReminder r : reminders) {
            if (!first) sb.append("\n\n");
            sb.append("<system-reminder>\n");
            appendLine(sb, r);
            sb.append("</system-reminder>");
            first = false;
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, SystemReminder r) {
        sb.append('[').append(r.source());
        if (r.severity() == SystemReminder.Severity.WARN) {
            sb.append(" WARN");
        }
        sb.append("] ").append(r.message()).append('\n');
    }
}
