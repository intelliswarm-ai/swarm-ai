package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lightweight wiring tests for {@link SpringAiLlmClient}. We stub Spring AI's
 * {@link ChatModel} to capture/return canned responses without booting any
 * real provider. The goal is to verify request/response translation, not
 * any specific provider's behaviour.
 */
@DisplayName("SpringAiLlmClient — Spring AI ChatModel bridge (1.0.25)")
class SpringAiLlmClientTest {

    @Test
    @DisplayName("identifies itself and reports STRICT_TOOL_USE + STREAMING")
    void identityAndFeatures() {
        SpringAiLlmClient client = new SpringAiLlmClient(stubChatModel(text("hi")));
        assertEquals("spring-ai", client.name());
        assertTrue(client.supportedFeatures().contains(LlmClient.Feature.STRICT_TOOL_USE));
        assertTrue(client.supportedFeatures().contains(LlmClient.Feature.STREAMING));
    }

    @Test
    @DisplayName("send() copies text + token usage from ChatResponse into LlmResponse")
    void sendCopiesTextAndUsage() {
        ChatResponse stubResponse = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .model("gpt-4.1-mini")
                        .usage(new DefaultUsage(42, 7))
                        .build());

        SpringAiLlmClient client = new SpringAiLlmClient(stubChatModel(stubResponse));
        LlmResponse out = client.send(LlmRequest.builder()
                .model("gpt-4.1-mini")
                .messages(List.of(LlmMessage.user("hello")))
                .build());

        assertEquals("answer", out.text());
        assertEquals("gpt-4.1-mini", out.modelId());
        assertEquals(LlmStopReason.END_TURN, out.stopReason());
        assertEquals(42, out.tokenUsage().inputTokens());
        assertEquals(7, out.tokenUsage().outputTokens());
        assertTrue(out.toolCalls().isEmpty());
    }

    @Test
    @DisplayName("send() parses tool calls and sets stop reason to TOOL_USE")
    void sendParsesToolCalls() {
        AssistantMessage assistant = new AssistantMessage(
                "", Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "file_read",
                        "{\"path\":\"foo.py\"}")));
        ChatResponse stubResponse = new ChatResponse(
                List.of(new Generation(assistant)),
                ChatResponseMetadata.builder().model("gpt-4.1-mini").build());

        SpringAiLlmClient client = new SpringAiLlmClient(stubChatModel(stubResponse));
        LlmResponse out = client.send(LlmRequest.builder()
                .model("gpt-4.1-mini")
                .messages(List.of(LlmMessage.user("read it"))).build());

        assertEquals(LlmStopReason.TOOL_USE, out.stopReason());
        assertEquals(1, out.toolCalls().size());
        LlmToolCall c = out.toolCalls().get(0);
        assertEquals("call-1", c.id());
        assertEquals("file_read", c.toolName());
        assertEquals(Map.of("path", "foo.py"), c.arguments());
    }

    @Test
    @DisplayName("send() bridges BaseTool into a Spring AI ToolCallback and ships its schema")
    void sendBridgesTools() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel stub = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                capturedPrompt.set(prompt);
                return new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().model("gpt-4.1-mini").build());
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };

        BaseTool stubTool = new EchoTool();
        SpringAiLlmClient client = new SpringAiLlmClient(stub);
        client.send(LlmRequest.builder()
                .model("gpt-4.1-mini")
                .messages(List.of(LlmMessage.user("hi")))
                .tools(List.of(stubTool))
                .build());

        Prompt p = capturedPrompt.get();
        assertNotNull(p);
        // The ChatOptions must be ToolCallingChatOptions when tools are passed.
        assertTrue(p.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions,
                "with tools, must use ToolCallingChatOptions, got " + p.getOptions().getClass());
        var opts = (org.springframework.ai.model.tool.ToolCallingChatOptions) p.getOptions();
        assertEquals(1, opts.getToolCallbacks().size());
        assertEquals("echo", opts.getToolCallbacks().get(0).getToolDefinition().name());
    }

    @Test
    @DisplayName("sendStreaming() forwards every text delta to the callback")
    void streamingForwardsDeltas() {
        ChatResponse c1 = new ChatResponse(
                List.of(new Generation(new AssistantMessage("Hel"))),
                ChatResponseMetadata.builder().model("m").build());
        ChatResponse c2 = new ChatResponse(
                List.of(new Generation(new AssistantMessage("lo "))),
                ChatResponseMetadata.builder().model("m").build());
        ChatResponse c3 = new ChatResponse(
                List.of(new Generation(new AssistantMessage("world"))),
                ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(3, 5)).build());

        ChatModel stub = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return c3; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(c1, c2, c3); }
        };

        List<String> chunks = new ArrayList<>();
        SpringAiLlmClient client = new SpringAiLlmClient(stub);
        LlmResponse out = client.sendStreaming(
                LlmRequest.builder().model("m").messages(List.of(LlmMessage.user("hi"))).build(),
                chunks::add);

        assertEquals(List.of("Hel", "lo ", "world"), chunks);
        assertEquals("Hello world", out.text());
    }

    @Test
    @DisplayName("sendStreaming() returns a sentinel empty response when the stream is empty")
    void streamingHandlesEmptyStream() {
        ChatModel stub = new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(""))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
        };
        List<String> chunks = new ArrayList<>();
        LlmResponse out = new SpringAiLlmClient(stub).sendStreaming(
                LlmRequest.builder().model("m").messages(List.of(LlmMessage.user("hi"))).build(),
                chunks::add);
        assertEquals("", out.text());
        assertEquals(List.of(), chunks);
        assertEquals(LlmStopReason.END_TURN, out.stopReason());
    }

    // --- helpers ---------------------------------------------------------

    private static ChatResponse text(String s) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(s))),
                ChatResponseMetadata.builder().model("m").build());
    }

    private static ChatModel stubChatModel(ChatResponse canned) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return canned; }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(canned); }
        };
    }

    /** Minimal BaseTool used only for the bridging test. */
    private static final class EchoTool implements BaseTool {
        @Override public String getFunctionName() { return "echo"; }
        @Override public String getDescription() { return "echoes its 'value' arg"; }
        @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
        @Override public boolean isAsync() { return false; }
        @Override public Object execute(Map<String, Object> p) { return p.get("value"); }
        @Override public Map<String, Object> getParameterSchema() {
            Map<String, Object> s = new HashMap<>();
            s.put("type", "object");
            s.put("properties", Map.of("value", Map.of("type", "string")));
            s.put("required", new String[]{"value"});
            return s;
        }
    }
}
