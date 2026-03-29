package ai.intelliswarm.swarmai.state;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A channel that accumulates values into a list.
 * New values are appended to the existing list rather than replacing it.
 * Supports deduplication to prevent identical entries.
 *
 * @param <T> the element type within the list
 */
public class AppenderChannel<T> implements Channel<List<T>> {

    private final boolean allowDuplicates;

    public AppenderChannel() {
        this(false);
    }

    public AppenderChannel(boolean allowDuplicates) {
        this.allowDuplicates = allowDuplicates;
    }

    @Override
    public Optional<Reducer<List<T>>> getReducer() {
        return Optional.of((oldList, newList) -> {
            if (oldList == null) {
                return new ArrayList<>(newList != null ? newList : List.of());
            }
            if (newList == null || newList.isEmpty()) {
                return new ArrayList<>(oldList);
            }

            if (allowDuplicates) {
                List<T> merged = new ArrayList<>(oldList);
                merged.addAll(newList);
                return merged;
            }

            // Deduplicate using a LinkedHashSet to preserve order
            LinkedHashSet<T> deduped = new LinkedHashSet<>(oldList);
            deduped.addAll(newList);
            return new ArrayList<>(deduped);
        });
    }

    @Override
    public Optional<Supplier<List<T>>> getDefault() {
        return Optional.of(ArrayList::new);
    }
}
