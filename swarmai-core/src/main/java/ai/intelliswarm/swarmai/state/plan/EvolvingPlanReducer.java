package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.state.Reducer;

/**
 * Channel reducer for {@link EvolvingPlan}. Two states are merged by replaying
 * every entry in the new plan onto the old one:
 * <ul>
 *   <li>An entry whose id is unknown in the old plan is appended.</li>
 *   <li>An entry whose id matches an existing one is treated as an in-place
 *       status/reason/metadata update via {@link EvolvingPlan#with(PlanEntry)}.
 *       Conflicting bodies (different {@code proposedBy} or {@code payload})
 *       throw — the merge is meant for status transitions, not rewrites.</li>
 * </ul>
 *
 * <p>This means concurrent updates from different nodes compose: a tool node
 * appends a {@code PROPOSED} entry, a governance node later marks it
 * {@code APPROVED}, and an executor node later marks it {@code EXECUTED} —
 * each emits a partial plan and the channel merges them deterministically.
 */
public final class EvolvingPlanReducer implements Reducer<EvolvingPlan> {

    private static final EvolvingPlanReducer INSTANCE = new EvolvingPlanReducer();

    public static EvolvingPlanReducer instance() {
        return INSTANCE;
    }

    private EvolvingPlanReducer() { }

    @Override
    public EvolvingPlan apply(EvolvingPlan oldValue, EvolvingPlan newValue) {
        if (oldValue == null || oldValue.isEmpty()) {
            return newValue != null ? newValue : EvolvingPlan.empty();
        }
        if (newValue == null || newValue.isEmpty()) {
            return oldValue;
        }
        EvolvingPlan merged = oldValue;
        for (PlanEntry entry : newValue.entries()) {
            merged = merged.with(entry);
        }
        return merged;
    }
}
