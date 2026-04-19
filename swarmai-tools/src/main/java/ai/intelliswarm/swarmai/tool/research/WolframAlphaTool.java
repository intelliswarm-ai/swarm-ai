package ai.intelliswarm.swarmai.tool.research;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wolfram Alpha Tool — computational knowledge engine for math, science, units, formulas.
 *
 * Two modes:
 *   - short:  Wolfram Short Answers API (v1/result)   — single-line answer, best for LLMs.
 *   - full:   Wolfram Full Results API (v2/query)     — JSON with step-by-step pods.
 *
 * Requires a free WOLFRAM_APPID from https://developer.wolframalpha.com/.
 */
@Component
public class WolframAlphaTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WolframAlphaTool.class);
    private static final String SHORT_URL = "https://api.wolframalpha.com/v1/result";
    private static final String FULL_URL  = "https://api.wolframalpha.com/v2/query";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.wolfram.app-id:}")
    private String appId = "";

    public WolframAlphaTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    WolframAlphaTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "wolfram_alpha"; }

    @Override
    public String getDescription() {
        return "Answer math, science, engineering, unit-conversion, and general knowledge questions via " +
               "Wolfram Alpha's computational engine. Use mode='short' (default) for one-line answers, " +
               "or mode='full' for structured step-by-step results.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String input = asString(parameters.get("input"));
        if (input == null || input.isBlank()) {
            return "Error: 'input' parameter is required (the question or expression to solve).";
        }
        String effectiveAppId = pickAppId(parameters);
        if (effectiveAppId == null || effectiveAppId.isBlank()) {
            return "Error: Wolfram Alpha app ID not configured. Set WOLFRAM_APPID env var or " +
                   "swarmai.tools.wolfram.app-id property.";
        }
        String mode = asString(parameters.getOrDefault("mode", "short")).toLowerCase();
        logger.info("WolframAlphaTool: mode={} input='{}'", mode, truncate(input, 100));

        try {
            return switch (mode) {
                case "short" -> queryShort(effectiveAppId, input);
                case "full"  -> queryFull(effectiveAppId, input);
                default -> "Error: unknown mode '" + mode + "'. Use 'short' or 'full'.";
            };
        } catch (HttpClientErrorException e) {
            // Short-answers API returns 501 when it can't interpret the input.
            if (e.getStatusCode().value() == 501) {
                return "Wolfram Alpha could not interpret the input. Try rephrasing (e.g. 'integrate x^2' " +
                       "instead of 'antiderivative of x squared').";
            }
            return "Error: Wolfram Alpha returned HTTP " + e.getStatusCode().value() + " — " + e.getMessage();
        } catch (RestClientException e) {
            logger.warn("WolframAlphaTool network error: {}", e.getMessage());
            return "Error: Wolfram Alpha request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("WolframAlphaTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String queryShort(String id, String input) {
        URI uri = URI.create(SHORT_URL + "?appid=" + enc(id) + "&i=" + enc(input));
        // The short-answers endpoint returns 200 with a plain-text body on success.
        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(acceptText()), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Wolfram Alpha returned HTTP " + response.getStatusCode().value();
        }
        return response.getBody().trim();
    }

    private String queryFull(String id, String input) {
        URI uri = URI.create(FULL_URL + "?appid=" + enc(id) + "&input=" + enc(input) + "&output=json");
        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(acceptJson()), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Wolfram Alpha returned HTTP " + response.getStatusCode().value();
        }

        try {
            JsonNode result = objectMapper.readTree(response.getBody()).path("queryresult");
            if (!result.path("success").asBoolean(false)) {
                return "No result for '" + input + "'. Wolfram Alpha could not interpret the query.";
            }
            StringBuilder out = new StringBuilder();
            out.append("**Wolfram Alpha** — `").append(input).append("`\n\n");
            JsonNode pods = result.path("pods");
            if (pods.isArray()) {
                for (JsonNode pod : pods) {
                    String title = pod.path("title").asText("");
                    if (title.isBlank()) continue;
                    out.append("**").append(title).append("**\n");
                    JsonNode subpods = pod.path("subpods");
                    if (subpods.isArray()) {
                        for (JsonNode sp : subpods) {
                            String text = sp.path("plaintext").asText("");
                            if (!text.isBlank()) {
                                out.append(text).append('\n');
                            }
                        }
                    }
                    out.append('\n');
                }
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "Error: failed to parse Wolfram response — " + e.getMessage();
        }
    }

    // ---------- helpers ----------

    private String pickAppId(Map<String, Object> parameters) {
        String explicit = asString(parameters.get("app_id"));
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (appId != null && !appId.isBlank()) return appId;
        String env = System.getenv("WOLFRAM_APPID");
        return env != null && !env.isBlank() ? env : null;
    }

    private HttpHeaders acceptText() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.ACCEPT, "text/plain");
        return h;
    }

    private HttpHeaders acceptJson() {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.ACCEPT, "application/json");
        return h;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> input = new HashMap<>();
        input.put("type", "string");
        input.put("description", "Natural-language or symbolic input (e.g. 'integrate x^2 dx', 'mass of Jupiter in kg').");
        props.put("input", input);

        Map<String, Object> mode = new HashMap<>();
        mode.put("type", "string");
        mode.put("enum", List.of("short", "full"));
        mode.put("description", "Response style. 'short' for one-line answers (default), 'full' for structured pods.");
        props.put("mode", mode);

        schema.put("properties", props);
        schema.put("required", new String[]{"input"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return true; }
    @Override public String getCategory() { return "research"; }
    @Override public List<String> getTags() { return List.of("math", "science", "knowledge", "computation"); }

    @Override
    public String getTriggerWhen() {
        return "User asks a math problem (calculus, algebra, statistics), physical-science question " +
               "(planetary data, material properties), unit conversion, formula lookup, or factual query " +
               "with a computable answer.";
    }

    @Override
    public String getAvoidWhen() {
        return "User wants a narrative explanation, opinion, real-time data, or content specific to a website " +
               "or private dataset. Prefer web_search or wikipedia for those.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("WOLFRAM_APPID").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Short single-line answer, or markdown-formatted structured pods for mode='full'."
        );
    }

    @Override
    public String smokeTest() {
        String id = pickAppId(Map.of());
        if (id == null || id.isBlank()) return "WOLFRAM_APPID not configured";
        try {
            String result = queryShort(id, "2+2");
            return result != null && result.startsWith("Error") ? "Wolfram Alpha unreachable: " + result : null;
        } catch (Exception e) {
            return "Wolfram Alpha unreachable: " + e.getMessage();
        }
    }

    public record Request(String input, String mode) {}
}
