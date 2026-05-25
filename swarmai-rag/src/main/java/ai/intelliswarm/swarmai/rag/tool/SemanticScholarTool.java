package ai.intelliswarm.swarmai.rag.tool;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search the Semantic Scholar Graph API for papers.
 *
 * <p>Public endpoint, no API key required (rate-limited). Returns title, authors, year,
 * abstract, openAccessPdf URL when available, and a Semantic Scholar paper ID.
 *
 * <p>See: <a href="https://api.semanticscholar.org/api-docs/">api.semanticscholar.org/api-docs/</a>
 */
public class SemanticScholarTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(SemanticScholarTool.class);
    private static final String API = "https://api.semanticscholar.org/graph/v1/paper/search";
    private static final String FIELDS = "title,authors,year,abstract,openAccessPdf,externalIds,venue,citationCount";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SemanticScholarTool() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    SemanticScholarTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override public String getFunctionName() { return "semantic_scholar_search"; }

    @Override
    public String getDescription() {
        return "Search Semantic Scholar for papers across all fields (CS, life sciences, social, etc.). "
             + "Returns title, authors, year, abstract, an openAccessPdf URL when available, and the paper ID. "
             + "Use when arXiv coverage is insufficient or for cross-field literature.";
    }

    @Override public String getCategory() { return "research"; }
    @Override public List<String> getTags() { return List.of("research", "academic", "papers", "semantic-scholar"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) return "Error: 'query' is required";
        int limit = clamp(asInt(parameters.get("limit"), DEFAULT_LIMIT), 1, MAX_LIMIT);

        String url = API + "?query=" + enc(query) + "&limit=" + limit + "&fields=" + enc(FIELDS);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "SwarmAI-Rag/1.0")
                    .GET().build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429) {
                return "Error: Semantic Scholar rate limit exceeded (HTTP 429). Retry later or request an API key.";
            }
            if (resp.statusCode() / 100 != 2) {
                return "Error: Semantic Scholar returned HTTP " + resp.statusCode();
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return "Semantic Scholar: no results for query '" + query + "'";
            }
            return format(query, data);
        } catch (Exception e) {
            logger.warn("SemanticScholar search failed: {}", e.getMessage());
            return "Error: Semantic Scholar request failed — " + e.getMessage();
        }
    }

    private String format(String query, JsonNode data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Semantic Scholar results for '").append(query).append("':\n\n");
        int i = 1;
        for (JsonNode p : data) {
            String title = p.path("title").asText("(no title)");
            int year = p.path("year").asInt(0);
            int citations = p.path("citationCount").asInt(0);
            String venue = p.path("venue").asText("");
            String paperId = p.path("paperId").asText("");
            String pdf = p.path("openAccessPdf").path("url").asText("");
            String abstr = p.path("abstract").asText("");
            String firstAuthor = "";
            JsonNode authors = p.path("authors");
            if (authors.isArray() && !authors.isEmpty()) firstAuthor = authors.get(0).path("name").asText("");

            sb.append(i++).append(". **").append(title).append("**\n");
            if (!firstAuthor.isBlank()) sb.append("   First author: ").append(firstAuthor).append('\n');
            if (year > 0) sb.append("   Year: ").append(year).append('\n');
            if (!venue.isBlank()) sb.append("   Venue: ").append(venue).append('\n');
            if (citations > 0) sb.append("   Citations: ").append(citations).append('\n');
            if (!paperId.isBlank()) sb.append("   ID: semanticscholar:").append(paperId).append('\n');
            if (!pdf.isBlank()) sb.append("   PDF: ").append(pdf).append('\n');
            if (!abstr.isBlank()) sb.append("   Abstract: ").append(truncate(abstr, 500)).append('\n');
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "Keyword query"));
        props.put("limit", Map.of("type", "integer", "description", "Max results (1.." + MAX_LIMIT + ")"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return true; }
    /** Registered as a Spring @Bean but with a different bean name than function name —
     *  Spring AI needs us to register as FunctionToolCallback at agent build time. */
    @Override public boolean isDynamic() { return true; }
    @Override public String getTriggerWhen() { return "User wants academic papers across fields broader than arXiv (e.g., social science, biology, medicine)."; }
    @Override public String getAvoidWhen() { return "User wants only CS/ML preprints (arxiv is faster) or generic web (use web_search)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (Exception e) { return dflt; }
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n).trim() + "…"; }
}
