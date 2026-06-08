package ai.intelliswarm.swarmai.agent.llm;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Custom OpenAI client for models that use the v1/responses endpoint
 * (e.g., gpt-5.1-codex-mini). This bypasses Spring AI's ChatModel
 * abstraction to support endpoints that aren't v1/chat/completions.
 */
@PublicApi(since = "1.0.28")
public class OpenAiResponsesClient implements StreamingLlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesClient.class);
    private static final Set<Feature> SUPPORTED = EnumSet.of(Feature.ONE_SHOT);

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiResponsesClient(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    @Override public String name() { return "openai-responses"; }
    @Override public Set<Feature> supportedFeatures() { return SUPPORTED; }

    @Override
    public LlmResponse send(LlmRequest req) {
        return sendStreaming(req, ignored -> {});
    }

    /** Transient HTTP statuses worth retrying with backoff (rate-limit / server errors). */
    private static final int MAX_HTTP_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MS = 2_000;

    @Override
    public LlmResponse sendStreaming(LlmRequest req, Consumer<String> textDelta) {
        try {
            Map<String, Object> requestBody = buildRequestBody(req);
            String jsonBody = json.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(300))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            for (int attempt = 1; attempt <= MAX_HTTP_ATTEMPTS; attempt++) {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code == 200) {
                    return parseResponsesEndpointResponse(response.body(), textDelta, req.model());
                }

                String body = response.body() == null ? "" : response.body();
                String snippet = body.substring(0, Math.min(200, body.length()));
                boolean quota = body.toLowerCase().contains("quota")
                        || body.toLowerCase().contains("billing");
                boolean transientErr = !quota && (code == 429 || code == 500 || code == 502 || code == 503);

                if (transientErr && attempt < MAX_HTTP_ATTEMPTS) {
                    long backoff = retryAfterMs(response).orElse(BASE_BACKOFF_MS * (1L << (attempt - 1)));
                    log.warn("OpenAI v1/responses HTTP {} (attempt {}/{}) — retrying in {}ms: {}",
                            code, attempt, MAX_HTTP_ATTEMPTS, backoff, snippet);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                // Non-retryable (quota/billing, auth, bad request) or retries exhausted.
                log.warn("OpenAI v1/responses HTTP {} — {}: {}", code,
                        quota ? "quota/billing exhausted (not retrying)" : "giving up", snippet);
                return errorResponse(req.model(), "HTTP " + code + (quota ? " (quota)" : ""));
            }
            return errorResponse(req.model(), "HTTP retries exhausted");

        } catch (Exception e) {
            log.warn("OpenAI responses client failed: {}", e.getMessage());
            return errorResponse(req.model(), e.getMessage());
        }
    }

    /** Honour a Retry-After header (seconds) when the API supplies one. */
    private java.util.Optional<Long> retryAfterMs(HttpResponse<String> resp) {
        return resp.headers().firstValue("retry-after").flatMap(v -> {
            try { return java.util.Optional.of((long) (Double.parseDouble(v.trim()) * 1000)); }
            catch (NumberFormatException e) { return java.util.Optional.empty(); }
        });
    }

    private Map<String, Object> buildRequestBody(LlmRequest req) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", req.model());
        body.put("max_output_tokens", req.maxOutputTokens());  // v1/responses uses max_output_tokens, not max_completion_tokens
        if (req.temperature() != null) {
            body.put("temperature", req.temperature());
        }

        // v1/responses endpoint expects a single 'input' string, not a messages array
        // For now, concatenate system + last user message as the input
        // This is a simplification since v1/responses is primarily designed for single-turn interactions
        StringBuilder input = new StringBuilder();

        if (req.system() != null && !req.system().isBlank()) {
            input.append(req.system()).append("\n\n");
        }

        // Find the last user message to use as input
        String userInput = "";
        for (LlmMessage m : req.messages()) {
            if (m.role() == LlmRole.USER) {
                userInput = m.content();
            }
        }
        input.append(userInput);

        body.put("input", input.toString());

        // v1/responses uses "instructions" as the system prompt equivalent
        if (req.system() != null && !req.system().isBlank()) {
            body.put("instructions", req.system());
        }

        return body;
    }

    private LlmResponse parseResponsesEndpointResponse(String responseBody, Consumer<String> textDelta, String modelId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(responseBody, Map.class);

            // Log status — "incomplete" means max_output_tokens was hit mid-generation
            String status = (String) parsed.getOrDefault("status", "unknown");
            if ("incomplete".equals(status)) {
                Object incomplete = parsed.get("incomplete_details");
                log.warn("v1/responses returned status=incomplete: {} — increase max_output_tokens", incomplete);
            }

            // v1/responses endpoint returns: { "output": [ { "type": "message", "content": [ { "type": "output_text", "text": "..." } ] } ] }
            String text = extractTextFromOutput(parsed);

            if (text.isEmpty()) {
                // Log a snippet of the raw body to help diagnose parsing failures
                log.warn("No text extracted from v1/responses. Status={}, keys={}, body_head={}",
                    status, parsed.keySet(),
                    responseBody.substring(0, Math.min(300, responseBody.length())));
            } else {
                textDelta.accept(text);
            }

            // Extract token usage if available
            long inputTokens = 0, outputTokens = 0;
            if (parsed.containsKey("usage")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> usage = (Map<String, Object>) parsed.get("usage");
                if (usage != null) {
                    Object inTokens = usage.get("input_tokens");
                    Object outTokens = usage.get("output_tokens");
                    if (inTokens instanceof Number) inputTokens = ((Number) inTokens).longValue();
                    if (outTokens instanceof Number) outputTokens = ((Number) outTokens).longValue();
                }
            }

            // Signal truncation as MAX_TOKENS so callers bump the budget and retry,
            // rather than treating a cut-off generation as a generic empty completion.
            LlmStopReason stop = "incomplete".equals(status)
                    ? LlmStopReason.MAX_TOKENS
                    : LlmStopReason.END_TURN;

            return new LlmResponse(
                UUID.randomUUID().toString(),
                modelId,
                stop,
                text,
                List.of(),
                List.of(),
                new TokenUsage(inputTokens, outputTokens, CacheUsage.NONE, 0, 0)
            );
        } catch (Exception e) {
            log.warn("Failed to parse responses endpoint response: {} — body_head: {}",
                e.getMessage(), responseBody.substring(0, Math.min(300, responseBody.length())));
            return errorResponse(modelId, e.getMessage());
        }
    }

    private String extractTextFromOutput(Map<String, Object> response) {
        if (!response.containsKey("output")) return "";

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) response.get("output");
        if (output == null || output.isEmpty()) return "";

        // Find the message object in the output array
        for (Map<String, Object> item : output) {
            String type = (String) item.get("type");
            if ("message".equals(type)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) item.get("content");
                if (content != null && !content.isEmpty()) {
                    // Extract text from content blocks
                    StringBuilder text = new StringBuilder();
                    for (Map<String, Object> block : content) {
                        String blockType = (String) block.get("type");
                        if ("output_text".equals(blockType)) {
                            Object blockText = block.get("text");
                            if (blockText != null) {
                                if (text.length() > 0) text.append("\n");
                                text.append(blockText.toString());
                            }
                        }
                    }
                    return text.toString();
                }
            }
        }
        return "";
    }

    private LlmResponse errorResponse(String modelId, String error) {
        // UNKNOWN stop reason marks an API/transport error (vs. END_TURN for a real,
        // possibly-empty completion, or MAX_TOKENS for truncation). Lets callers like
        // OneShotEngine fail fast instead of misreading a 429/quota as truncation.
        return new LlmResponse(
            UUID.randomUUID().toString(),
            modelId,
            LlmStopReason.UNKNOWN,
            "",
            List.of(),
            List.of(),
            new TokenUsage(0, 0, CacheUsage.NONE, 0, 0)
        );
    }
}
