package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-function formatter that turns an {@link EvolvingPlan} into a Markdown
 * snippet suitable for injection into an agent's system or user prompt.
 * Lets the agent see its own plan without coupling it to the channel API —
 * the renderer's output is just a string.
 *
 * <p>The default rendering groups entries by status, ordered:
 * {@code APPROVED}, {@code PROPOSED}, {@code EXECUTED}, {@code REJECTED},
 * {@code SUPERSEDED}. {@code SUPERSEDED} is included only when the configured
 * {@code includeSuperseded} flag is set, since it's mostly noise during
 * normal operation.
 *
 * <p>Per entry, the line shows: a bullet, the {@code id}, the {@code risk}
 * tag, the {@code proposedBy} attribution, and the {@code summary} from
 * metadata (if present). On non-PROPOSED entries the line also shows
 * {@code decidedBy} and the most recent {@code reason}.
 *
 * <p>Output is stable: the same plan always renders identically. No timestamps,
 * no random ids — agents that read this can build deterministic mental models.
 */
public final class PlanContextRenderer {

    /** Default header used when the rendered output is non-empty. */
    public static final String DEFAULT_HEADER = "## Current plan";

    private static final List<PlanEntry.Status> STATUS_ORDER = List.of(
            PlanEntry.Status.APPROVED,
            PlanEntry.Status.PROPOSED,
            PlanEntry.Status.EXECUTED,
            PlanEntry.Status.REJECTED,
            PlanEntry.Status.SUPERSEDED);

    private static final Map<PlanEntry.Status, String> STATUS_LABEL = new EnumMap<>(Map.of(
            PlanEntry.Status.APPROVED, "approved",
            PlanEntry.Status.PROPOSED, "proposed",
            PlanEntry.Status.EXECUTED, "executed",
            PlanEntry.Status.REJECTED, "rejected",
            PlanEntry.Status.SUPERSEDED, "superseded"));

    private final String header;
    private final boolean includeSuperseded;
    private final int maxEntriesPerSection;

    public PlanContextRenderer() {
        this(DEFAULT_HEADER, /*includeSuperseded*/ false, /*maxEntriesPerSection*/ 20);
    }

    public PlanContextRenderer(String header,
                                boolean includeSuperseded,
                                int maxEntriesPerSection) {
        this.header = Objects.requireNonNull(header, "header");
        this.includeSuperseded = includeSuperseded;
        if (maxEntriesPerSection < 1) {
            throw new IllegalArgumentException("maxEntriesPerSection must be >= 1, was " + maxEntriesPerSection);
        }
        this.maxEntriesPerSection = maxEntriesPerSection;
    }

    /**
     * Render the plan as a Markdown snippet. Returns an empty string when the
     * plan is empty (so the caller can append unconditionally without leaking
     * an empty header).
     */
    public String render(EvolvingPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.isEmpty()) {
            return "";
        }

        Map<PlanEntry.Status, List<PlanEntry>> grouped = group(plan);
        StringBuilder out = new StringBuilder();
        out.append(header).append("\n\n");

        boolean wroteAny = false;
        for (PlanEntry.Status status : STATUS_ORDER) {
            if (status == PlanEntry.Status.SUPERSEDED && !includeSuperseded) continue;
            List<PlanEntry> entries = grouped.getOrDefault(status, List.of());
            if (entries.isEmpty()) continue;

            out.append("### ").append(STATUS_LABEL.get(status))
                    .append(" (").append(entries.size()).append(")\n");
            int rendered = 0;
            for (PlanEntry e : entries) {
                if (rendered >= maxEntriesPerSection) {
                    out.append("- … and ")
                            .append(entries.size() - rendered)
                            .append(" more\n");
                    break;
                }
                out.append(renderEntry(e, status));
                rendered++;
            }
            out.append('\n');
            wroteAny = true;
        }

        return wroteAny ? out.toString().stripTrailing() + "\n" : "";
    }

    private Map<PlanEntry.Status, List<PlanEntry>> group(EvolvingPlan plan) {
        Map<PlanEntry.Status, List<PlanEntry>> grouped = new EnumMap<>(PlanEntry.Status.class);
        for (PlanEntry e : plan.entries()) {
            grouped.computeIfAbsent(e.status(), k -> new ArrayList<>()).add(e);
        }
        return grouped;
    }

    private String renderEntry(PlanEntry e, PlanEntry.Status status) {
        StringBuilder line = new StringBuilder();
        line.append("- `").append(e.id()).append('`');
        if (e.risk() != Risk.UNKNOWN) {
            line.append(" [").append(e.risk()).append(']');
        }
        line.append(' ').append(e.proposedBy());

        Object summary = e.metadata().get("summary");
        if (summary != null) {
            line.append(" — ").append(summary);
        }
        if (status != PlanEntry.Status.PROPOSED) {
            if (e.decidedBy() != null) {
                line.append(" *(").append(STATUS_LABEL.get(status))
                        .append(" by ").append(e.decidedBy()).append(")*");
            }
            if (e.reason() != null && !e.reason().isBlank()) {
                line.append(" — ").append(e.reason());
            }
        }
        if (e.supersedes() != null) {
            line.append(" *(supersedes ").append(e.supersedes()).append(")*");
        }
        line.append('\n');
        return line.toString();
    }
}
