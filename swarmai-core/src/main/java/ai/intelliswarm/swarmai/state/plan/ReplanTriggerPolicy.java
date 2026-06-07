package ai.intelliswarm.swarmai.state.plan;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides when a {@link Replanner} should run. Consulted by
 * {@link ReplannerLoop} on every channel transition; the loop fires the
 * replanner if {@link #shouldReplan} returns true, otherwise lets the
 * transition pass.
 *
 * <p>Stateless on the plan side — the trigger inspects the {@code event}
 * and (for some impls) keeps small internal state like a step counter.
 * Implementations need to be thread-safe if used from a multi-threaded
 * channel.
 */
@FunctionalInterface
public interface ReplanTriggerPolicy {

    /**
     * @param plan    the plan as it stands at the moment of the event
     * @param event   the transition that prompted the check
     * @return        {@code true} if the replanner should be invoked
     */
    boolean shouldReplan(EvolvingPlan plan, ReplanTriggerEvent event);

    /** Information about a single channel transition. */
    record ReplanTriggerEvent(PlanEntry previous, PlanEntry current) {

        /** True when this event represents a new entry being appended. */
        public boolean isAppend() {
            return previous == null;
        }

        /** True when {@code current.status()} is one of EXECUTED / REJECTED / SUPERSEDED. */
        public boolean isTerminal() {
            return current != null && current.isTerminal();
        }
    }

    // ========================================
    // Built-in trigger policies
    // ========================================

    /** Never trigger. Useful for tests and as a "pause replanning" toggle. */
    static ReplanTriggerPolicy never() {
        return (plan, event) -> false;
    }

    /** Trigger on every transition. Useful for tests; rarely production-appropriate. */
    static ReplanTriggerPolicy always() {
        return (plan, event) -> true;
    }

    /**
     * Trigger every {@code n} transitions. Counts only non-append events
     * (status changes), so the replanner doesn't fire on every fresh
     * proposal — only after meaningful progress.
     *
     * @param n number of transitions between fires; must be {@code >= 1}
     */
    static ReplanTriggerPolicy everyNSteps(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1, was " + n);
        }
        AtomicInteger counter = new AtomicInteger(0);
        return (plan, event) -> {
            if (event.isAppend()) return false;
            return counter.incrementAndGet() % n == 0;
        };
    }

    /**
     * Trigger when an entry transitions to {@link PlanEntry.Status#REJECTED} —
     * either because an operator overrode it or because execution failed.
     * Useful for "fix it after a failure" workflows.
     */
    static ReplanTriggerPolicy onRejection() {
        return (plan, event) ->
                event.current() != null && event.current().status() == PlanEntry.Status.REJECTED;
    }

    /**
     * Trigger on any terminal transition — EXECUTED, REJECTED, or SUPERSEDED.
     * The replanner gets to react after every meaningful outcome.
     */
    static ReplanTriggerPolicy onTerminal() {
        return (plan, event) -> event.isTerminal();
    }

    /** Permissive OR: fires if any composed trigger fires. */
    static ReplanTriggerPolicy any(ReplanTriggerPolicy... policies) {
        Objects.requireNonNull(policies, "policies");
        List<ReplanTriggerPolicy> list = Arrays.asList(policies);
        return (plan, event) -> {
            for (ReplanTriggerPolicy p : list) {
                if (p.shouldReplan(plan, event)) return true;
            }
            return false;
        };
    }

    /** Conservative AND: fires only when all composed triggers fire. */
    static ReplanTriggerPolicy all(ReplanTriggerPolicy... policies) {
        Objects.requireNonNull(policies, "policies");
        List<ReplanTriggerPolicy> list = Arrays.asList(policies);
        return (plan, event) -> {
            for (ReplanTriggerPolicy p : list) {
                if (!p.shouldReplan(plan, event)) return false;
            }
            return !list.isEmpty();
        };
    }
}
