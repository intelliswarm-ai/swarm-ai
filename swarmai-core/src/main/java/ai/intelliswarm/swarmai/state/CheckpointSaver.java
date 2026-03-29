package ai.intelliswarm.swarmai.state;

import java.util.List;
import java.util.Optional;

/**
 * Persistence interface for workflow checkpoints.
 * Implementations store and retrieve {@link Checkpoint} snapshots so that
 * workflows can be resumed after failure, restart, or interruption.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link InMemoryCheckpointSaver} — for testing and development</li>
 * </ul>
 *
 * <p>Usage with {@link CompiledSwarm}:
 * <pre>{@code
 * CheckpointSaver saver = new InMemoryCheckpointSaver();
 *
 * CompiledSwarm swarm = SwarmGraph.create()
 *     .addAgent(agent)
 *     .addTask(task)
 *     .checkpointSaver(saver)
 *     .compileOrThrow();
 *
 * // Execute — checkpoints saved automatically
 * swarm.kickoff(state);
 *
 * // Resume from last checkpoint after failure
 * swarm.resume("workflow-id");
 * }</pre>
 */
public interface CheckpointSaver {

    /**
     * Saves a checkpoint. If a checkpoint with the same ID already exists,
     * it is replaced.
     */
    void save(Checkpoint checkpoint);

    /**
     * Loads the most recent checkpoint for a workflow.
     *
     * @param workflowId the workflow identifier
     * @return the latest checkpoint, or empty if none exist
     */
    Optional<Checkpoint> loadLatest(String workflowId);

    /**
     * Loads all checkpoints for a workflow, ordered by timestamp (oldest first).
     *
     * @param workflowId the workflow identifier
     * @return all checkpoints for this workflow
     */
    List<Checkpoint> loadAll(String workflowId);

    /**
     * Deletes all checkpoints for a workflow.
     *
     * @param workflowId the workflow identifier
     */
    void delete(String workflowId);
}
