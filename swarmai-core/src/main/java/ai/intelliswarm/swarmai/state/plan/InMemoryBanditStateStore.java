package ai.intelliswarm.swarmai.state.plan;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory {@link BanditStateStore} useful for tests and for development
 * setups where bandit state should reset on restart. Holds a single
 * {@link BanditState} reference; load returns the current one, save replaces
 * it atomically.
 */
public final class InMemoryBanditStateStore implements BanditStateStore {

    private final AtomicReference<BanditState> state = new AtomicReference<>(BanditState.empty());

    @Override
    public BanditState load() {
        return state.get();
    }

    @Override
    public void save(BanditState newState) {
        Objects.requireNonNull(newState, "state");
        this.state.set(newState);
    }
}
