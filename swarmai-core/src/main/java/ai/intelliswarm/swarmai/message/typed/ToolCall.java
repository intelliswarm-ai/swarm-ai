package ai.intelliswarm.swarmai.message.typed;

import java.util.Objects;

/**
 * A single tool invocation captured in the typed message history. Carried
 * inside {@link TypedAssistantMessage#toolCalls()} (when the assistant
 * decided to call a tool) and referenced by {@link TypedToolResultMessage#toolCallId()}
 * (when the tool's output flows back).
 *
 * @param id       opaque correlation id assigned by the LLM/runtime; matches
 *                 the eventual {@link TypedToolResultMessage#toolCallId()}
 * @param name     the tool's function name (e.g. {@code "calculator"})
 * @param argsJson the LLM-supplied arguments as a JSON string. Stored as-is
 *                 so callers can introspect without commitment to a schema.
 */
public record ToolCall(String id, String name, String argsJson) {
    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argsJson, "argsJson");
        if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /** Convenience: build with no arguments (LLM called the tool with {} ). */
    public static ToolCall of(String id, String name) {
        return new ToolCall(id, name, "{}");
    }
}
