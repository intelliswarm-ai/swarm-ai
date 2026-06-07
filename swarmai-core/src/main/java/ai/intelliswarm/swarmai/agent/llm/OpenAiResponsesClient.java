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
    private static final Set<Feature> SUPPORTED = EnumSet.of(Feature.STREAMING);

    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json;

    public OpenAiResponsesClient(String apiKey) {
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    @Override public String name() { return "openai-responses"; }
    @Override public Set<Feature> supportedFeatures() { return SUPPORTED; }

    @Override
    public LlmResponse send(LlmRequest req) {
        return sendStreaming(req, ignored -> {});
    }

    @Override
    public LlmResponse sendStreaming(LlmRequest req, Consumer<String> textDelta) {
        try {
            // Build request for v1/responses endpoint
            Map<String, Object> requestBody = buildRequestBody(req);
            String jsonBody = json.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("OpenAI v1/responses HTTP {}: {}",
                    response.statusCode(),
                    response.body().substring(0, Math.min(200, response.body().length())));
                return errorResponse(req.model(), "HTTP " + response.statusCode());
            }

            return parseResponsesEndpointResponse(response.body(), textDelta, req.model());

        } catch (Exception e) {
            log.warn("OpenAI responses client failed: {}", e.getMessage());
            return errorResponse(req.model(), e.getMessage());
        }
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
        return body;
    }

    private LlmResponse parseResponsesEndpointResponse(String responseBody, Consumer<String> textDelta, String modelId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(responseBody, Map.class);

            // v1/responses endpoint returns: { "output": [ { "type": "message", "content": [ { "type": "output_text", "text": "..." } ] } ] }
            String text = extractTextFromOutput(parsed);

            if (!text.isEmpty()) {
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

            return new LlmResponse(
                UUID.randomUUID().toString(),
                modelId,
                LlmStopReason.END_TURN,
                text,
                List.of(),
                List.of(),
                new TokenUsage(inputTokens, outputTokens, CacheUsage.NONE, 0, 0)
            );
        } catch (Exception e) {
            log.warn("Failed to parse responses endpoint response: {}", e.getMessage(), e);
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
        return new LlmResponse(
            UUID.randomUUID().toString(),
            modelId,
            LlmStopReason.END_TURN,
            "",
            List.of(),
            List.of(),
            new TokenUsage(0, 0, CacheUsage.NONE, 0, 0)
        );
    }
}
