package ai.intelliswarm.swarmai.state;

import java.util.function.BiFunction;

/**
 * Defines how concurrent state updates for a single channel are merged.
 * Inspired by Redux reducers — given an old value and a new value, produce
 * the merged result.
 *
 * @param <T> the type of value in the channel
 */
@FunctionalInterface
public interface Reducer<T> extends BiFunction<T, T, T> {

    /**
     * Merge the old value with the new value.
     *
     * @param oldValue the current value in the channel (may be null on first write)
     * @param newValue the incoming value
     * @return the merged result
     */
    @Override
    T apply(T oldValue, T newValue);
}
