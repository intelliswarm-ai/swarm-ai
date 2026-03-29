package ai.intelliswarm.swarmai.state;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A snapshot of workflow execution state at a specific point in time.
 * Checkpoints enable resumable workflows — if execution fails, the workflow
 * can be restarted from the last saved checkpoint instead of from scratch.
 *
 * <p>Each checkpoint captures:
 * <ul>
 *   <li>The workflow identity ({@code workflowId})</li>
 *   <li>Which task just completed ({@code completedTaskId})</li>
 *   <li>Which task runs next ({@code nextTaskId})</li>
 *   <li>The full agent state at that point</li>
 *   <li>Arbitrary metadata (token counts, iteration number, etc.)</li>
 * </ul>
 *
 * @param id              unique checkpoint identifier
 * @param workflowId      the swarm/workflow this checkpoint belongs to
 * @param completedTaskId the task that just finished (null if at start)
 * @param nextTaskId      the next task to execute (null if workflow is complete)
 * @param state           the agent state snapshot
 * @param timestamp       when the checkpoint was created
 * @param metadata        additional context (token usage, iteration count, etc.)
 */
public record Checkpoint(
        String id,
        String workflowId,
        String completedTaskId,
        String nextTaskId,
        AgentState state,
        Instant timestamp,
        Map<String, Object> metadata
) {
    public Checkpoint {
        Objects.requireNonNull(id, "Checkpoint ID cannot be null");
        Objects.requireNonNull(workflowId, "Workflow ID cannot be null");
        Objects.requireNonNull(state, "State cannot be null");
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (metadata == null) {
            metadata = Map.of();
        }
    }

    /**
     * Creates a checkpoint with auto-generated ID and current timestamp.
     */
    public static Checkpoint create(
            String workflowId,
            String completedTaskId,
            String nextTaskId,
            AgentState state,
            Map<String, Object> metadata) {
        return new Checkpoint(
                UUID.randomUUID().toString(),
                workflowId,
                completedTaskId,
                nextTaskId,
                state,
                Instant.now(),
                metadata != null ? metadata : Map.of()
        );
    }

    /**
     * Creates a checkpoint with auto-generated ID, current timestamp, and no metadata.
     */
    public static Checkpoint create(
            String workflowId,
            String completedTaskId,
            String nextTaskId,
            AgentState state) {
        return create(workflowId, completedTaskId, nextTaskId, state, Map.of());
    }
}
