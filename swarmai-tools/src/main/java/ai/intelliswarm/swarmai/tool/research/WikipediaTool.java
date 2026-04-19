package ai.intelliswarm.swarmai.tool.research;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wikipedia Tool — factual grounding via the public Wikipedia REST API.
 *
 * No API key required. Supports three operations:
 *   - summary : short abstract + thumbnail for a given page title
 *   - search  : ranked list of page titles matching a query
 *   - page    : plain-text body of a full article (stripped HTML)
 */
@Component
public class WikipediaTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WikipediaTool.class);

    private static final String DEFAULT_LANGUAGE = "en";
    private static final int DEFAULT_SEARCH_LIMIT = 5;
    private static final int MAX_SEARCH_LIMIT = 20;
    private static final int MAX_PAGE_CHARS = 7000; // leave headroom under BaseTool's 8000 cap

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Field initializer is the fallback when Spring isn't bootstrapping this bean
    // (e.g. unit tests that call `new WikipediaTool(restTemplate, mapper)` directly).
    // Spring's @Value will overwrite this at container startup.
    @Value("${swarmai.tools.wikipedia.user-agent:SwarmAI/1.0 (https://intelliswarm.ai; contact@intelliswarm.ai)}")
    private String userAgent = "SwarmAI/1.0 (https://intelliswarm.ai; contact@intelliswarm.ai)";

    public WikipediaTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    // Package-private for tests; lets unit tests inject a mocked RestTemplate.
    WikipediaTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getFunctionName() {
        return "wikipedia";
    }

    @Override
    public String getDescription() {
        return "Look up factual information on Wikipedia. Supports 'summary' (short abstract for a page title), " +
               "'search' (ranked titles matching a query), and 'page' (full article body as plain text). " +
               "Works in any Wikipedia language edition (default: en). No API key required.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) {
            return "Error: 'query' parameter is required.";
        }
        String operation = asString(parameters.getOrDefault("operation", "summary")).toLowerCase();
        String language = asString(parameters.getOrDefault("language", DEFAULT_LANGUAGE)).toLowerCase();
        if (!language.matches("[a-z]{2,10}")) {
            return "Error: 'language' must be a short Wikipedia language code (e.g. 'en', 'de', 'zh').";
        }

        logger.info("WikipediaTool: op={} lang={} query='{}'", operation, language, query);

        try {
            return switch (operation) {
                case "summary" -> fetchSummary(language, query);
                case "search"  -> search(language, query, parseLimit(parameters));
                case "page"    -> fetchPage(language, query);
                default -> "Error: unknown operation '" + operation + "'. Use 'summary', 'search', or 'page'.";
            };
        } catch (RestClientException e) {
            logger.warn("WikipediaTool network error: {}", e.getMessage());
            return "Error: Wikipedia request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("WikipediaTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String fetchSummary(String lang, String title) {
        String url = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
                     + encodePathSegment(title);
        ResponseEntity<String> response = restTemplate.exchange(
            URI.create(url), HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: no summary found for '" + title + "' (HTTP " + response.getStatusCode().value() + ").";
        }

        try {
            JsonNode node = objectMapper.readTree(response.getBody());
            if (node.has("type") && "disambiguation".equals(node.path("type").asText())) {
                return "Disambiguation page for '" + title + "'. Try a more specific title or use operation='search'.";
            }
            StringBuilder out = new StringBuilder();
            out.append("**").append(node.path("title").asText(title)).append("**\n");
            if (node.hasNonNull("description")) {
                out.append("_").append(node.path("description").asText()).append("_\n\n");
            }
            out.append(node.path("extract").asText("(no summary available)")).append('\n');
            if (node.path("content_urls").path("desktop").hasNonNull("page")) {
                out.append("\nSource: ").append(node.path("content_urls").path("desktop").path("page").asText());
            }
            return out.toString();
        } catch (Exception e) {
            return "Error: failed to parse summary response — " + e.getMessage();
        }
    }

    private String search(String lang, String query, int limit) {
        String url = "https://" + lang + ".wikipedia.org/w/api.php"
                     + "?action=query&format=json&list=search"
                     + "&srlimit=" + limit
                     + "&srsearch=" + encodeQueryParam(query);
        ResponseEntity<String> response = restTemplate.exchange(
            URI.create(url), HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Wikipedia search failed (HTTP " + response.getStatusCode().value() + ").";
        }

        try {
            JsonNode results = objectMapper.readTree(response.getBody())
                                           .path("query").path("search");
            if (!results.isArray() || results.isEmpty()) {
                return "No Wikipedia results for '" + query + "'.";
            }
            StringBuilder out = new StringBuilder();
            out.append("Wikipedia search results for '").append(query).append("':\n\n");
            int i = 1;
            for (JsonNode hit : results) {
                out.append(i++).append(". **").append(hit.path("title").asText()).append("**\n");
                String snippet = stripHtml(hit.path("snippet").asText(""));
                if (!snippet.isBlank()) {
                    out.append("   ").append(snippet).append('\n');
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "Error: failed to parse search response — " + e.getMessage();
        }
    }

    private String fetchPage(String lang, String title) {
        String url = "https://" + lang + ".wikipedia.org/api/rest_v1/page/html/"
                     + encodePathSegment(title);
        ResponseEntity<String> response = restTemplate.exchange(
            URI.create(url), HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: no page found for '" + title + "' (HTTP " + response.getStatusCode().value() + ").";
        }

        String text = Jsoup.parse(response.getBody()).text();
        if (text.length() > MAX_PAGE_CHARS) {
            text = text.substring(0, MAX_PAGE_CHARS) + "\n\n… (truncated; full page at https://"
                   + lang + ".wikipedia.org/wiki/" + encodePathSegment(title) + ")";
        }
        return "**" + title + "**\n\n" + text;
    }

    // ---------- helpers ----------

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/html");
        return headers;
    }

    private int parseLimit(Map<String, Object> parameters) {
        Object raw = parameters.get("limit");
        if (raw == null) return DEFAULT_SEARCH_LIMIT;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue()
                                          : Integer.parseInt(raw.toString().trim());
            return Math.max(1, Math.min(MAX_SEARCH_LIMIT, n));
        } catch (NumberFormatException e) {
            return DEFAULT_SEARCH_LIMIT;
        }
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static String encodePathSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQueryParam(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String stripHtml(String html) {
        return html == null ? "" : Jsoup.parse(html).text();
    }

    // ---------- BaseTool metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "Page title (for 'summary'/'page') or search phrase (for 'search').");
        properties.put("query", query);

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("summary", "search", "page"));
        operation.put("description", "Which API to call. Default: 'summary'.");
        properties.put("operation", operation);

        Map<String, Object> language = new HashMap<>();
        language.put("type", "string");
        language.put("description", "Wikipedia language code (e.g. 'en', 'de'). Default: 'en'.");
        properties.put("language", language);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "Max results for 'search' (1–" + MAX_SEARCH_LIMIT + "). Default: " + DEFAULT_SEARCH_LIMIT + ".");
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});
        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public boolean isCacheable() {
        return true; // Wikipedia results are stable within a run
    }

    @Override
    public String getCategory() {
        return "research";
    }

    @Override
    public List<String> getTags() {
        return List.of("wikipedia", "research", "knowledge", "factual");
    }

    @Override
    public String getTriggerWhen() {
        return "User asks for factual background, definitions, biographies, historical context, " +
               "or needs grounding on a named entity, place, event, or scientific concept.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks for real-time data (news, prices, weather), opinions, or content that changes " +
               "hourly — use web_search or domain-specific tools instead.";
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Markdown-formatted article summary, search result list, or plain-text page body."
        );
    }

    @Override
    public String smokeTest() {
        try {
            String probe = fetchSummary(DEFAULT_LANGUAGE, "Wikipedia");
            return probe == null || probe.startsWith("Error") ? "Wikipedia API unreachable" : null;
        } catch (Exception e) {
            return "Wikipedia API unreachable: " + e.getMessage();
        }
    }

    // Request record for Spring AI function binding
    public record Request(String query, String operation, String language, Integer limit) {}
}
