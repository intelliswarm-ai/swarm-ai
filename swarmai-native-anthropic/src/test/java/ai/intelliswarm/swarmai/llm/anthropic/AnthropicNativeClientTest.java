package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint;
import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.CitationConfig;
import ai.intelliswarm.swarmai.agent.llm.CitationLocation;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.agent.llm.ThinkingBudget;
import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.CitationPageLocation;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end behavioural tests for {@link AnthropicNativeClient}. Mocks the
 * SDK's {@link AnthropicClient}, captures the {@link MessageCreateParams}
 * actually sent, and verifies that all Phase 1 features (prompt caching,
 * extended thinking, citations, document blocks, tool round-trip) are wired
 * into the wire format.
 *
 * <p>Doubles as living documentation for the adapter's API surface.
 */
class AnthropicNativeClientTest {

    private static final String MODEL = "claude-opus-4-7";

    @Test
    void cachingThinkingAndCitationsFlowEndToEnd() {
        // ----- given: a request with all Phase 1 capabilities engaged -----
        DocumentBlock.Pdf tenK = DocumentBlock.Pdf.ofBytes(
                "ACME-10K-2025", "ACME Corp 10-K", new byte[]{1, 2, 3, 4}, true);

        AnthropicToolSpec calculator = AnthropicToolSpec.of(
                "calculator",
                "Evaluate an arithmetic expression",
                Map.of(
                        "type", "object",
                        "properties", Map.of("expression",
                                Map.of("type", "string", "description", "expression to evaluate")),
                        "required", List.of("expression")));

        CachePolicy cachePolicy = CachePolicy.builder()
                .breakpoint(CacheBreakpoint.oneHour("tools"))
                .breakpoint(CacheBreakpoint.oneHour("system"))
                .breakpoint(CacheBreakpoint.fiveMinutes("documents"))
                .build();

        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .system("You are a financial analyst. Cite every claim back to the filing.")
                .tool(calculator)
                .document(tenK)
                .message(AnthropicMessage.userText("What was ACME's FY2025 operating margin?"))
                .maxOutputTokens(4096)
                .cachePolicy(cachePolicy)
                .thinkingBudget(ThinkingBudget.enabled(4096))
                .citationConfig(CitationConfig.ENABLED)
                .build();

        // ----- and: a mocked SDK client returning a realistic response -----
        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);

        Message stubbed = buildStubbedFinancialResponse();
        stubRawResponse(mockMessages, stubbed, "req_demo_001");

        // ----- when: the adapter sends -----
        AnthropicNativeClient adapter = AnthropicNativeClient.withClient(mockClient);
        AnthropicResponse resp = adapter.send(req);

        // ----- then: the request was built correctly -----
        ArgumentCaptor<MessageCreateParams> paramCaptor = ArgumentCaptor.forClass(MessageCreateParams.class);
        org.mockito.Mockito.verify(mockMessages.withRawResponse()).create(paramCaptor.capture());
        MessageCreateParams sent = paramCaptor.getValue();

        assertThat(sent.model()).isEqualTo(Model.of(MODEL));
        assertThat(sent.maxTokens()).isEqualTo(4096);

        // System used the cache-aware text-block form
        assertThat(sent.system()).isPresent();

        // Tools: the calculator carries cache_control because "tools" had a 1h breakpoint
        assertThat(sent.tools()).isPresent();
        var tools = sent.tools().get();
        assertThat(tools).hasSize(1);
        var lastTool = tools.get(tools.size() - 1).tool().get();
        assertThat(lastTool.cacheControl()).isPresent();
        assertThat(lastTool.cacheControl().get().ttl())
                .hasValue(CacheControlEphemeral.Ttl.TTL_1H);

        // Thinking: present with the requested budget
        assertThat(sent.thinking()).isPresent();
        assertThat(sent.thinking().get().enabled()).isPresent();
        assertThat(sent.thinking().get().enabled().get().budgetTokens()).isEqualTo(4096L);

        // ----- and: the response was parsed correctly -----
        assertThat(resp.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(resp.text()).contains("operating margin").contains("18.4%");

        // Citations were extracted with the right shape
        assertThat(resp.citations()).hasSize(1);
        var citation = resp.citations().get(0);
        assertThat(citation.documentId()).isEqualTo("ACME-10K-2025");
        assertThat(citation.documentTitle()).isEqualTo("ACME Corp 10-K");
        assertThat(citation.location()).isInstanceOf(CitationLocation.PageLocation.class);
        var pageLoc = (CitationLocation.PageLocation) citation.location();
        assertThat(pageLoc.startPageNumber()).isEqualTo(42);
        assertThat(pageLoc.endPageNumber()).isEqualTo(43);

        // Thinking blocks surfaced
        assertThat(resp.thinking()).hasSize(1);
        assertThat(resp.thinking().get(0).text()).contains("Let me check");

        // Cache telemetry parsed: 12000 read, 800 written at 5m
        assertThat(resp.tokenUsage().cacheUsage().cacheReadInputTokens()).isEqualTo(12_000L);
        assertThat(resp.tokenUsage().cacheUsage().cacheCreationFiveMinuteInputTokens()).isEqualTo(800L);
        assertThat(resp.tokenUsage().cacheUsage().hadCacheHit()).isTrue();
    }

