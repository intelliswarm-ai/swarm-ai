package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the TaskItem-typed entries on a channel as a Markdown checkbox list,
 * suitable for streaming to a UI or splicing into an agent prompt as the
 * agent's "current todo list" view.
 *
 * <p>Output uses standard Markdown task-list syntax with status icons:
 * <ul>
 *   <li>{@code - [ ] description} — pending</li>
 *   <li>{@code - [~] active form} — in-progress (uses the TaskItem's activeForm)</li>
 *   <li>{@code - [x] description} — done</li>
 *   <li>{@code - [-] description} — cancelled</li>
 * </ul>
 *
 * <p>Sub-tasks (those with {@link TaskItem#parentTaskId} set) are indented
 * two spaces under their parent. Superseded tasks are hidden by default; flip
 * {@link #TaskListRenderer(String, boolean)} to include them.
 *
 * <p>Mixed-payload channels (those with both TaskItem and MutationPlan entries)
 * are filtered automatically — only TaskItem entries appear in the output.
 */
public final class TaskListRenderer {

    public static final String DEFAULT_HEADER = "## Tasks";

    private final String header;
    private final boolean includeSuperseded;

    public TaskListRenderer() {
        this(DEFAULT_HEADER, /*includeSuperseded*/ false);
    }

    public TaskListRenderer(String header, boolean includeSuperseded) {
        this.header = Objects.requireNonNull(header, "header");
        this.includeSuperseded = includeSuperseded;
    }

    /**
     * Render the channel's TaskItem entries. Returns an empty string when
     * there are no TaskItems at all (so the caller can append unconditionally
     * without a leaked header).
     */
    public String render(EvolvingPlan plan) {
        Objects.requireNonNull(plan, "plan");

        List<PlanEntry> tasks = TaskList.filter(plan, /*status*/ null);
        if (!includeSuperseded) {
            tasks = tasks.stream()
                    .filter(e -> e.status() != PlanEntry.Status.SUPERSEDED)
                    .toList();
        }
        if (tasks.isEmpty()) {
            return "";
        }

        // Build parent → children map preserving append order, plus a list of top-level entries.
        Map<String, List<PlanEntry>> children = new LinkedHashMap<>();
        List<PlanEntry> roots = new ArrayList<>();
        for (PlanEntry e : tasks) {
            TaskItem t = (TaskItem) e.payload();
            if (t.hasParent()) {
                children.computeIfAbsent(t.parentTaskId(), k -> new ArrayList<>()).add(e);
            } else {
                roots.add(e);
            }
        }

        // Also surface orphan sub-tasks (parent missing or filtered out) as roots —
        // otherwise they'd vanish from the render entirely.
        for (Map.Entry<String, List<PlanEntry>> mapEntry : children.entrySet()) {
            String parentId = mapEntry.getKey();
            if (tasks.stream().noneMatch(e -> e.id().equals(parentId))) {
                roots.addAll(mapEntry.getValue());
            }
        }

        StringBuilder out = new StringBuilder();
        out.append(header).append("\n\n");
        for (PlanEntry root : roots) {
            // Skip sub-tasks added as roots-due-to-orphan handling — they'd otherwise double-print.
            // (Sub-tasks added via the orphan path are still root-level here, and we render them once.)
            renderEntry(out, root, /*depth*/ 0);
            renderChildren(out, root.id(), children, /*depth*/ 1);
        }
        return out.toString().stripTrailing() + "\n";
    }

    private void renderChildren(StringBuilder out, String parentId,
                                 Map<String, List<PlanEntry>> children, int depth) {
        List<PlanEntry> kids = children.get(parentId);
        if (kids == null) return;
        for (PlanEntry kid : kids) {
            renderEntry(out, kid, depth);
            renderChildren(out, kid.id(), children, depth + 1);
        }
    }

    private void renderEntry(StringBuilder out, PlanEntry entry, int depth) {
        TaskItem item = (TaskItem) entry.payload();
        String icon = statusIcon(entry.status());
        String label = entry.status() == PlanEntry.Status.APPROVED
                ? item.effectiveActiveForm()
                : item.description();

        for (int i = 0; i < depth; i++) out.append("  ");
        out.append("- [").append(icon).append("] ").append(label);

        if (entry.status() == PlanEntry.Status.REJECTED
                && entry.reason() != null && !entry.reason().isBlank()) {
            out.append(" *(cancelled: ").append(entry.reason()).append(")*");
        } else if (entry.status() == PlanEntry.Status.EXECUTED
                && entry.reason() != null && !entry.reason().isBlank()
                && !"completed".equals(entry.reason())) {
            // Show the result note when it's not the default "completed".
            out.append(" — ").append(entry.reason());
        } else if (entry.status() == PlanEntry.Status.SUPERSEDED) {
            out.append(" *(superseded)*");
        }
        out.append('\n');
    }

    private static String statusIcon(PlanEntry.Status status) {
        return switch (status) {
            case PROPOSED -> " ";
            case APPROVED -> "~";
            case EXECUTED -> "x";
            case REJECTED -> "-";
            case SUPERSEDED -> "s";
        };
    }
}
