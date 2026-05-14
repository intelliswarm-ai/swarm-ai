package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * One model-emitted tool invocation, provider-neutral.
 *
 * @param id        provider-issued id (matches the {@code toolUseId} on the eventual {@link LlmMessage#toolResult(String, String)})
 * @param toolName  the tool's function name (matches a registered {@code BaseTool})
 * @param arguments parsed argument map (the adapter has JSON-decoded the input)
 */
@PublicApi(since = "1.0.24")
public record LlmToolCall(String id, String toolName, Map<String, Object> arguments) {
    public LlmToolCall {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
