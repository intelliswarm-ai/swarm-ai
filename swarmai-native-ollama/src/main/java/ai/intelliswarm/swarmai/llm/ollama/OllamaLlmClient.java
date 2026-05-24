package ai.intelliswarm.swarmai.llm.ollama;

import ai.intelliswarm.swarmai.agent.llm.CacheUsage;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmRole;
import ai.intelliswarm.swarmai.agent.llm.LlmStopReason;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.agent.llm.TokenUsage;
import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native Ollama implementation of the {@link LlmClient} SPI. Talks to
 * Ollama's {@code /api/chat} endpoint directly (non-streaming) using
 * {@link java.net.http.HttpClient} + Jackson.
 *
 * <p>Bypasses Spring AI's Ollama bridge — which has two issues this client
 * sidesteps:
 * <ul>
 *   <li>Spring AI's blocking deserializer can hang on Ollama's
 *       {@code application/octet-stream} streaming responses.</li>
 *   <li>Routing Ollama via the OpenAI-compat {@code /v1/chat/completions}
 *       shim doesn't reliably convert model-emitted tool calls to OpenAI's
 *       structured {@code tool_calls} field, so tools look like plain text
 *       to the consumer.</li>
 * </ul>
 *
 * <p>This client speaks Ollama's native chat schema, so {@code tool_calls}
 * arrive structured and Ollama-specific options ({@code num_ctx},
 * {@code num_predict}, {@code seed}, ...) flow through unchanged.
 *
 * <p>Use {@link #builder()} or {@link #fromEnv()} (reads {@code OLLAMA_BASE_URL}
 * and {@code OLLAMA_NUM_CTX}).
 */
