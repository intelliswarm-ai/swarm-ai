package ai.intelliswarm.swarmai.state.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Default {@link PlanChannelAccessor} for tests and simple programs that don't
 * need to plumb a plan through {@link ai.intelliswarm.swarmai.state.AgentState}.
 * Holds a single {@link EvolvingPlan} reference under a coarse lock and notifies
 * listeners after each successful update.
 *
 * <p>For real swarm runs the accessor should be backed by the active
 * {@code AgentState} channel — a future {@code AgentStateBackedPlanChannelAccessor}
 * will provide that bridge. This class exists so safety-chain wiring (mutation
 * guards, trainers) can be exercised end-to-end without touching {@code AgentState}.
 */
public final class InMemoryPlanChannelAccessor implements PlanChannelAccessor {

    private final Object lock = new Object();
    private EvolvingPlan plan;
    private final List<PlanListener> listeners = new CopyOnWriteArrayList<>();

    public InMemoryPlanChannelAccessor() {
        this(EvolvingPlan.empty());
    }

    public InMemoryPlanChannelAccessor(EvolvingPlan initial) {
        this.plan = Objects.requireNonNull(initial, "initial");
    }

    @Override
    public EvolvingPlan get() {
        synchronized (lock) {
            return plan;
        }
    }

    @Override
    public EvolvingPlan update(UnaryOperator<EvolvingPlan> mutator) {
        Objects.requireNonNull(mutator, "mutator");

        EvolvingPlan before;
        EvolvingPlan after;
        synchronized (lock) {
            before = plan;
            after = mutator.apply(before);
            Objects.requireNonNull(after, "mutator returned null EvolvingPlan");
            plan = after;
        }
        fireTransitions(before, after);
        return after;
    }

    @Override
    public void addListener(PlanListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(PlanListener listener) {
        listeners.remove(listener);
    }

    /**
     * Diff old/new plans and notify listeners for every entry whose status
     * changed (including newly-appended entries, which are reported as
     * {@code (null, current)}).
     */
    private void fireTransitions(EvolvingPlan before, EvolvingPlan after) {
        if (listeners.isEmpty()) return;

        List<PlanListener> snapshot = new ArrayList<>(listeners);
        for (PlanEntry current : after.entries()) {
            PlanEntry previous = before.byId(current.id()).orElse(null);
            if (previous == null) {
                for (PlanListener l : snapshot) l.onTransition(null, current);
            } else if (previous.status() != current.status()) {
                for (PlanListener l : snapshot) l.onTransition(previous, current);
            }
        }
    }
}
