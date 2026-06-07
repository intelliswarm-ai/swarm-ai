package ai.intelliswarm.swarmai.llm.ollama;

import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmStopReason;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OllamaLlmClient client = new OllamaLlmClient(
            OllamaConfig.builder().baseUrl("http://x").option("num_ctx", 16384).build());

    // ---------- request mapping ----------

    @Test
    void requestBodyCarriesSystemPromptAndToolsAndOptions() throws Exception {
        LlmRequest req = LlmRequest.builder()
                .model("qwen2.5-coder:7b")
                .system("you are helpful")
                .message(LlmMessage.user("hi"))
                .tool(new FakeTool("file_read", "read a file"))
                .temperature(0.2)
                .maxOutputTokens(512)
                .build();

        Map<String, Object> body = client.toOllamaBody(req);
        JsonNode tree = JSON.valueToTree(body);

        assertThat(tree.get("model").asText()).isEqualTo("qwen2.5-coder:7b");
        assertThat(tree.get("stream").asBoolean()).isFalse();
        assertThat(tree.get("messages").size()).isEqualTo(2);
        assertThat(tree.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(tree.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(tree.get("tools").get(0).get("function").get("name").asText()).isEqualTo("file_read");
        assertThat(tree.get("options").get("num_ctx").asInt()).isEqualTo(16384);
        assertThat(tree.get("options").get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(tree.get("options").get("num_predict").asInt()).isEqualTo(512);
    }

    @Test
    void assistantWithToolCallsRoundTripsIntoOllamaShape() {
        var call = new ai.intelliswarm.swarmai.agent.llm.LlmToolCall(
                "tool-0", "file_read", Map.of("path", "README.md"));
        var assistantMsg = LlmMessage.assistantWithToolCalls("", List.of(call));

        Map<String, Object> out = client.toOllamaMessage(assistantMsg);

        assertThat(out.get("role")).isEqualTo("assistant");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcs = (List<Map<String, Object>>) out.get("tool_calls");
        assertThat(tcs).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) tcs.get(0).get("function");
        assertThat(function.get("name")).isEqualTo("file_read");
        assertThat(function.get("arguments")).isEqualTo(Map.of("path", "README.md"));
    }

    @Test
    void toolResultMessageCarriesToolCallId() {
        var toolMsg = LlmMessage.toolResult("tool-7", "content of README");
        Map<String, Object> out = client.toOllamaMessage(toolMsg);
        assertThat(out.get("role")).isEqualTo("tool");
        assertThat(out.get("content")).isEqualTo("content of README");
        assertThat(out.get("tool_call_id")).isEqualTo("tool-7");
        // No prior assistant tool_call known — tool_name is omitted.
        assertThat(out).doesNotContainKey("tool_name");
    }

    @Test
    void toolResultMessageCarriesToolNameFromPriorAssistantCall() {
        var call = new ai.intelliswarm.swarmai.agent.llm.LlmToolCall(
                "tool-0", "file_read", Map.of("path", "README.md"));
        var assistantMsg = LlmMessage.assistantWithToolCalls("", List.of(call));
        var toolMsg = LlmMessage.toolResult("tool-0", "content of README");
        LlmRequest req = LlmRequest.builder()
                .model("qwen2.5-coder:7b")
                .message(LlmMessage.user("read it"))
                .message(assistantMsg)
                .message(toolMsg)
                .build();

        Map<String, Object> body = client.toOllamaBody(req);
        JsonNode tree = JSON.valueToTree(body);
        JsonNode toolNode = tree.get("messages").get(2);
        assertThat(toolNode.get("role").asText()).isEqualTo("tool");
        assertThat(toolNode.get("tool_name").asText()).isEqualTo("file_read");
        assertThat(toolNode.get("tool_call_id").asText()).isEqualTo("tool-0");
    }

    // ---------- response mapping ----------

    @Test
    void parsesPlainTextResponseAsEndTurn() throws Exception {
        String body = """
                {
                  "model": "qwen2.5-coder:7b",
                  "message": {"role":"assistant","content":"Hello!"},
                  "done": true,
                  "done_reason": "stop",
                  "prompt_eval_count": 12,
                  "eval_count": 4
                }""";
        LlmResponse resp = client.parseResponse(body, "qwen2.5-coder:7b");
        assertThat(resp.text()).isEqualTo("Hello!");
        assertThat(resp.toolCalls()).isEmpty();
        assertThat(resp.stopReason()).isEqualTo(LlmStopReason.END_TURN);
        assertThat(resp.tokenUsage().inputTokens()).isEqualTo(12L);
        assertThat(resp.tokenUsage().outputTokens()).isEqualTo(4L);
        assertThat(resp.needsToolExecution()).isFalse();
    }

    @Test
    void parsesStructuredToolCallAsToolUse() throws Exception {
        String body = """
                {
                  "model": "qwen2.5-coder:7b",
                  "message": {
                    "role": "assistant",
                    "content": "",
                    "tool_calls": [
                      {"function": {"name":"file_read","arguments":{"path":"a.py"}}}
                    ]
                  },
                  "done": true,
                  "done_reason": "stop",
                  "prompt_eval_count": 80,
                  "eval_count": 12
                }""";
        LlmResponse resp = client.parseResponse(body, "qwen2.5-coder:7b");
        assertThat(resp.stopReason()).isEqualTo(LlmStopReason.TOOL_USE);
        assertThat(resp.needsToolExecution()).isTrue();
        assertThat(resp.toolCalls()).hasSize(1);
        var call = resp.toolCalls().get(0);
        assertThat(call.toolName()).isEqualTo("file_read");
        assertThat(call.arguments()).isEqualTo(Map.of("path", "a.py"));
        assertThat(call.id()).isEqualTo("tool-0");
    }

    @Test
    void parsesStringifiedToolArguments() throws Exception {
        // Some Ollama responses stringify "arguments" instead of nesting an object.
        String body = """
                {
                  "model":"qwen2.5-coder:7b",
                  "message":{"role":"assistant","content":"",
                             "tool_calls":[{"function":{"name":"grep","arguments":"{\\"pattern\\":\\"TODO\\"}"}}]},
                  "done":true,"done_reason":"stop",
                  "prompt_eval_count":1,"eval_count":1
                }""";
        LlmResponse resp = client.parseResponse(body, "qwen2.5-coder:7b");
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).arguments()).isEqualTo(Map.of("pattern", "TODO"));
    }

    // ---------- streaming ----------

    @Test
    void streamingEmitsDeltasInOrderAndAssemblesFinalResponse() {
        // Canned Ollama NDJSON: three text deltas then a done frame with stats.
        Stream<String> stream = Stream.of(
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"},\"done\":false}",
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\", \"},\"done\":false}",
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"world!\"},\"done\":false}",
                "{\"model\":\"qwen2.5-coder:7b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":5}"
        );

        List<String> seen = new ArrayList<>();
        var resp = client.assembleStreamingResponse(stream, "qwen2.5-coder:7b", seen::add);

        assertThat(seen).containsExactly("Hello", ", ", "world!");
        assertThat(resp.text()).isEqualTo("Hello, world!");
        assertThat(resp.stopReason()).isEqualTo(ai.intelliswarm.swarmai.agent.llm.LlmStopReason.END_TURN);
        assertThat(resp.tokenUsage().inputTokens()).isEqualTo(12L);
        assertThat(resp.tokenUsage().outputTokens()).isEqualTo(5L);
        assertThat(resp.toolCalls()).isEmpty();
    }

    @Test
    void streamingCapturesToolCallsFromFinalFrame() {
        Stream<String> stream = Stream.of(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":false}",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"file_read\",\"arguments\":{\"path\":\"x.py\"}}}]},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":80,\"eval_count\":7}"
        );

        List<String> seen = new ArrayList<>();
        var resp = client.assembleStreamingResponse(stream, "qwen2.5-coder:7b", seen::add);

        assertThat(seen).isEmpty();
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).toolName()).isEqualTo("file_read");
        assertThat(resp.stopReason()).isEqualTo(ai.intelliswarm.swarmai.agent.llm.LlmStopReason.TOOL_USE);
        assertThat(resp.needsToolExecution()).isTrue();
    }

    @Test
    void streamingThrowsWhenStreamEndsWithoutDoneFrame() {
        Stream<String> stream = Stream.of(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},\"done\":false}"
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                OllamaClientException.class,
                () -> client.assembleStreamingResponse(stream, "m", s -> {}),
                "stream missing done=true must surface as an error, not silent success");
    }

    @Test
    void streamingPropagatesEmbeddedError() {
        Stream<String> stream = Stream.of(
                "{\"error\":\"model 'nope' not found\"}"
        );

        var ex = org.junit.jupiter.api.Assertions.assertThrows(
                OllamaClientException.class,
                () -> client.assembleStreamingResponse(stream, "nope", s -> {}));
        assertThat(ex.getMessage()).contains("not found");
    }

    @Test
    void streamingBodyHasStreamTrueAndStaysSchemaCompatible() throws Exception {
        LlmRequest req = LlmRequest.builder()
                .model("qwen2.5-coder:7b")
                .system("be brief")
                .message(LlmMessage.user("hi"))
                .build();

        com.fasterxml.jackson.databind.JsonNode streamingTree =
                JSON.valueToTree(client.toOllamaBody(req, true));
        com.fasterxml.jackson.databind.JsonNode blockingTree =
                JSON.valueToTree(client.toOllamaBody(req, false));

        assertThat(streamingTree.get("stream").asBoolean()).isTrue();
        assertThat(blockingTree.get("stream").asBoolean()).isFalse();
        // Apart from `stream`, the two bodies must be structurally identical.
        assertThat(streamingTree.get("model")).isEqualTo(blockingTree.get("model"));
        assertThat(streamingTree.get("messages")).isEqualTo(blockingTree.get("messages"));
    }

    @Test
    void mapStopReasonHandlesCommonCases() {
        assertThat(OllamaLlmClient.mapStopReason("stop", true, List.of())).isEqualTo(LlmStopReason.END_TURN);
        assertThat(OllamaLlmClient.mapStopReason("length", true, List.of())).isEqualTo(LlmStopReason.MAX_TOKENS);
        assertThat(OllamaLlmClient.mapStopReason("max_tokens", true, List.of())).isEqualTo(LlmStopReason.MAX_TOKENS);
        assertThat(OllamaLlmClient.mapStopReason("stop_sequence", true, List.of())).isEqualTo(LlmStopReason.STOP_SEQUENCE);
        assertThat(OllamaLlmClient.mapStopReason("", true, List.of())).isEqualTo(LlmStopReason.END_TURN);
        assertThat(OllamaLlmClient.mapStopReason("weird", true, List.of())).isEqualTo(LlmStopReason.UNKNOWN);
        assertThat(OllamaLlmClient.mapStopReason("stop", false, List.of())).isEqualTo(LlmStopReason.UNKNOWN);
        assertThat(OllamaLlmClient.mapStopReason("stop", true,
                List.of(new ai.intelliswarm.swarmai.agent.llm.LlmToolCall("t", "n", Map.of()))))
                .isEqualTo(LlmStopReason.TOOL_USE);
    }

    // ---------- test fixture ----------

    private static final class FakeTool implements BaseTool {
        private final String name;
        private final String description;

        FakeTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override public String getFunctionName() { return name; }
        @Override public String getDescription() { return description; }
        @Override public Object execute(Map<String, Object> parameters) { return null; }
        @Override public Map<String, Object> getParameterSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("path", Map.of("type", "string")),
                    "required", List.of("path"));
        }
        @Override public boolean isAsync() { return false; }
        @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    }
}