@PublicApi(since = "1.0.27")
public final class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Features the native client honours. Streaming isn't wired yet (v1 is
    // blocking only); cache/thinking/citations are provider-specific to
    // Anthropic / OpenAI and don't have an Ollama analogue.
    private static final Set<Feature> SUPPORTED = EnumSet.of(Feature.STRICT_TOOL_USE);

    private final OllamaConfig config;
    private final HttpClient http;

    public OllamaLlmClient(OllamaConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build());
    }

    /** Test / power-user constructor: inject your own {@link HttpClient}. */
    public OllamaLlmClient(OllamaConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    public static OllamaLlmClient fromEnv() {
        return new OllamaLlmClient(OllamaConfig.fromEnv());
    }

    public static OllamaConfig.Builder builder() {
        return OllamaConfig.builder();
    }

    @Override
    public String name() {
        return "ollama-native";
    }

    @Override
    public Set<Feature> supportedFeatures() {
        return SUPPORTED;
    }

    @Override
    public LlmResponse send(LlmRequest request) {
        Map<String, Object> body = toOllamaBody(request);
        String payload;
        try {
            payload = JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new OllamaClientException("Failed to serialize Ollama request: " + e.getMessage(), -1, e);
        }

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(config.chatEndpoint()))
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResp;
        try {
            httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OllamaClientException("Ollama transport failure: " + e.getMessage(), -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaClientException("Ollama request interrupted", -1, e);
        }

        int status = httpResp.statusCode();
        String responseBody = httpResp.body();
        if (status < 200 || status >= 300) {
            throw new OllamaClientException(
                    "Ollama returned HTTP " + status + ": " + truncate(responseBody, 2_000), status);
        }

        try {
            return parseResponse(responseBody, request.model());
        } catch (IOException e) {
            throw new OllamaClientException(
                    "Failed to parse Ollama response: " + e.getMessage()
                            + " — body was: " + truncate(responseBody, 1_000),
                    status, e);
        }
    }

    // ---------- request mapping (LlmRequest → Ollama JSON) ----------

    Map<String, Object> toOllamaBody(LlmRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", buildMessages(req));
        body.put("stream", false);

        List<Map<String, Object>> tools = buildTools(req.tools());
        if (!tools.isEmpty()) body.put("tools", tools);

        Map<String, Object> opts = buildOptions(req);
        if (!opts.isEmpty()) body.put("options", opts);
        return body;
    }

    private List<Map<String, Object>> buildMessages(LlmRequest req) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (req.system() != null && !req.system().isBlank()) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", req.system());
            out.add(sys);
        }
        for (LlmMessage m : req.messages()) {
            out.add(toOllamaMessage(m));
        }
        return out;
    }

    Map<String, Object> toOllamaMessage(LlmMessage m) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", roleOf(m.role()));
        // Ollama accepts "content" on every role; for tool-result messages
        // the convention is content = the tool output text.
        msg.put("content", m.content());
        if (m.role() == LlmRole.ASSISTANT && !m.toolCalls().isEmpty()) {
            msg.put("tool_calls", m.toolCalls().stream().map(OllamaLlmClient::toOllamaToolCall).toList());
        }
        if (m.role() == LlmRole.TOOL && m.toolUseId() != null) {
            // Ollama doesn't strictly require tool_call_id on tool replies
            // but newer versions accept it; including it for round-trip fidelity.
            msg.put("tool_call_id", m.toolUseId());
        }
        return msg;
    }

    private static Map<String, Object> toOllamaToolCall(LlmToolCall call) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.toolName());
        function.put("arguments", call.arguments());
        wrapper.put("function", function);
        return wrapper;
    }

    private static String roleOf(LlmRole role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private List<Map<String, Object>> buildTools(List<BaseTool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BaseTool t : tools) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", t.getFunctionName());
            String desc = t.getDescription();
            if (desc != null) function.put("description", desc);
            Map<String, Object> schema = t.getParameterSchema();
            // Ollama expects an OpenAI-compatible JSON Schema in "parameters";
            // most BaseTool implementations already return that shape, but
            // fall back to a permissive object schema if not.
            if (schema == null || schema.isEmpty()) {
                schema = Map.of("type", "object", "properties", Map.of());
            }
            function.put("parameters", schema);
            wrap.put("function", function);
            out.add(wrap);
        }
        return out;
    }

    private Map<String, Object> buildOptions(LlmRequest req) {
        Map<String, Object> opts = new LinkedHashMap<>(config.defaultOptions());
        if (req.temperature() != null) opts.put("temperature", req.temperature());
        if (req.maxOutputTokens() > 0) opts.put("num_predict", req.maxOutputTokens());
        return opts;
    }

    // ---------- response mapping (Ollama JSON → LlmResponse) ----------

    LlmResponse parseResponse(String body, String requestedModel) throws IOException {
        JsonNode root = JSON.readTree(body);
        JsonNode messageNode = root.path("message");
        String text = messageNode.path("content").asText("");
        List<LlmToolCall> toolCalls = parseToolCalls(messageNode.path("tool_calls"));

        String modelId = root.path("model").asText(requestedModel);
        String doneReason = root.path("done_reason").asText("");
        boolean done = root.path("done").asBoolean(true);
        LlmStopReason stop = mapStopReason(doneReason, done, toolCalls);

        long promptTokens = root.path("prompt_eval_count").asLong(0);
        long outputTokens = root.path("eval_count").asLong(0);
        TokenUsage usage = new TokenUsage(promptTokens, outputTokens, CacheUsage.NONE, 0L, 0L);

        // Ollama doesn't issue request IDs; mint a local one so callers that
        // log on this field have something stable.
        String requestId = "ollama-" + UUID.randomUUID().toString().substring(0, 8);
        return new LlmResponse(requestId, modelId, stop, text, toolCalls, List.of(), usage);
    }

    @SuppressWarnings("unchecked")
    List<LlmToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || toolCallsNode.isMissingNode() || !toolCallsNode.isArray()) {
            return List.of();
        }
        List<LlmToolCall> out = new ArrayList<>();
        int idx = 0;
        for (JsonNode call : toolCallsNode) {
            // Ollama wraps each tool call as { "function": { "name": ..., "arguments": {...} } }.
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isEmpty()) continue;

            JsonNode argsNode = function.path("arguments");
            Map<String, Object> args;
            try {
                if (argsNode.isObject()) {
                    args = JSON.convertValue(argsNode, MAP_TYPE);
                } else if (argsNode.isTextual()) {
                    // Some Ollama responses stringify arguments. Parse them.
                    String raw = argsNode.asText("");
                    args = raw.isBlank() ? Map.of() : JSON.readValue(raw, MAP_TYPE);
                } else {
                    args = Map.of();
                }
            } catch (Exception e) {
                log.warn("Could not parse tool_call arguments for '{}': {}. Falling back to empty args.",
                        name, e.getMessage());
                args = Map.of();
            }

            // Ollama doesn't always supply an id; mint a stable one per index.
            String id = call.path("id").asText("");
            if (id.isEmpty()) id = "tool-" + idx;
            out.add(new LlmToolCall(id, name, args));
            idx++;
        }
        return out;
    }

    static LlmStopReason mapStopReason(String doneReason, boolean done, List<LlmToolCall> toolCalls) {
        if (!toolCalls.isEmpty()) return LlmStopReason.TOOL_USE;
        if (!done) return LlmStopReason.UNKNOWN;
        return switch (doneReason == null ? "" : doneReason.toLowerCase()) {
            case "stop", "end_turn", "" -> LlmStopReason.END_TURN;
            case "length", "max_tokens" -> LlmStopReason.MAX_TOKENS;
            case "stop_sequence" -> LlmStopReason.STOP_SEQUENCE;
            default -> LlmStopReason.UNKNOWN;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated]";
    }
}
