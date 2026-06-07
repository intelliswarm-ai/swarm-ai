package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CitationConfig;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmStopReason;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicLlmClientTest {

    @Test
    void adapterDeclaresSupportedFeatures() {
        AnthropicLlmClient client = new AnthropicLlmClient(AnthropicNativeClient.withClient(null));
        var features = client.supportedFeatures();
        assertThat(features).contains(
                LlmClient.Feature.PROMPT_CACHING,
                LlmClient.Feature.EXTENDED_THINKING,
                LlmClient.Feature.INLINE_CITATIONS,
                LlmClient.Feature.PDF_DOCUMENTS,
                LlmClient.Feature.STRICT_TOOL_USE,
                LlmClient.Feature.CACHE_TELEMETRY);
        assertThat(client.name()).isEqualTo("anthropic-native");
    }

    @Test
    void requestTranslationPreservesSubstrate() {
        AnthropicLlmClient client = new AnthropicLlmClient(AnthropicNativeClient.withClient(null));
        LlmRequest req = LlmRequest.builder()
                .model("claude-opus-4-7")
                .system("You are an analyst")
                .message(LlmMessage.user("Analyse ACME"))
                .maxOutputTokens(8192)
                .cachePolicy(CachePolicy.builder()
                        .breakpoint(CacheBreakpoint.oneHour("system"))
                        .build())
                .thinkingBudget(ThinkingBudget.enabled(4096))
                .citationConfig(CitationConfig.ENABLED)
                .build();

        AnthropicRequest anthropic = client.toAnthropic(req);
        assertThat(anthropic.model()).isEqualTo("claude-opus-4-7");
        assertThat(anthropic.system()).isEqualTo("You are an analyst");
        assertThat(anthropic.maxOutputTokens()).isEqualTo(8192);
        assertThat(anthropic.cachePolicy().breakpoints()).hasSize(1);
        assertThat(anthropic.thinkingBudget().budgetTokens()).isEqualTo(4096);
        assertThat(anthropic.citationConfig().enabled()).isTrue();
        assertThat(anthropic.messages()).hasSize(1);
    }

    @Test
    void responseTranslationCarriesStopReasonAndUsage() {
        // Build a minimal AnthropicResponse and verify the translation
        var usage = new ai.intelliswarm.swarmai.agent.llm.TokenUsage(
                100, 50,
                new ai.intelliswarm.swarmai.agent.llm.CacheUsage(800, 0, 0),
                0, 0);
        AnthropicResponse a = new AnthropicResponse(
                "req-123", "claude-opus-4-7",
                StopReason.END_TURN,
                java.util.List.of(AnthropicContent.Text.of("Final answer.")),
                usage);

        var llm = AnthropicLlmClient.fromAnthropic(a);
        assertThat(llm.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(llm.text()).isEqualTo("Final answer.");
        assertThat(llm.tokenUsage().cacheUsage().cacheReadInputTokens()).isEqualTo(800);
        assertThat(llm.requestId()).isEqualTo("req-123");
        assertThat(llm.modelId()).isEqualTo("claude-opus-4-7");
    }

    @Test
    void toolUseResponseProducesToolCalls() {
        AnthropicContent.ToolUse tu = new AnthropicContent.ToolUse(
                "toolu_abc", "calc", java.util.Map.of("expression", "2+2"));
        AnthropicResponse a = new AnthropicResponse(
                "r", "claude-opus-4-7",
                StopReason.TOOL_USE,
                java.util.List.of(tu),
                ai.intelliswarm.swarmai.agent.llm.TokenUsage.ZERO);

        var llm = AnthropicLlmClient.fromAnthropic(a);
        assertThat(llm.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(llm.needsToolExecution()).isTrue();
        assertThat(llm.toolCalls()).hasSize(1);
        assertThat(llm.toolCalls().get(0).id()).isEqualTo("toolu_abc");
        assertThat(llm.toolCalls().get(0).toolName()).isEqualTo("calc");
    }

    @Test
    void toolResultMessageRoundsTripsAsUserToolResult() {
        AnthropicLlmClient client = new AnthropicLlmClient(AnthropicNativeClient.withClient(null));
        LlmRequest req = LlmRequest.builder()
                .model("claude")
                .message(LlmMessage.toolResult("toolu_abc", "4"))
                .build();
        AnthropicRequest anthropic = client.toAnthropic(req);
        assertThat(anthropic.messages().get(0).role()).isEqualTo(AnthropicRole.USER);
        var content = anthropic.messages().get(0).content().get(0);
        assertThat(content).isInstanceOf(AnthropicContent.ToolResult.class);
        var tr = (AnthropicContent.ToolResult) content;
        assertThat(tr.toolUseId()).isEqualTo("toolu_abc");
        assertThat(tr.content()).isEqualTo("4");
    }

    @Test
    void assistantWithToolCallsRoundsTrips() {
        AnthropicLlmClient client = new AnthropicLlmClient(AnthropicNativeClient.withClient(null));
        var toolCall = new ai.intelliswarm.swarmai.agent.llm.LlmToolCall(
                "toolu_x", "calc", java.util.Map.of("expression", "2+2"));
        LlmRequest req = LlmRequest.builder()
                .model("claude")
                .message(LlmMessage.user("compute 2+2"))
                .message(LlmMessage.assistantWithToolCalls("Let me calculate", java.util.List.of(toolCall)))
                .build();
        AnthropicRequest anthropic = client.toAnthropic(req);
        assertThat(anthropic.messages()).hasSize(2);
        var assistantMsg = anthropic.messages().get(1);
        assertThat(assistantMsg.role()).isEqualTo(AnthropicRole.ASSISTANT);
        assertThat(assistantMsg.content()).hasSize(2);  // text + tool_use
    }
}
