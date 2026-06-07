package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * SPI for durable storage of {@link PausedAgent} records. Implementations
 * persist enough state to rehydrate the agent in a fresh JVM session.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link InMemoryPausedAgentStore} — Map-backed, for tests and single-JVM
 *       harnesses where survival across process restarts isn't needed (yet still
 *       want the architectural separation).</li>
 * </ul>
 *
 * <p>Production implementations should persist to a durable substrate
 * (Postgres, Redis with persistence enabled, S3, etc.) so paused agents
 * survive process restart. Serialisation of {@code PausedAgent} hits one
 * non-trivial field — {@code List<TypedMessage>} — which is a sealed
 * interface; standard Jackson/protobuf adapters handle it via type info on
 * the variants.
 */
public interface PausedAgentStore {

    /** Persist a paused agent. If a record with the same id exists, replace it. */
    void save(PausedAgent agent);

    /** Load a paused agent by its (= wait) id. */
    Optional<PausedAgent> load(String id);

    /** Snapshot of every paused agent currently in the store, in insertion order. */
    List<PausedAgent> all();

    /** True if a record with this id exists. */
    boolean contains(String id);

    /** Remove the record. Returns true if it existed and was removed. */
    boolean delete(String id);

    /**
     * Remove every record whose {@link PausedAgent#hasExpired()} is true.
     * Returns the count removed. Stores may schedule this on a timer or
     * leave it to harness code.
     */
    int deleteExpired();

    /** Number of records currently stored. */
    int size();
}
