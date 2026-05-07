package ai.intelliswarm.swarmai.agent.subagent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Terminal outcome of a {@link Subagent}. Returned by
 * {@link Subagent#awaitResult(Duration)}.
 *
 * <p>For {@link SubagentStatus#COMPLETED}, {@link #output()} is the model's
 * final assistant message. For {@link SubagentStatus#FAILED} the same field
 * carries the failure message; for {@link SubagentStatus#TIMED_OUT} or
 * {@link SubagentStatus#CANCELLED}, it's a short diagnostic string.
 *
 * <p>{@link #metrics()} is a free-form map carrying tokens, durations, tool
 * call counts, and anything else the spawner chose to record.
 */
public record SubagentResult(
        String id,
        SubagentStatus status,
        String output,
        Map<String, Object> metrics) {

    public SubagentResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        output = output != null ? output : "";
        metrics = metrics != null ? Map.copyOf(metrics) : Map.of();
    }

    /** Convenience: true when {@link #status} is {@link SubagentStatus#COMPLETED}. */
    public boolean succeeded() {
        return status == SubagentStatus.COMPLETED;
    }

    /** Convenience: true when {@link #status#isTerminal()}. */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    public static SubagentResult success(String id, String output, Map<String, Object> metrics) {
        return new SubagentResult(id, SubagentStatus.COMPLETED, output, metrics);
    }

    public static SubagentResult failure(String id, String message, Map<String, Object> metrics) {
        return new SubagentResult(id, SubagentStatus.FAILED, message, metrics);
    }

    public static SubagentResult timedOut(String id, Map<String, Object> metrics) {
        return new SubagentResult(id, SubagentStatus.TIMED_OUT,
                "subagent timed out", metrics);
    }

    public static SubagentResult cancelled(String id, Map<String, Object> metrics) {
        return new SubagentResult(id, SubagentStatus.CANCELLED,
                "subagent cancelled", metrics);
    }

    /** Helper for spawners that want to attach common metrics in a builder style. */
    public static MetricsBuilder metricsBuilder() {
        return new MetricsBuilder();
    }

    public static final class MetricsBuilder {
        private final Map<String, Object> map = new LinkedHashMap<>();

        public MetricsBuilder put(String key, Object value) {
            map.put(Objects.requireNonNull(key, "key"), value);
            return this;
        }

        public Map<String, Object> build() {
            return Map.copyOf(map);
        }
    }
}
