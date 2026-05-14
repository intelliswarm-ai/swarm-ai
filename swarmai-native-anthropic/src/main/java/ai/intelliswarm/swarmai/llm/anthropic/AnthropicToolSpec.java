package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * Tool contract sent to the model. {@code inputSchema} is a JSON Schema object
 * describing the tool's arguments. {@code strict} requests schema-conformant
 * inputs (Anthropic's strict tool use).
 *
 * @param name        tool name (matches what the model emits in {@link AnthropicContent.ToolUse#name})
 * @param description prompt-engineering text the model sees when picking tools
 * @param inputSchema JSON Schema object (typically with {@code type: "object"}, {@code properties}, {@code required})
 * @param strict      when true, request the strict-tool-use beta behaviour
 * @param cacheable   when true, attach a cache breakpoint after this tool so the prefix is cached
 */
@PublicApi(since = "1.0.24")
public record AnthropicToolSpec(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean strict,
        boolean cacheable) {

    public AnthropicToolSpec {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        inputSchema = Map.copyOf(inputSchema);
    }

    public static AnthropicToolSpec of(String name, String description, Map<String, Object> inputSchema) {
        return new AnthropicToolSpec(name, description, inputSchema, false, false);
    }
}