    @Test
    void toolUseStopReasonSurfacesToolCalls() {
        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .message(AnthropicMessage.userText("What's 2+2?"))
                .tool(AnthropicToolSpec.of("calc", "math",
                        Map.of("type", "object",
                                "properties", Map.of("expression", Map.of("type", "string")),
                                "required", List.of("expression"))))
                .build();

        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);

        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("toolu_abc123")
                .name("calc")
                .input(com.anthropic.core.JsonValue.from(Map.of("expression", "2+2")))
                .caller(com.anthropic.models.messages.DirectCaller.builder().build())
                .build();
        Message msg = messageBuilder("msg_test", com.anthropic.models.messages.StopReason.TOOL_USE)
                .addContent(toolUse)
                .usage(minimalUsage(50, 20))
                .build();
        stubRawResponse(mockMessages, msg, "req_tool_use_001");

        AnthropicResponse resp = AnthropicNativeClient.withClient(mockClient).send(req);

        assertThat(resp.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(resp.needsToolExecution()).isTrue();
        assertThat(resp.toolUses()).hasSize(1);
        assertThat(resp.toolUses().get(0).id()).isEqualTo("toolu_abc123");
        assertThat(resp.toolUses().get(0).name()).isEqualTo("calc");
    }

    @Test
    void toolResultRoundTripBuildsCorrectMessage() {
        // Simulate the resume-after-tool-execution turn: build a user message with a ToolResult.
        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .message(AnthropicMessage.user(
                        AnthropicContent.ToolResult.success("toolu_abc123", "4")))
                .build();

        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);
        Message msg = messageBuilder("msg_final", com.anthropic.models.messages.StopReason.END_TURN)
                .addContent(TextBlock.builder()
                        .text("The answer is 4.")
                        .citations(java.util.Optional.empty())
                        .build())
                .usage(minimalUsage(60, 8))
                .build();
        stubRawResponse(mockMessages, msg, "req_round_trip_001");

