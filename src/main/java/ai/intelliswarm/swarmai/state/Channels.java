package ai.intelliswarm.swarmai.state;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Factory methods for creating commonly-used {@link Channel} instances.
 * Provides a clean, discoverable API for defining state schemas.
 *
 * <p>Usage example:
 * <pre>{@code
 * var schema = StateSchema.builder()
 *     .channel("messages", Channels.appender())
 *     .channel("count", Channels.counter())
 *     .channel("status", Channels.lastWriteWins("PENDING"))
 *     .build();
 * }</pre>
 */
public final class Channels {

    private Channels() {
        // utility class
    }

    /**
     * A channel that replaces the old value with the new value (last-write-wins).
     * This is the default merge strategy.
     */
    public static <T> Channel<T> lastWriteWins() {
        return new BaseChannel<>();
    }

    /**
     * A channel that replaces the old value with the new value, with a default initial value.
     */
    public static <T> Channel<T> lastWriteWins(T defaultValue) {
        return new BaseChannel<>(null, () -> defaultValue);
    }

    /**
     * A channel with a custom reducer and no default value.
     */
    public static <T> Channel<T> withReducer(Reducer<T> reducer) {
        return new BaseChannel<>(reducer);
    }

    /**
     * A channel with a custom reducer and a default value.
     */
    public static <T> Channel<T> withReducer(Reducer<T> reducer, Supplier<T> defaultSupplier) {
        return new BaseChannel<>(reducer, defaultSupplier);
    }

    /**
     * A channel that accumulates values into a list (deduplicating).
     */
    public static <T> Channel<List<T>> appender() {
        return new AppenderChannel<>(false);
    }

    /**
     * A channel that accumulates values into a list, optionally allowing duplicates.
     */
    public static <T> Channel<List<T>> appender(boolean allowDuplicates) {
        return new AppenderChannel<>(allowDuplicates);
    }

    /**
     * A channel that counts — increments the old value by the new value.
     * Default initial value is 0L.
     */
    public static Channel<Long> counter() {
        return new BaseChannel<>(
                (oldVal, newVal) -> {
                    long old = oldVal != null ? oldVal : 0L;
                    long inc = newVal != null ? newVal : 0L;
                    return old + inc;
                },
                () -> 0L
        );
    }

    /**
     * A channel that concatenates strings with a newline separator.
     */
    public static Channel<String> stringAppender() {
        return new BaseChannel<>(
                (oldVal, newVal) -> {
                    if (oldVal == null || oldVal.isEmpty()) return newVal;
                    if (newVal == null || newVal.isEmpty()) return oldVal;
                    return oldVal + "\n" + newVal;
                },
                () -> ""
        );
    }
}
