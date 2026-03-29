package ai.intelliswarm.swarmai.state;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * A typed slot in an {@link AgentState} that controls how values are stored and merged.
 * Channels enable safe concurrent state updates by defining merge semantics via a {@link Reducer}.
 *
 * <p>Each channel has:
 * <ul>
 *   <li>An optional {@link Reducer} — defines how to merge old + new values (default: last-write-wins)</li>
 *   <li>An optional default supplier — provides an initial value when the channel is first read</li>
 * </ul>
 *
 * @param <T> the type of value this channel holds
 */
public interface Channel<T> {

    /**
     * Returns the reducer for merging state updates, if any.
     * When empty, last-write-wins semantics are used.
     */
    Optional<Reducer<T>> getReducer();

    /**
     * Returns a supplier for the default value, if any.
     * Called once when the channel is first read and has no value.
     */
    Optional<Supplier<T>> getDefault();

    /**
     * Computes the updated value for this channel given old and new values.
     * Delegates to the reducer if present, otherwise replaces with newValue.
     *
     * @param oldValue the current value (may be null)
     * @param newValue the incoming value
     * @return the resolved value
     */
    @SuppressWarnings("unchecked")
    default Object update(Object oldValue, Object newValue) {
        return getReducer()
                .map(reducer -> (Object) reducer.apply((T) oldValue, (T) newValue))
                .orElse(newValue);
    }
}
