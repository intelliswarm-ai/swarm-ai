package ai.intelliswarm.swarmai.llm.ollama;

import ai.intelliswarm.swarmai.agent.llm.CacheUsage;
import ai.intelliswarm.swarmai.agent.llm.LlmClient;
import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRequest;
import ai.intelliswarm.swarmai.agent.llm.LlmResponse;
import ai.intelliswarm.swarmai.agent.llm.LlmRole;
import ai.intelliswarm.swarmai.agent.llm.LlmStopReason;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.agent.llm.StreamingLlmClient;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

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
public final class OllamaLlmClient implements StreamingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    // Features the native client honours. Streaming isn't wired yet (v1 is
    // blocking only); cache/thinking/citations are provider-specific to
    // Anthropic / OpenAI and don't have an Ollama analogue.
    private static final Set<Feature> SUPPORTED = EnumSet.of(Feature.STRICT_TOOL_USE, Feature.STREAMING);

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
        String payload = serializeBody(toOllamaBody(request, false));
        HttpRequest httpReq = buildHttpRequest(payload, "application/json");
        HttpResponse<String> httpResp = sendBlocking(httpReq);
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

    @Override
    public LlmResponse sendStreaming(LlmRequest request, Consumer<String> textDelta) {
        Consumer<String> sink = textDelta != null ? textDelta : s -> {};
        String payload = serializeBody(toOllamaBody(request, true));
        HttpRequest httpReq = buildHttpRequest(payload, "application/x-ndjson");

        // We use ofInputStream() rather than ofLines() because the latter
        // buffers aggressively under HTTP/1.1 chunked transfer — for Ollama's
        // NDJSON we need each line to surface as soon as Ollama flushes it.
        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new OllamaClientException("Ollama streaming transport failure: " + e.getMessage(), -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaClientException("Ollama streaming request interrupted", -1, e);
        }

        int status = resp.statusCode();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {

            if (status < 200 || status >= 300) {
                StringBuilder errBody = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines++ < 50) {
                    errBody.append(line).append('\n');
                }
                throw new OllamaClientException(
                        "Ollama streaming returned HTTP " + status + ": " + truncate(errBody.toString(), 2_000),
                        status);
            }

            return assembleStreamingResponse(readerLines(reader), request.model(), sink);
        } catch (IOException e) {
            throw new OllamaClientException(
                    "Ollama streaming I/O failure: " + e.getMessage(), status, e);
        }
    }

    /**
     * Lazy stream over a {@link java.io.BufferedReader}'s lines that yields
     * each line as soon as the reader hands it back — unlike
     * {@link java.io.BufferedReader#lines()} which uses spliterator buffering
     * that can starve a slow producer.
     */
    private static Stream<String> readerLines(java.io.BufferedReader reader) {
        java.util.Iterator<String> it = new java.util.Iterator<>() {
            String next;
            boolean done;
            @Override public boolean hasNext() {
                if (done) return false;
                if (next != null) return true;
                try {
                    next = reader.readLine();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                if (next == null) { done = true; return false; }
                return true;
            }
            @Override public String next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                String out = next; next = null; return out;
            }
        };
        return java.util.stream.StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(it,
                        java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL),
                false);
    }

    /** Drain Ollama's NDJSON stream, emit text deltas, build the final response. */
    LlmResponse assembleStreamingResponse(Stream<String> lines, String requestedModel, Consumer<String> sink) {
        StringBuilder text = new StringBuilder();
        List<LlmToolCall> toolCalls = List.of();
        long promptTokens = 0;
        long outputTokens = 0;
        String modelId = requestedModel;
        String doneReason = "";
        boolean sawDone = false;

        for (String line : (Iterable<String>) lines::iterator) {
            if (line == null || line.isBlank()) continue;
            JsonNode root;
            try {
                root = JSON.readTree(line);
            } catch (IOException e) {
                log.warn("Skipping malformed Ollama stream line: {}", truncate(line, 200));
                continue;
            }
            if (root.hasNonNull("error")) {
                throw new OllamaClientException(
                        "Ollama streaming error: " + root.get("error").asText(), -1);
            }
            // model + token counts may arrive on any frame; final frame is authoritative.
            if (root.hasNonNull("model")) modelId = root.path("model").asText(modelId);

            JsonNode messageNode = root.path("message");
            if (messageNode.isObject()) {
                String delta = messageNode.path("content").asText("");
                if (!delta.isEmpty()) {
                    text.append(delta);
                    sink.accept(delta);
                    if (log.isDebugEnabled()) log.debug("delta: {}", delta);
                }
                JsonNode tc = messageNode.path("tool_calls");
                if (tc.isArray() && tc.size() > 0) {
                    // Ollama emits tool_calls on a single frame (usually the
                    // last one). Re-parse on every frame that carries them
                    // so the final list reflects the last seen.
                    toolCalls = parseToolCalls(tc);
                }
            }
            if (root.path("done").asBoolean(false)) {
                sawDone = true;
                doneReason = root.path("done_reason").asText("");
                promptTokens = root.path("prompt_eval_count").asLong(promptTokens);
                outputTokens = root.path("eval_count").asLong(outputTokens);
            }
        }

        if (!sawDone) {
            throw new OllamaClientException(
                    "Ollama streaming ended without a done=true frame (truncated stream)", -1);
        }

        LlmStopReason stop = mapStopReason(doneReason, true, toolCalls);
        TokenUsage usage = new TokenUsage(promptTokens, outputTokens, CacheUsage.NONE, 0L, 0L);
        String requestId = "ollama-" + UUID.randomUUID().toString().substring(0, 8);
        return new LlmResponse(requestId, modelId, stop, text.toString(), toolCalls, List.of(), usage);
    }

    // ---------- HTTP helpers ----------

    private String serializeBody(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new OllamaClientException("Failed to serialize Ollama request: " + e.getMessage(), -1, e);
        }
    }

    private HttpRequest buildHttpRequest(String payload, String acceptHeader) {
        return HttpRequest.newBuilder()
                .uri(URI.create(config.chatEndpoint()))
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", acceptHeader)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> sendBlocking(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new OllamaClientException("Ollama transport failure: " + e.getMessage(), -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OllamaClientException("Ollama request interrupted", -1, e);
        }
    }

    // ---------- request mapping (LlmRequest → Ollama JSON) ----------

    /** Back-compat overload used by tests — defaults to non-streaming. */
    Map<String, Object> toOllamaBody(LlmRequest req) {
        return toOllamaBody(req, false);
    }

    Map<String, Object> toOllamaBody(LlmRequest req, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", buildMessages(req));
        body.put("stream", stream);

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
        // Track tool-call-id -> tool name from assistant messages so that
        // subsequent TOOL replies can be tagged with `tool_name`, which is
        // how Ollama's native /api/chat associates a tool result with the
        // call that produced it. Synthetic ids (tool-0, tool-1, ...) are
        // not meaningful to Ollama on their own.
        Map<String, String> toolNamesById = new LinkedHashMap<>();
        for (LlmMessage m : req.messages()) {
            if (m.role() == LlmRole.ASSISTANT) {
                for (LlmToolCall call : m.toolCalls()) {
                    toolNamesById.put(call.id(), call.toolName());
                }
            }
            out.add(toOllamaMessage(m, toolNamesById));
        }
        return out;
    }

    Map<String, Object> toOllamaMessage(LlmMessage m) {
        return toOllamaMessage(m, Map.of());
    }

    Map<String, Object> toOllamaMessage(LlmMessage m, Map<String, String> toolNamesById) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", roleOf(m.role()));
        // Ollama accepts "content" on every role; for tool-result messages
        // the convention is content = the tool output text.
        msg.put("content", m.content());
        if (m.role() == LlmRole.ASSISTANT && !m.toolCalls().isEmpty()) {
            msg.put("tool_calls", m.toolCalls().stream().map(OllamaLlmClient::toOllamaToolCall).toList());
        }
        if (m.role() == LlmRole.TOOL && m.toolUseId() != null) {
            // Ollama's native chat schema associates a tool result with its
            // call via `tool_name` (not the id). Include it whenever we can
            // resolve the name from a prior assistant tool_call; otherwise
            // newer Ollama builds will fall back to positional matching.
            String toolName = toolNamesById.get(m.toolUseId());
            if (toolName != null) {
                msg.put("tool_name", toolName);
            }
            // Kept for round-trip fidelity; newer Ollama versions accept it
            // but ignore it when `tool_name` is present.
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
