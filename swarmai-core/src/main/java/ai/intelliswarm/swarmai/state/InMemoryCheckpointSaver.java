package ai.intelliswarm.swarmai.state;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory checkpoint saver for testing and development.
 * Stores checkpoints in a {@link ConcurrentHashMap} — thread-safe but not persistent.
 * All data is lost when the JVM exits.
 *
 * <p>For production use, implement {@link CheckpointSaver} with a database backend
 * (JDBC, Redis, etc.).
 */
public class InMemoryCheckpointSaver implements CheckpointSaver {

    private final ConcurrentHashMap<String, List<Checkpoint>> store = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "Checkpoint cannot be null");
        store.compute(checkpoint.workflowId(), (key, existing) -> {
            List<Checkpoint> list = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
            // Replace if same ID exists, otherwise append
            list.removeIf(cp -> cp.id().equals(checkpoint.id()));
            list.add(checkpoint);
            return list;
        });
    }

    @Override
    public Optional<Checkpoint> loadLatest(String workflowId) {
        List<Checkpoint> checkpoints = store.get(workflowId);
        if (checkpoints == null || checkpoints.isEmpty()) {
            return Optional.empty();
        }
        return checkpoints.stream()
                .max(Comparator.comparing(Checkpoint::timestamp));
    }

    @Override
    public List<Checkpoint> loadAll(String workflowId) {
        List<Checkpoint> checkpoints = store.get(workflowId);
        if (checkpoints == null) {
            return List.of();
        }
        return checkpoints.stream()
                .sorted(Comparator.comparing(Checkpoint::timestamp))
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public void delete(String workflowId) {
        store.remove(workflowId);
    }

    /**
     * Returns the total number of checkpoints across all workflows.
     */
    public int size() {
        return store.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Clears all stored checkpoints.
     */
    public void clear() {
        store.clear();
    }
}
