package ai.intelliswarm.swarmai.state.plan;

import ai.intelliswarm.swarmai.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * {@link PlanChannelAccessor} that reads from and writes to an
 * {@link AgentState} channel by name. Bridges the safety chain and the
 * swarm's state machinery without coupling either side directly to
 * {@code AgentState} (which is why the in-memory accessor still works for
 * tests and simple programs).
 *
 * <p>Wiring contract:
 * <ul>
 *   <li>{@code getState} must return the current {@link AgentState}. In a
 *       node-based swarm, this is typically the state passed to the active
 *       node; in simpler flows, it's a getter on a holder.</li>
 *   <li>{@code setState} receives an updated {@code AgentState} and must
 *       install it as the new current state — either by replacing a holder's
 *       reference, by emitting a partial-update for the next node to merge,
 *       or by storing it for the next read.</li>
 *   <li>{@code channelKey} is the state-key under which the
 *       {@link EvolvingPlan} lives. The schema must declare the channel using
 *       {@link ai.intelliswarm.swarmai.state.Channels#evolvingPlan()} so the
 *       reducer is in place for cross-node merges.</li>
 * </ul>
 *
 * <p><b>Concurrency.</b> The accessor serialises read-modify-write under a
 * single lock. Within a single process this is sufficient; across distributed
 * nodes the {@link EvolvingPlanReducer} on the channel handles concurrent
 * partials correctly because every {@code setState} call submits the full
 * post-mutation plan as a partial — append-only entries merge cleanly.
 *
 * <p>Listeners are invoked outside the lock with the {@code (previous, current)}
 * pairs computed by diffing the pre/post plans.
 */
public final class AgentStateBackedPlanChannelAccessor implements PlanChannelAccessor {

    /** Default channel key — matches the conventional name used in examples. */
    public static final String DEFAULT_CHANNEL_KEY = "plan";

    private final Supplier<AgentState> getState;
    private final Consumer<AgentState> setState;
    private final String channelKey;
    private final Object lock = new Object();
    private final List<PlanListener> listeners = new CopyOnWriteArrayList<>();

    public AgentStateBackedPlanChannelAccessor(Supplier<AgentState> getState,
                                                Consumer<AgentState> setState) {
        this(getState, setState, DEFAULT_CHANNEL_KEY);
    }

    public AgentStateBackedPlanChannelAccessor(Supplier<AgentState> getState,
                                                Consumer<AgentState> setState,
                                                String channelKey) {
        this.getState = Objects.requireNonNull(getState, "getState");
        this.setState = Objects.requireNonNull(setState, "setState");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
    }

    public String channelKey() {
        return channelKey;
    }

    @Override
    public EvolvingPlan get() {
        synchronized (lock) {
            return readPlan();
        }
    }

    @Override
    public EvolvingPlan update(UnaryOperator<EvolvingPlan> mutator) {
        Objects.requireNonNull(mutator, "mutator");

        EvolvingPlan before;
        EvolvingPlan after;
        synchronized (lock) {
            before = readPlan();
            after = mutator.apply(before);
            Objects.requireNonNull(after, "mutator returned null EvolvingPlan");
            AgentState current = getState.get();
            if (current == null) {
                throw new IllegalStateException("getState supplier returned null AgentState");
            }
            AgentState updated = current.withUpdate(Map.of(channelKey, after));
            setState.accept(updated);
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

    private EvolvingPlan readPlan() {
        AgentState s = getState.get();
        if (s == null) {
            return EvolvingPlan.empty();
        }
        return s.<EvolvingPlan>value(channelKey).orElse(EvolvingPlan.empty());
    }

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
