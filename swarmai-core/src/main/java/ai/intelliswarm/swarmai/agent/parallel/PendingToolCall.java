package ai.intelliswarm.swarmai.agent.parallel;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * One pending tool invocation, as seen by the parallel-safety analyzer. The
 * id matches whatever the LLM-emitted tool-use id was so the analyzer can
 * return groups keyed back to the original request.
 */
@PublicApi(since = "1.0.24")
public record PendingToolCall(String id, String toolName, Map<String, Object> arguments) {
    public PendingToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
