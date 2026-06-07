package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single content block inside an Anthropic message — request or response.
 * Sealed for exhaustive pattern-matching at the adapter boundary.
 */
@PublicApi(since = "1.0.24")
public sealed interface AnthropicContent permits
        AnthropicContent.Text,
        AnthropicContent.ToolUse,
        AnthropicContent.ToolResult,
        AnthropicContent.Thinking,
        AnthropicContent.Image {

    /**
     * Plain text. {@code citations} is non-empty only when the response was
     * generated against a document with citations enabled.
     */
    @PublicApi(since = "1.0.24")
    record Text(String text, List<InlineCitation> citations) implements AnthropicContent {
        public Text {
            Objects.requireNonNull(text, "text must not be null");
            citations = citations == null ? List.of() : List.copyOf(citations);
        }

        public static Text of(String text) {
            return new Text(text, List.of());
        }
    }

    /**
     * Model-requested tool invocation. {@code input} is the parsed JSON arguments.
     * Caller is responsible for executing and replying with a matching
     * {@link ToolResult} carrying the same {@code id}.
     */
    @PublicApi(since = "1.0.24")
    record ToolUse(String id, String name, Map<String, Object> input) implements AnthropicContent {
        public ToolUse {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    /**
     * Caller's reply to a prior {@link ToolUse}. {@code toolUseId} must match.
     * {@code content} carries the tool's textual output (or error message).
     */
    @PublicApi(since = "1.0.24")
    record ToolResult(String toolUseId, String content, boolean isError) implements AnthropicContent {
        public ToolResult {
            Objects.requireNonNull(toolUseId, "toolUseId must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }

        public static ToolResult success(String toolUseId, String content) {
            return new ToolResult(toolUseId, content, false);
        }

        public static ToolResult error(String toolUseId, String message) {
            return new ToolResult(toolUseId, message, true);
        }
    }

    /**
     * Model's chain-of-thought block. Populated only when the request used
     * {@link ai.intelliswarm.swarmai.agent.llm.ThinkingBudget} with display
     * {@link ai.intelliswarm.swarmai.agent.llm.ThinkingDisplay#FULL} or
     * {@link ai.intelliswarm.swarmai.agent.llm.ThinkingDisplay#SUMMARIZED}.
     * {@code signature} is the opaque continuation token used to verify the
     * block on subsequent turns.
     */
    @PublicApi(since = "1.0.24")
    record Thinking(String text, String signature) implements AnthropicContent {
        public Thinking {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /**
     * Vision input. Exactly one of {@code base64Data} / {@code mediaType} or
     * {@code url} must be set.
     */
    @PublicApi(since = "1.0.24")
    record Image(String base64Data, String mediaType, String url) implements AnthropicContent {
        public Image {
            boolean hasInline = base64Data != null && mediaType != null;
            boolean hasUrl = url != null;
            if (hasInline == hasUrl) {
                throw new IllegalArgumentException("exactly one of (base64Data + mediaType) or url must be set");
            }
        }

        public static Image base64(String mediaType, String base64Data) {
            return new Image(base64Data, mediaType, null);
        }

        public static Image url(String url) {
            return new Image(null, null, url);
        }
    }
}