        AnthropicResponse resp = AnthropicNativeClient.withClient(mockClient).send(req);
        assertThat(resp.text()).isEqualTo("The answer is 4.");
        assertThat(resp.stopReason()).isEqualTo(StopReason.END_TURN);
    }

    @Test
    void requestIdFromHeaderTakesPriorityOverMessageId() {
        // Regression: previously, AnthropicResponse.requestId() returned the
        // SDK's message id (msg_*) instead of the actual request-id HTTP header
        // (req_*) — breaking support / audit-trail correlation. With
        // withRawResponse() we now plumb the header value through.
        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .message(AnthropicMessage.userText("ping"))
                .build();

        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);

        Message msg = messageBuilder("msg_should_NOT_appear_in_requestId",
                com.anthropic.models.messages.StopReason.END_TURN)
                .addContent(TextBlock.builder()
                        .text("pong")
                        .citations(java.util.Optional.empty())
                        .build())
                .usage(minimalUsage(2, 2))
                .build();
        stubRawResponse(mockMessages, msg, "req_actual_header_value");

        AnthropicResponse resp = AnthropicNativeClient.withClient(mockClient).send(req);
        assertThat(resp.requestId()).isEqualTo("req_actual_header_value");
        assertThat(resp.requestId()).doesNotStartWith("msg_");
    }

    @Test
    void betaFlagsAreSentAsCommaJoinedAnthropicBetaHeader() {
        // Regression: AnthropicRequest.Builder#beta(...) used to only log the
        // requested flags; the SDK never saw them, so server-tool / MCP beta
        // opt-ins were silently dropped. Verify they're propagated as a single
        // comma-separated anthropic-beta header (Anthropic's wire format).
        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .message(AnthropicMessage.userText("ping"))
                .beta("tools-2024-04-04")
                .beta("computer-use-2025-01-24")
                .build();

        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);
        Message msg = messageBuilder("msg_beta_test",
                com.anthropic.models.messages.StopReason.END_TURN)
                .addContent(TextBlock.builder()
                        .text("pong")
                        .citations(java.util.Optional.empty())
                        .build())
                .usage(minimalUsage(2, 2))
                .build();
        stubRawResponse(mockMessages, msg, "req_beta_test");

        AnthropicNativeClient.withClient(mockClient).send(req);

        ArgumentCaptor<MessageCreateParams> paramCaptor = ArgumentCaptor.forClass(MessageCreateParams.class);
        org.mockito.Mockito.verify(mockMessages.withRawResponse()).create(paramCaptor.capture());
        MessageCreateParams sent = paramCaptor.getValue();
        List<String> betaHeaderValues = sent._additionalHeaders().values("anthropic-beta");
        assertThat(betaHeaderValues).hasSize(1);
        assertThat(betaHeaderValues.get(0)).isEqualTo("tools-2024-04-04,computer-use-2025-01-24");
    }

    @Test
    void requestIdFallsBackToMessageIdWhenHeaderAbsent() {
        // When the SDK reports no request-id (Optional.empty()), fall back to
        // the message id for traceability rather than null.
        AnthropicRequest req = AnthropicRequest.builder()
                .model(MODEL)
                .message(AnthropicMessage.userText("ping"))
                .build();

        AnthropicClient mockClient = mock(AnthropicClient.class);
        MessageService mockMessages = mock(MessageService.class);
        when(mockClient.messages()).thenReturn(mockMessages);
        Message msg = messageBuilder("msg_fallback_xyz",
                com.anthropic.models.messages.StopReason.END_TURN)
                .addContent(TextBlock.builder()
                        .text("pong")
                        .citations(java.util.Optional.empty())
                        .build())
                .usage(minimalUsage(2, 2))
                .build();
        stubRawResponse(mockMessages, msg, null);  // no header

        AnthropicResponse resp = AnthropicNativeClient.withClient(mockClient).send(req);
        assertThat(resp.requestId()).isEqualTo("msg_fallback_xyz");
    }

    // ---------- Helpers ----------

    /**
     * Stub the {@code messages().withRawResponse().create(...)} chain so the
     * production code can read both a parsed {@link Message} and the actual
     * {@code request-id} HTTP header. Pass {@code requestId == null} to
     * exercise the Optional.empty fallback path.
     */
    @SuppressWarnings("unchecked")
    private static void stubRawResponse(MessageService mockMessages, Message stubbed, String requestId) {
        MessageService.WithRawResponse mockRaw = mock(MessageService.WithRawResponse.class);
        com.anthropic.core.http.HttpResponseFor<Message> mockHttp =
                mock(com.anthropic.core.http.HttpResponseFor.class);
        when(mockMessages.withRawResponse()).thenReturn(mockRaw);
        when(mockRaw.create((MessageCreateParams) org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockHttp);
        when(mockHttp.parse()).thenReturn(stubbed);
        when(mockHttp.requestId()).thenReturn(java.util.Optional.ofNullable(requestId));
    }

    /** SDK's Usage builder validates that every optional slot is explicitly set. */
    private static Usage minimalUsage(long in, long out) {
        return Usage.builder()
                .inputTokens(in).outputTokens(out)
                .cacheCreationInputTokens(java.util.Optional.empty())
                .cacheCreation(java.util.Optional.empty())
                .cacheReadInputTokens(java.util.Optional.empty())
                .inferenceGeo(java.util.Optional.empty())
                .serverToolUse(java.util.Optional.empty())
                .serviceTier(java.util.Optional.empty())
                .build();
    }

    /** SDK's Message builder validates every Optional slot — fill the blanks. */
    private static Message.Builder messageBuilder(String id, com.anthropic.models.messages.StopReason stop) {
        return Message.builder()
                .id(id)
                .model(MODEL)
                .role(com.anthropic.core.JsonValue.from("assistant"))
                .type(com.anthropic.core.JsonValue.from("message"))
                .stopReason(stop)
                .container(java.util.Optional.empty())
                .stopDetails(java.util.Optional.empty())
                .stopSequence(java.util.Optional.empty());
    }

    private static Message buildStubbedFinancialResponse() {
        ThinkingBlock thinking = ThinkingBlock.builder()
                .thinking("Let me check the income statement on the consolidated financial statements page.")
                .signature("sig_abc")
                .build();
        TextCitation pageCitation = TextCitation.ofPageLocation(
                CitationPageLocation.builder()
                        .citedText("Operating income was $1,840 million on revenue of $10,000 million.")
                        .documentIndex(0L)
                        .documentTitle("ACME Corp 10-K")
                        .startPageNumber(42L)
                        .endPageNumber(43L)
                        .fileId(java.util.Optional.empty())
                        .build());
        TextBlock answer = TextBlock.builder()
                .text("ACME's FY2025 operating margin was 18.4%.")
                .citations(List.of(pageCitation))
                .build();
        Usage usage = Usage.builder()
                .inputTokens(2_000)
                .outputTokens(150)
                .cacheReadInputTokens(12_000L)
                .cacheCreationInputTokens(800L)
                .cacheCreation(CacheCreation.builder()
                        .ephemeral5mInputTokens(800L)
                        .ephemeral1hInputTokens(0L)
                        .build())
                .inferenceGeo(java.util.Optional.empty())
                .serverToolUse(java.util.Optional.empty())
                .serviceTier(java.util.Optional.empty())
                .build();
        return messageBuilder("msg_acme_demo", com.anthropic.models.messages.StopReason.END_TURN)
                .addContent(thinking)
                .addContent(answer)
                .usage(usage)
                .build();
    }
}
