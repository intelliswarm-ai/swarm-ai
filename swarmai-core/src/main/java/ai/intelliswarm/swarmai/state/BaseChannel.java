package ai.intelliswarm.swarmai.state;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A simple channel with optional reducer and default value.
 * When no reducer is provided, uses last-write-wins semantics.
 *
 * @param <T> the type of value this channel holds
 */
public class BaseChannel<T> implements Channel<T> {

    private final Reducer<T> reducer;
    private final Supplier<T> defaultSupplier;

    public BaseChannel() {
        this(null, null);
    }

    public BaseChannel(Reducer<T> reducer) {
        this(reducer, null);
    }

    public BaseChannel(Reducer<T> reducer, Supplier<T> defaultSupplier) {
        this.reducer = reducer;
        this.defaultSupplier = defaultSupplier;
    }

    @Override
    public Optional<Reducer<T>> getReducer() {
        return Optional.ofNullable(reducer);
    }

    @Override
    public Optional<Supplier<T>> getDefault() {
        return Optional.ofNullable(defaultSupplier);
    }
}
