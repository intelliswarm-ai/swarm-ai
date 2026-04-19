package ai.intelliswarm.swarmai.tool.vision;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ImageGenerationTool — OpenAI DALL-E / GPT-Image image synthesis.
 *
 * <p>Calls {@code POST https://api.openai.com/v1/images/generations}. Supports DALL-E 3,
 * DALL-E 2, and gpt-image-1 (OpenAI's current frontier image model). Agents can request
 * a URL or base64 bytes; URLs are returned as-is, base64 is optionally saved to a file.
 *
 * <p>Required config: {@code OPENAI_API_KEY}.
 * Optional: {@code swarmai.tools.image.base-url} to point at OpenAI-compatible providers
 * (Azure OpenAI, self-hosted gateways).
 *
 * <p>Permission level is {@code WORKSPACE_WRITE} because image generation consumes paid
 * quota and writes files when {@code save_to} is set.
 */
@Component
public class ImageGenerationTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationTool.class);
    private static final String DEFAULT_BASE = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "dall-e-3";

    // Model-specific size catalog (OpenAI reference).
    private static final Set<String> DALLE3_SIZES = Set.of("1024x1024", "1792x1024", "1024x1792");
    private static final Set<String> DALLE2_SIZES = Set.of("256x256", "512x512", "1024x1024");
    private static final Set<String> GPT_IMAGE_SIZES = Set.of("1024x1024", "1024x1536", "1536x1024", "auto");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.image.api-key:}")
    private String apiKey = "";

    @Value("${swarmai.tools.image.base-url:}")
    private String baseUrl = "";

    public ImageGenerationTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    ImageGenerationTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "image_generate"; }

    @Override
    public String getDescription() {
        return "Generate images from a text prompt via OpenAI's Images API (DALL-E 3, DALL-E 2, " +
               "gpt-image-1). Supports size / quality / style / n. Returns URL or base64 bytes; " +
               "optionally saves the result to a local file via 'save_to'. Requires OPENAI_API_KEY.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String prompt = asString(parameters.get("prompt"));
        if (prompt == null || prompt.isBlank()) {
            return "Error: 'prompt' parameter is required.";
        }
        String key = pickKey(parameters);
        if (key == null) {
            return "Error: OpenAI API key not configured. Set OPENAI_API_KEY env var " +
                   "or swarmai.tools.image.api-key property.";
        }
        String model = asString(parameters.getOrDefault("model", DEFAULT_MODEL));
        String size = asString(parameters.getOrDefault("size", defaultSizeFor(model)));
        String sizeErr = validateSize(model, size);
        if (sizeErr != null) return "Error: " + sizeErr;

        String quality = asString(parameters.get("quality")); // dall-e-3 / gpt-image-1 only
        String style   = asString(parameters.get("style"));   // dall-e-3 only: vivid | natural
        String responseFormat = asString(parameters.getOrDefault("response_format", "url"));
        if (!List.of("url", "b64_json").contains(responseFormat)) {
            return "Error: 'response_format' must be 'url' or 'b64_json'.";
        }
        int n = parseInt(parameters, "n", 1, 1, 10);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("model", model);
        body.put("n", n);
        body.put("size", size);
        if (!"gpt-image-1".equals(model)) {
            // gpt-image-1 doesn't accept response_format; it always returns base64.
            body.put("response_format", responseFormat);
        }
        if (quality != null && !quality.isBlank()) body.put("quality", quality);
        if (style != null   && !style.isBlank())   body.put("style", style);

        String url = (isBlank(baseUrl) ? DEFAULT_BASE : stripTrailingSlash(baseUrl)) + "/images/generations";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        logger.info("ImageGenerationTool: model={} size={} n={} quality={} prompt[0:80]={}",
            model, size, n, quality, truncate(prompt, 80));

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                URI.create(url), HttpMethod.POST, new HttpEntity<>(body.toString(), headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return "Error: OpenAI returned HTTP " + response.getStatusCode().value();
            }
            return formatResponse(response.getBody(), parameters, model, responseFormat);
        } catch (HttpClientErrorException.Unauthorized e) {
            return "Error: OpenAI rejected the API key (401). Verify OPENAI_API_KEY.";
        } catch (HttpClientErrorException.TooManyRequests e) {
            return "Error: OpenAI rate-limited this request (429). Back off and retry.";
        } catch (HttpClientErrorException e) {
            return "Error: OpenAI " + e.getStatusCode().value() + " — " +
                   truncate(e.getResponseBodyAsString(), 500);
        } catch (RestClientException e) {
            logger.warn("ImageGenerationTool network error: {}", e.getMessage());
            return "Error: image generation request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("ImageGenerationTool unexpected error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    // ---------- formatting ----------

    private String formatResponse(String bodyStr, Map<String, Object> parameters, String model, String format) throws Exception {
        JsonNode body = objectMapper.readTree(bodyStr);
        JsonNode data = body.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return "Error: OpenAI returned no image data. Raw body: " + truncate(bodyStr, 500);
        }

        String saveTo = asString(parameters.get("save_to"));
        StringBuilder out = new StringBuilder();
        out.append("Generated ").append(data.size()).append(" image(s) with ").append(model).append(":\n\n");

        for (int i = 0; i < data.size(); i++) {
            JsonNode item = data.get(i);
            out.append(i + 1).append(". ");
            // gpt-image-1 always returns base64; everything else honors response_format.
            if (item.hasNonNull("b64_json") || "gpt-image-1".equals(model)) {
                String b64 = item.path("b64_json").asText();
                byte[] bytes = Base64.getDecoder().decode(b64);
                out.append("base64 bytes: ").append(bytes.length).append('\n');
                if (saveTo != null && !saveTo.isBlank()) {
                    Path p = resolveSavePath(saveTo, i, data.size());
                    Files.createDirectories(p.getParent() == null ? Paths.get(".") : p.getParent());
                    Files.write(p, bytes);
                    out.append("   saved to: ").append(p).append('\n');
                }
            } else {
                String imgUrl = item.path("url").asText();
                out.append(imgUrl).append('\n');
                if (saveTo != null && !saveTo.isBlank()) {
                    // Download the URL and persist locally.
                    Path p = resolveSavePath(saveTo, i, data.size());
                    Files.createDirectories(p.getParent() == null ? Paths.get(".") : p.getParent());
                    try (var in = new java.net.URL(imgUrl).openStream()) {
                        Files.copy(in, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    out.append("   saved to: ").append(p).append('\n');
                }
            }
            String revisedPrompt = item.path("revised_prompt").asText(null);
            if (revisedPrompt != null && !revisedPrompt.isBlank()) {
                out.append("   revised prompt: ").append(truncate(revisedPrompt, 300)).append('\n');
            }
        }
        return out.toString().trim();
    }

    private Path resolveSavePath(String saveTo, int index, int total) {
        Path p = Paths.get(saveTo);
        if (total <= 1) return p;
        // Multiple outputs → append -N before the extension.
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String newName = dot < 0
            ? name + "-" + (index + 1)
            : name.substring(0, dot) + "-" + (index + 1) + name.substring(dot);
        Path parent = p.getParent();
        return parent == null ? Paths.get(newName) : parent.resolve(newName);
    }

    // ---------- helpers ----------

    private String pickKey(Map<String, Object> parameters) {
        String explicit = asString(parameters.get("api_key"));
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        String env = System.getenv("OPENAI_API_KEY");
        return env != null && !env.isBlank() ? env : null;
    }

    private static String defaultSizeFor(String model) {
        return "dall-e-2".equals(model) ? "512x512" : "1024x1024";
    }

    private static String validateSize(String model, String size) {
        if (size == null || size.isBlank()) return "size is required.";
        Set<String> allowed = switch (model) {
            case "dall-e-3"    -> DALLE3_SIZES;
            case "dall-e-2"    -> DALLE2_SIZES;
            case "gpt-image-1" -> GPT_IMAGE_SIZES;
            default -> null;
        };
        if (allowed == null) return null; // Unknown model — let OpenAI validate.
        return allowed.contains(size) ? null
            : "size '" + size + "' not supported by " + model + ". Allowed: " + allowed;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
    private static int parseInt(Map<String, Object> parameters, String key, int def, int min, int max) {
        Object raw = parameters.get(key);
        if (raw == null) return def;
        try {
            int v = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) { return def; }
    }
    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n) + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        addStringProp(props, "prompt", "Text description of the desired image (required).");

        Map<String, Object> model = new HashMap<>();
        model.put("type", "string");
        model.put("enum", List.of("dall-e-3", "dall-e-2", "gpt-image-1"));
        model.put("description", "Which model to use. Default: 'dall-e-3'.");
        props.put("model", model);

        addStringProp(props, "size",     "Image size — model-specific (e.g. '1024x1024', '1792x1024').");
        addStringProp(props, "quality",  "'standard' or 'hd' (dall-e-3), 'low'/'medium'/'high' (gpt-image-1).");
        addStringProp(props, "style",    "'vivid' or 'natural' (dall-e-3 only).");
        addStringProp(props, "response_format", "'url' (default) or 'b64_json'. Ignored for gpt-image-1.");

        Map<String, Object> nProp = new HashMap<>();
        nProp.put("type", "integer");
        nProp.put("description", "How many images to produce (1–10). DALL-E 3 only supports n=1.");
        props.put("n", nProp);

        addStringProp(props, "save_to", "Optional file path to download/write the image(s) to. " +
                                         "If n>1, index '-1', '-2', ... is appended.");

        schema.put("properties", props);
        schema.put("required", new String[]{"prompt"});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; } // image gen consumes quota each call
    @Override public String getCategory() { return "vision"; }
    @Override public List<String> getTags() { return List.of("image-generation", "dall-e", "openai", "creative"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }

    @Override
    public String getTriggerWhen() {
        return "User asks to generate, create, or visualize an image from a text description " +
               "(illustration, cover art, mockup, logo concept, diagram sketch).";
    }

    @Override
    public String getAvoidWhen() {
        return "User wants to edit an existing image (masks, inpainting), transcribe text from an " +
               "image (use ocr), or produce photo-real people/celebrities — consult OpenAI's policy first.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("OPENAI_API_KEY").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Numbered list of generated image URLs or base64 payload descriptors, " +
                           "plus (if save_to set) local file paths.");
    }

    @Override
    public String smokeTest() {
        return pickKey(Map.of()) == null ? "OPENAI_API_KEY not configured" : null;
    }

    public record Request(String prompt, String model, String size, String quality, String style,
                          String response_format, Integer n, String save_to) {}
}
