package ai.intelliswarm.swarmai.state.plan;

import java.util.function.UnaryOperator;

/**
 * Read/write contract for the {@link EvolvingPlan} channel of a swarm session.
 * Decouples the data model in {@code state.plan} from the runtime that hosts
 * the plan — typically the {@link ai.intelliswarm.swarmai.state.AgentState}
 * channel value, but for tests and simple programs an in-process holder
 * (see {@link InMemoryPlanChannelAccessor}) is enough.
 *
 * <p>The accessor is the seam through which the safety chain (mutation guards,
 * lifecycle hooks) talks to the plan without importing {@code AgentState} or
 * dragging session-management concerns into pure tool code.
 *
 * <p>Implementations must be safe for concurrent reads and writes. The simplest
 * way to satisfy that is to make {@link #update} apply the function under a
 * single lock — {@link InMemoryPlanChannelAccessor} does exactly this.
 */
public interface PlanChannelAccessor {

    /** Snapshot of the current plan; never null. */
    EvolvingPlan get();

    /**
     * Apply {@code mutator} to the current plan and atomically install the
     * result. Listeners (if any) are notified after the swap completes.
     *
     * @return the post-mutation plan
     */
    EvolvingPlan update(UnaryOperator<EvolvingPlan> mutator);

    /**
     * Convenience: append a single entry. Equivalent to
     * {@code update(p -> p.with(entry))}.
     */
    default EvolvingPlan append(PlanEntry entry) {
        return update(p -> p.with(entry));
    }

    /**
     * Convenience: transition an entry's status, preserving prior {@code decidedBy}.
     * Equivalent to {@code update(p -> p.markStatus(id, status, reason))}.
     */
    default EvolvingPlan markStatus(String id, PlanEntry.Status status, String reason) {
        return update(p -> p.markStatus(id, status, reason));
    }

    /**
     * Convenience: transition an entry's status and attribute it to
     * {@code decidedBy}. Equivalent to
     * {@code update(p -> p.markStatus(id, status, reason, decidedBy))}.
     */
    default EvolvingPlan markStatus(String id, PlanEntry.Status status, String reason, String decidedBy) {
        return update(p -> p.markStatus(id, status, reason, decidedBy));
    }

    /**
     * Subscribe to status transitions. Listeners receive {@code (oldEntry, newEntry)}
     * pairs every time {@link #update} produces a plan whose existing entries
     * have changed status. Newly-appended entries are reported as
     * {@code (null, newEntry)}. Optional — implementations may no-op.
     */
    default void addListener(PlanListener listener) {
        // no-op by default; in-memory impl supports it
    }

    /**
     * Listener for status transitions on the channel.
     */
    @FunctionalInterface
    interface PlanListener {
        /**
         * @param previous the entry before this update, or null if it was just appended
         * @param current  the entry as it now stands
         */
        void onTransition(PlanEntry previous, PlanEntry current);
    }
}
