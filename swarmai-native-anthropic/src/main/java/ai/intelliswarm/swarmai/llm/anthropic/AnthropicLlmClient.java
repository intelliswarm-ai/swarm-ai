package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmRole;
import ai.intelliswarm.swarmai.agent.llm.LlmStopReason;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges the provider-neutral {@link LlmClient} SPI to the native Anthropic
 * adapter — so SwarmAI code that targets the SPI gets prompt caching,
 * extended thinking, citations, document blocks, vision, and strict tool use
 * when Anthropic is the backing provider.
 *
 * <p>Wrap a configured {@link AnthropicNativeClient} and the request flow is:
 * <ol>
 *   <li>{@link LlmRequest} → {@link AnthropicRequest} (this class)</li>
 *   <li>{@link AnthropicNativeClient#send} (SDK call)</li>
 *   <li>{@link AnthropicResponse} → {@link LlmResponse} (this class)</li>
 * </ol>
 */
@PublicApi(since = "1.0.24")
public final class AnthropicLlmClient implements LlmClient {

    private static final Set<Feature> SUPPORTED = EnumSet.of(
            Feature.PROMPT_CACHING,
            Feature.EXTENDED_THINKING,
            Feature.INLINE_CITATIONS,
            Feature.PDF_DOCUMENTS,
            Feature.VISION,
            Feature.STRICT_TOOL_USE,
            Feature.CACHE_TELEMETRY);

    private final AnthropicNativeClient delegate;

    public AnthropicLlmClient(AnthropicNativeClient delegate) {
        this.delegate = delegate;
    }

    public static AnthropicLlmClient from(AnthropicConfig config) {
        return new AnthropicLlmClient(AnthropicNativeClient.from(config));
    }

    public static AnthropicLlmClient fromEnv() {
        return new AnthropicLlmClient(AnthropicNativeClient.fromEnv());
    }

    @Override
    public String name() {
        return "anthropic-native";
    }

    @Override
    public Set<Feature> supportedFeatures() {
        return SUPPORTED;
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        AnthropicRequest anthropicReq = toAnthropic(request);
        AnthropicResponse anthropicResp = delegate.send(anthropicReq);
        return fromAnthropic(anthropicResp);
    }

    // ---------- request translation ----------

    AnthropicRequest toAnthropic(LlmRequest req) {
        AnthropicRequest.Builder b = AnthropicRequest.builder()
                .model(req.model())
                .maxOutputTokens(req.maxOutputTokens())
                .cachePolicy(req.cachePolicy())
                .thinkingBudget(req.thinkingBudget())
                .citationConfig(req.citationConfig())
                .documents(req.documents());

        if (req.system() != null) {
            b.system(req.system());
        }
        if (req.temperature() != null) {
            b.temperature(req.temperature());
        }
        for (LlmMessage m : req.messages()) {
            if (m.role() == LlmRole.SYSTEM) {
                // System messages in the message list (rare with the LlmRequest pattern)
                // — collapse into the system prompt rather than the message array.
                if (req.system() == null) b.system(m.content());
                continue;
            }
            b.message(toAnthropicMessage(m));
        }
        for (BaseTool tool : req.tools()) {
            b.tool(AnthropicToolSpec.of(
                    tool.getFunctionName(),
                    tool.getDescription(),
                    tool.getParameterSchema()));
        }
        return b.build();
    }

    private static AnthropicMessage toAnthropicMessage(LlmMessage m) {
        AnthropicRole role = switch (m.role()) {
            case USER, TOOL -> AnthropicRole.USER;
            case ASSISTANT -> AnthropicRole.ASSISTANT;
            case SYSTEM -> AnthropicRole.USER; // SYSTEM handled before this method runs
        };
        List<AnthropicContent> content = new ArrayList<>();
        if (m.role() == LlmRole.TOOL) {
            content.add(AnthropicContent.ToolResult.success(m.toolUseId(), m.content()));
        } else if (m.role() == LlmRole.ASSISTANT && !m.toolCalls().isEmpty()) {
            // Echo prior assistant turn including tool_use blocks
            if (!m.content().isEmpty()) {
                content.add(AnthropicContent.Text.of(m.content()));
            }
            for (LlmToolCall tc : m.toolCalls()) {
                content.add(new AnthropicContent.ToolUse(tc.id(), tc.toolName(), tc.arguments()));
            }
        } else {
            content.add(AnthropicContent.Text.of(m.content()));
        }
        return new AnthropicMessage(role, content);
    }

    // ---------- response translation ----------

    static LlmResponse fromAnthropic(AnthropicResponse r) {
        LlmStopReason stop = switch (r.stopReason()) {
            case END_TURN -> LlmStopReason.END_TURN;
            case MAX_TOKENS -> LlmStopReason.MAX_TOKENS;
            case STOP_SEQUENCE -> LlmStopReason.STOP_SEQUENCE;
            case TOOL_USE -> LlmStopReason.TOOL_USE;
            case REFUSAL -> LlmStopReason.REFUSAL;
            case PAUSE_TURN, UNKNOWN -> LlmStopReason.UNKNOWN;
        };
        List<LlmToolCall> toolCalls = new ArrayList<>();
        for (AnthropicContent.ToolUse tu : r.toolUses()) {
            toolCalls.add(new LlmToolCall(tu.id(), tu.name(), tu.input()));
        }
        return new LlmResponse(
                r.requestId(),
                r.modelId(),
                stop,
                r.text(),
                toolCalls,
                r.citations(),
                r.tokenUsage());
    }
}
