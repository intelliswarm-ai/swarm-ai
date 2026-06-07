package ai.intelliswarm.swarmai.observability.trajectory;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row in a trajectory log — the canonical record of a single agent run.
 * Designed for ShareGPT-style downstream consumption: RL training, SFT data
 * curation, regression analysis, eval scorecards.
 *
 * <p>{@link Turn}s capture the bidirectional conversation. {@link #success}
 * is the framework's verdict on whether the run achieved its goal — used by
 * {@link TrajectoryWriter} to split into success/failure JSONL streams.
 *
 * @param runId         stable id (typically a workflow run id)
 * @param agentId       which agent emitted this trajectory
 * @param goal          the agent's goal at the start of the run
 * @param turns         ordered list of conversation turns
 * @param success       did the run succeed (per the harness's verdict)
 * @param successReason free-text rationale (cited rule, score, etc.)
 * @param startedAt     wall-clock start
 * @param endedAt       wall-clock end
 * @param attributes    structured side-channel data — model id, token totals, cost, framework value score, …
 */
@PublicApi(since = "1.0.24")
public record TrajectoryEntry(
        String runId,
        String agentId,
        String goal,
        List<Turn> turns,
        boolean success,
        String successReason,
        Instant startedAt,
        Instant endedAt,
        Map<String, Object> attributes) {

    public TrajectoryEntry {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(turns, "turns must not be null");
        turns = List.copyOf(turns);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * One conversation turn. {@code role} is one of {@code user},
     * {@code assistant}, {@code tool} (tool-result turn), {@code system}.
     */
    @PublicApi(since = "1.0.24")
    public record Turn(String role, String content, Map<String, Object> meta) {
        public Turn {
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(content, "content must not be null");
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }

        public static Turn user(String content) { return new Turn("user", content, Map.of()); }
        public static Turn assistant(String content) { return new Turn("assistant", content, Map.of()); }
        public static Turn tool(String content) { return new Turn("tool", content, Map.of()); }
        public static Turn system(String content) { return new Turn("system", content, Map.of()); }
    }
}
