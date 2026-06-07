package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Operator-style helper for managing a {@link TaskItem}-typed view of a
 * {@link PlanChannelAccessor}. Lets agents (and the harness) self-organise
 * by creating, starting, completing, cancelling, and revising tasks, without
 * touching the channel's bare API.
 *
 * <p>Tasks live on the same channel as mutation entries — they're identified
 * by their {@link PlanEntry#payload} being a {@link TaskItem}. Renderers
 * (and the harness) filter on payload type to display tasks separately from
 * mutation history.
 *
 * <p>Default {@code proposedBy}: {@code "agent"}. Override via the
 * {@link #TaskList(PlanChannelAccessor, String)} constructor when multiple
 * actors create tasks (e.g. an operator-driven CLI and an LLM-driven agent).
 *
 * <h2>Status mapping</h2>
 * <ul>
 *   <li>{@link #createTask} — adds a {@link PlanEntry.Status#PROPOSED} entry (pending)</li>
 *   <li>{@link #startTask}  — PROPOSED → APPROVED (in-progress)</li>
 *   <li>{@link #completeTask} — APPROVED → EXECUTED (done)</li>
 *   <li>{@link #cancelTask} — any active status → REJECTED (cancelled)</li>
 *   <li>{@link #supersedeTask} — replace with a revised task; old → SUPERSEDED</li>
 * </ul>
 */
public final class TaskList {

    /** Default proposedBy attribution when the caller doesn't specify. */
    public static final String DEFAULT_PROPOSED_BY = "agent";

    private final PlanChannelAccessor channel;
    private final String proposedBy;

    public TaskList(PlanChannelAccessor channel) {
        this(channel, DEFAULT_PROPOSED_BY);
    }

    public TaskList(PlanChannelAccessor channel, String proposedBy) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.proposedBy = Objects.requireNonNull(proposedBy, "proposedBy");
    }

    // ============== Mutators ==============

    /** Create a pending top-level task; returns the entry id. */
    public String createTask(String description) {
        return createTask(TaskItem.of(description), null);
    }

    /** Create a pending task with explicit active-form label; returns the entry id. */
    public String createTask(String description, String activeForm) {
        return createTask(TaskItem.of(description, activeForm), null);
    }

    /** Create a sub-task under {@code parentTaskId}; returns the entry id. */
    public String createSubTask(String parentTaskId, String description, String activeForm) {
        Objects.requireNonNull(parentTaskId, "parentTaskId");
        TaskItem item = new TaskItem(description, activeForm, parentTaskId, java.util.Map.of());
        return createTask(item, null);
    }

    /** Lower-level: create with a fully-formed {@link TaskItem} and an optional explicit id. */
    public String createTask(TaskItem item, String explicitId) {
        Objects.requireNonNull(item, "item");
        String id = (explicitId == null || explicitId.isBlank())
                ? UUID.randomUUID().toString()
                : explicitId;
        PlanEntry entry = PlanEntry.builder()
                .id(id)
                .proposedBy(proposedBy)
                .payload(item)
                .payloadType(TaskItem.class.getName())
                .risk(Risk.LOW)
                .build();
        channel.append(entry);
        return id;
    }

    /** Mark a pending task as in-progress (PROPOSED → APPROVED). */
    public void startTask(String taskId) {
        transition(taskId, PlanEntry.Status.APPROVED, "started", proposedBy);
    }

    /** Mark an in-progress task as completed (APPROVED → EXECUTED). */
    public void completeTask(String taskId) {
        transition(taskId, PlanEntry.Status.EXECUTED, "completed", proposedBy);
    }

    /** Mark an in-progress task as completed with a result note. */
    public void completeTask(String taskId, String resultNote) {
        transition(taskId, PlanEntry.Status.EXECUTED, resultNote, proposedBy);
    }

    /** Cancel a task (any active status → REJECTED). */
    public void cancelTask(String taskId, String reason) {
        transition(taskId, PlanEntry.Status.REJECTED, reason, proposedBy);
    }

    /**
     * Replace {@code oldTaskId} with a revised TaskItem. Old entry transitions
     * to SUPERSEDED; new entry is created as PROPOSED (pending) with the same
     * proposedBy. Returns the new entry's id.
     */
    public String supersedeTask(String oldTaskId, TaskItem revised) {
        Objects.requireNonNull(oldTaskId, "oldTaskId");
        Objects.requireNonNull(revised, "revised");
        String newId = UUID.randomUUID().toString();
        PlanEntry replacement = PlanEntry.builder()
                .id(newId)
                .proposedBy(proposedBy)
                .payload(revised)
                .payloadType(TaskItem.class.getName())
                .risk(Risk.LOW)
                .supersedes(oldTaskId)
                .build();
        channel.update(p -> p.markStatus(oldTaskId,
                        PlanEntry.Status.SUPERSEDED,
                        "superseded by " + newId,
                        proposedBy)
                .with(replacement));
        return newId;
    }

    // ============== Queries ==============

    /** All TaskItem-typed entries on the channel, in append order. */
    public List<PlanEntry> allTasks() {
        return filter(channel.get(), null);
    }

    /** Currently pending tasks (PROPOSED). */
    public List<PlanEntry> pendingTasks() {
        return filter(channel.get(), PlanEntry.Status.PROPOSED);
    }

    /** Currently in-progress tasks (APPROVED). */
    public List<PlanEntry> inProgressTasks() {
        return filter(channel.get(), PlanEntry.Status.APPROVED);
    }

    /** Tasks that have completed (EXECUTED). */
    public List<PlanEntry> completedTasks() {
        return filter(channel.get(), PlanEntry.Status.EXECUTED);
    }

    /** Tasks that have been cancelled (REJECTED). */
    public List<PlanEntry> cancelledTasks() {
        return filter(channel.get(), PlanEntry.Status.REJECTED);
    }

    // ============== Internals ==============

    private void transition(String taskId, PlanEntry.Status target,
                             String reason, String decidedBy) {
        Objects.requireNonNull(taskId, "taskId");
        channel.update(p -> {
            PlanEntry current = p.byId(taskId).orElseThrow(
                    () -> new IllegalArgumentException("unknown task id: " + taskId));
            requireTaskItem(current);
            if (current.isTerminal() && current.status() != target) {
                throw new IllegalStateException(
                        "task " + taskId + " is already terminal in status " + current.status());
            }
            return p.markStatus(taskId, target, reason, decidedBy);
        });
    }

    private static void requireTaskItem(PlanEntry entry) {
        if (!(entry.payload() instanceof TaskItem)) {
            throw new IllegalArgumentException(
                    "PlanEntry " + entry.id() + " is not a TaskItem (payload is " +
                            (entry.payload() == null ? "null" : entry.payload().getClass().getSimpleName()) + ")");
        }
    }

    /**
     * Filter the plan to TaskItem entries optionally matching {@code status}.
     * {@code null} status returns all TaskItem entries.
     */
    static List<PlanEntry> filter(EvolvingPlan plan, PlanEntry.Status status) {
        List<PlanEntry> out = new ArrayList<>();
        for (PlanEntry e : plan.entries()) {
            if (!(e.payload() instanceof TaskItem)) continue;
            if (status != null && e.status() != status) continue;
            out.add(e);
        }
        return List.copyOf(out);
    }
}
