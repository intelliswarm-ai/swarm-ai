package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * One turn in an Anthropic conversation. Role is either user (caller, tool results)
 * or assistant (prior model output, replayed for continuity).
 */
@PublicApi(since = "1.0.24")
public record AnthropicMessage(AnthropicRole role, List<AnthropicContent> content) {

    public AnthropicMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        content = List.copyOf(content);
    }

    public static AnthropicMessage userText(String text) {
        return new AnthropicMessage(AnthropicRole.USER, List.of(AnthropicContent.Text.of(text)));
    }

    public static AnthropicMessage assistantText(String text) {
        return new AnthropicMessage(AnthropicRole.ASSISTANT, List.of(AnthropicContent.Text.of(text)));
    }

    public static AnthropicMessage user(AnthropicContent... blocks) {
        return new AnthropicMessage(AnthropicRole.USER, List.of(blocks));
    }

    public static AnthropicMessage assistant(AnthropicContent... blocks) {
        return new AnthropicMessage(AnthropicRole.ASSISTANT, List.of(blocks));
    }
}
