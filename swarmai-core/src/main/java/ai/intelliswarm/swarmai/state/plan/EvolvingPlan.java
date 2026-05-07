package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable container of {@link PlanEntry} values forming a swarm's evolving
 * plan. Designed to live as a single value inside an {@link
 * ai.intelliswarm.swarmai.state.AgentState} channel, merged across nodes via
 * {@link EvolvingPlanReducer}.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Entries are stored in append-order — never reordered or removed.</li>
 *   <li>Mutating helpers ({@link #with(PlanEntry)}, {@link #markStatus},
 *       {@link #supersede}) return new instances; the original is unchanged.</li>
 *   <li>{@link #with(PlanEntry)} is idempotent on entry id: re-adding an
 *       existing id replaces that entry's status/reason/metadata in place.</li>
 *   <li>Entry ids are unique within a plan — collisions on id while bodies
 *       differ (proposedBy, payload) throw {@link IllegalArgumentException}.</li>
 * </ul>
 */
public final class EvolvingPlan {

    private static final EvolvingPlan EMPTY =
            new EvolvingPlan(Collections.emptyList(), Collections.emptyMap());

    private final List<PlanEntry> entries;
    private final Map<String, Integer> indexById;

    private EvolvingPlan(List<PlanEntry> entries, Map<String, Integer> indexById) {
        this.entries = entries;
        this.indexById = indexById;
    }

    public static EvolvingPlan empty() {
        return EMPTY;
    }

    public static EvolvingPlan of(PlanEntry... entries) {
        EvolvingPlan plan = empty();
        for (PlanEntry e : entries) {
            plan = plan.with(e);
        }
        return plan;
    }

    /** Read-only view of every entry in append order, including terminal ones. */
    public List<PlanEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Optional<PlanEntry> byId(String id) {
        Integer i = indexById.get(id);
        return i == null ? Optional.empty() : Optional.of(entries.get(i));
    }

    /** Entries in {@link PlanEntry.Status#PROPOSED} or {@code APPROVED}. */
    public List<PlanEntry> active() {
        List<PlanEntry> out = new ArrayList<>();
        for (PlanEntry e : entries) {
            if (e.isActive()) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    public List<PlanEntry> withStatus(PlanEntry.Status status) {
        Objects.requireNonNull(status, "status");
        List<PlanEntry> out = new ArrayList<>();
        for (PlanEntry e : entries) {
            if (e.status() == status) out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Append (or replace, if id already present) an entry. If the id is new,
     * the entry is appended; if the id exists and bodies match, the existing
     * entry is replaced (status/reason/metadata can change). If bodies diverge
     * on a duplicate id, throws — that's a wiring bug, not a normal flow.
     */
    public EvolvingPlan with(PlanEntry entry) {
        Objects.requireNonNull(entry, "entry");
        Integer existing = indexById.get(entry.id());
        if (existing != null) {
            PlanEntry current = entries.get(existing);
            if (!Objects.equals(current.proposedBy(), entry.proposedBy())
                    || !Objects.equals(current.payload(), entry.payload())) {
                throw new IllegalArgumentException(
                        "PlanEntry id " + entry.id() +
                        " already present with a different body — use markStatus or supersede instead");
            }
            List<PlanEntry> next = new ArrayList<>(entries);
            next.set(existing, entry);
            return new EvolvingPlan(Collections.unmodifiableList(next), indexById);
        }
        List<PlanEntry> next = new ArrayList<>(entries);
        next.add(entry);
        Map<String, Integer> nextIdx = new LinkedHashMap<>(indexById);
        nextIdx.put(entry.id(), next.size() - 1);
        return new EvolvingPlan(
                Collections.unmodifiableList(next),
                Collections.unmodifiableMap(nextIdx));
    }

    /** Mark an existing entry's status, preserving its prior decidedBy. Throws if the id is unknown. */
    public EvolvingPlan markStatus(String id, PlanEntry.Status newStatus, String reason) {
        return markStatus(id, newStatus, reason, null, /*preserveDecidedBy*/ true);
    }

    /**
     * Mark an existing entry's status and attribute the transition to
     * {@code decidedBy}. Throws if the id is unknown.
     *
     * @param decidedBy actor whose action is causing the transition
     *                  (e.g. {@code "PLAN_POLICY"}, {@code "alice"}, {@code "replanner"});
     *                  may be null to clear attribution
     */
    public EvolvingPlan markStatus(String id, PlanEntry.Status newStatus, String reason, String decidedBy) {
        return markStatus(id, newStatus, reason, decidedBy, /*preserveDecidedBy*/ false);
    }

    private EvolvingPlan markStatus(String id, PlanEntry.Status newStatus, String reason,
                                     String decidedBy, boolean preserveDecidedBy) {
        Objects.requireNonNull(id, "id");
        Integer i = indexById.get(id);
        if (i == null) {
            throw new IllegalArgumentException("unknown PlanEntry id: " + id);
        }
        PlanEntry current = entries.get(i);
        PlanEntry updated = preserveDecidedBy
                ? current.withStatus(newStatus, reason)
                : current.withStatus(newStatus, reason, decidedBy);
        List<PlanEntry> next = new ArrayList<>(entries);
        next.set(i, updated);
        return new EvolvingPlan(Collections.unmodifiableList(next), indexById);
    }

    /**
     * Replace {@code oldId} with {@code replacement}: the old entry is marked
     * {@link PlanEntry.Status#SUPERSEDED}, the replacement is appended (and
     * its {@link PlanEntry#supersedes()} should already point at {@code oldId}).
     */
    public EvolvingPlan supersede(String oldId, PlanEntry replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return markStatus(oldId, PlanEntry.Status.SUPERSEDED,
                "superseded by " + replacement.id())
                .with(replacement);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EvolvingPlan that)) return false;
        return Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(entries);
    }

    @Override
    public String toString() {
        return "EvolvingPlan{size=" + entries.size() +
                ", active=" + active().size() + "}";
    }
}
