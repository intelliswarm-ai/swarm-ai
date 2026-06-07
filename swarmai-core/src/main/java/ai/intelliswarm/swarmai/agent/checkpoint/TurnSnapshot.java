package ai.intelliswarm.swarmai.agent.checkpoint;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Record of one turn snapshot in the {@link TurnSnapshotStore}.
 *
 * @param commitId   git commit id of the snapshot
 * @param runId      caller-provided id (typically the workflow run id)
 * @param turnIndex  zero-based turn position within the run
 * @param message    human-readable description of the turn ({@code "before tool: edit /x.txt"})
 * @param timestamp  when the snapshot was taken
 * @param attributes structured side-channel (model id, tool name, etc.)
 */
@PublicApi(since = "1.0.24")
public record TurnSnapshot(
        String commitId,
        String runId,
        int turnIndex,
        String message,
        Instant timestamp,
        Map<String, Object> attributes) {

    public TurnSnapshot {
        Objects.requireNonNull(commitId, "commitId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public String shortCommitId() {
        return commitId.length() > 8 ? commitId.substring(0, 8) : commitId;
    }
}
