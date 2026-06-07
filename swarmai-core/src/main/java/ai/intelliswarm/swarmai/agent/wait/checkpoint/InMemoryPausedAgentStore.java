package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Map-backed {@link PausedAgentStore} for tests and single-JVM harnesses.
 * Insertion-ordered so {@link #all()} returns records in the order they were
 * saved. Synchronized — fine for typical write-once-per-pause workloads.
 *
 * <p>Does not survive process restart. For genuine cross-session resume use a
 * persistent implementation (JDBC, Redis, S3).
 */
public final class InMemoryPausedAgentStore implements PausedAgentStore {

    private final Map<String, PausedAgent> store = new LinkedHashMap<>();

    @Override
    public synchronized void save(PausedAgent agent) {
        Objects.requireNonNull(agent, "agent");
        store.put(agent.id(), agent);
    }

    @Override
    public synchronized Optional<PausedAgent> load(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public synchronized List<PausedAgent> all() {
        return List.copyOf(store.values());
    }

    @Override
    public synchronized boolean contains(String id) {
        return id != null && store.containsKey(id);
    }

    @Override
    public synchronized boolean delete(String id) {
        if (id == null) return false;
        return store.remove(id) != null;
    }

    @Override
    public synchronized int deleteExpired() {
        int removed = 0;
        var iter = store.values().iterator();
        while (iter.hasNext()) {
            if (iter.next().hasExpired()) {
                iter.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public synchronized int size() {
        return store.size();
    }
}
