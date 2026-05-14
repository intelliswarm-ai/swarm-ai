package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.InlineCitation;
import ai.intelliswarm.swarmai.agent.llm.TokenUsage;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * Native Anthropic response. {@code content} is the ordered list of blocks the
 * model emitted (text + tool_use + thinking + image are possible). Convenience
 * accessors flatten the common cases:
 * <ul>
 *   <li>{@link #text()} concatenates every {@link AnthropicContent.Text} block</li>
 *   <li>{@link #toolUses()} returns every {@link AnthropicContent.ToolUse}</li>
 *   <li>{@link #citations()} returns every citation across every text block</li>
 * </ul>
 *
 * @param requestId   Anthropic-issued request id (header {@code request-id}) — log this for support
 * @param modelId     the model that produced the response
 * @param stopReason  why the model stopped
 * @param content     ordered content blocks
 * @param tokenUsage  per-response token + cache + server-tool accounting
 */
@PublicApi(since = "1.0.24")
public record AnthropicResponse(
        String requestId,
        String modelId,
        StopReason stopReason,
        List<AnthropicContent> content,
        TokenUsage tokenUsage) {

    public AnthropicResponse {
        Objects.requireNonNull(stopReason, "stopReason must not be null");
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);
        if (tokenUsage == null) {
            tokenUsage = TokenUsage.ZERO;
        }
    }

    /** Concatenated text from every {@link AnthropicContent.Text} block, or empty string if none. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (AnthropicContent c : content) {
            if (c instanceof AnthropicContent.Text t) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** Every tool-use block the model requested; empty when {@link #stopReason()} ≠ TOOL_USE. */
    public List<AnthropicContent.ToolUse> toolUses() {
        return content.stream()
                .filter(AnthropicContent.ToolUse.class::isInstance)
                .map(AnthropicContent.ToolUse.class::cast)
                .toList();
    }

    /** Every citation across every text block. */
    public List<InlineCitation> citations() {
        return content.stream()
                .filter(AnthropicContent.Text.class::isInstance)
                .map(AnthropicContent.Text.class::cast)
                .flatMap(t -> t.citations().stream())
                .toList();
    }

    /** Every thinking block (empty when thinking was disabled or display=OMITTED). */
    public List<AnthropicContent.Thinking> thinking() {
        return content.stream()
                .filter(AnthropicContent.Thinking.class::isInstance)
                .map(AnthropicContent.Thinking.class::cast)
                .toList();
    }

    public boolean needsToolExecution() {
        return stopReason == StopReason.TOOL_USE && !toolUses().isEmpty();
    }
}
