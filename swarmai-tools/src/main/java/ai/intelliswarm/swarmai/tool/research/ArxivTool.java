package ai.intelliswarm.swarmai.tool.research;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * arXiv Tool — search the arXiv preprint server for papers.
 *
 * Uses the public arXiv API (https://export.arxiv.org/api/query). No API key.
 * Two operations:
 *   - search : keyword / field-scoped search returning ranked papers
 *   - get    : fetch a single paper by arXiv ID (e.g. "2401.12345" or "cs.AI/0001001")
 */
@Component
public class ArxivTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ArxivTool.class);
    private static final String API_BASE = "https://export.arxiv.org/api/query";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 30;

    private final RestTemplate restTemplate;

    public ArxivTool() {
        this(new RestTemplate());
    }

    ArxivTool(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override public String getFunctionName() { return "arxiv_search"; }

    @Override
    public String getDescription() {
        return "Search arXiv for scientific preprints (CS, physics, math, biology, etc.). Use operation='search' " +
               "with a keyword query, or operation='get' with an arXiv ID to fetch one paper. Returns titles, " +
               "authors, abstracts, and PDF links. No API key required.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String operation = asString(parameters.getOrDefault("operation", "search")).toLowerCase();
        try {
            return switch (operation) {
                case "search" -> search(parameters);
                case "get"    -> getById(parameters);
                default -> "Error: unknown operation '" + operation + "'. Use 'search' or 'get'.";
            };
        } catch (RestClientException e) {
            logger.warn("ArxivTool network error: {}", e.getMessage());
            return "Error: arXiv request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("ArxivTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String search(Map<String, Object> parameters) {
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) {
            return "Error: 'query' parameter is required for operation='search'.";
        }
        int limit = parseLimit(parameters);
        String sortBy = asString(parameters.getOrDefault("sort_by", "relevance")).toLowerCase();
        if (!List.of("relevance", "lastUpdatedDate", "submittedDate").contains(sortBy)) {
            sortBy = "relevance";
        }

        URI uri = URI.create(API_BASE
            + "?search_query=" + enc("all:" + query)
            + "&start=0"
            + "&max_results=" + limit
            + "&sortBy=" + sortBy
            + "&sortOrder=descending");

        logger.info("ArxivTool search: query='{}' limit={} sortBy={}", query, limit, sortBy);
        String body = fetch(uri);
        if (body == null) return "Error: empty response from arXiv.";
        return formatFeed(body, "arXiv search results for '" + query + "'");
    }

    private String getById(Map<String, Object> parameters) {
        String id = asString(parameters.get("id"));
        if (id == null || id.isBlank()) {
            return "Error: 'id' parameter is required for operation='get' (e.g. '2401.12345').";
        }
        URI uri = URI.create(API_BASE + "?id_list=" + enc(id));
        logger.info("ArxivTool get: id={}", id);
        String body = fetch(uri);
        if (body == null) return "Error: empty response from arXiv.";
        return formatFeed(body, "arXiv paper " + id);
    }

    private String fetch(URI uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/atom+xml");
        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            logger.warn("ArxivTool non-2xx: {}", response.getStatusCode().value());
            return null;
        }
        return response.getBody();
    }

    // ---------- formatting ----------

    private String formatFeed(String atomXml, String header) {
        Document doc = Jsoup.parse(atomXml, "", Parser.xmlParser());
        Elements entries = doc.select("entry");
        if (entries.isEmpty()) {
            return header + ": no papers found.";
        }

        StringBuilder out = new StringBuilder();
        out.append(header).append(":\n\n");
        int i = 1;
        for (Element entry : entries) {
            String title = cleanText(entry.selectFirst("title"));
            String summary = cleanText(entry.selectFirst("summary"));
            String published = cleanText(entry.selectFirst("published"));
            String id = cleanText(entry.selectFirst("id"));
            List<String> authors = entry.select("author > name").eachText();
            String pdf = entry.select("link[title=pdf]").attr("href");

            out.append(i++).append(". **").append(title).append("**\n");
            if (!authors.isEmpty()) {
                out.append("   Authors: ").append(String.join(", ", authors)).append('\n');
            }
            if (!published.isBlank()) {
                out.append("   Published: ").append(published, 0, Math.min(10, published.length())).append('\n');
            }
            if (!id.isBlank())   out.append("   Link: ").append(id).append('\n');
            if (!pdf.isBlank())  out.append("   PDF:  ").append(pdf).append('\n');
            if (!summary.isBlank()) {
                out.append("   Abstract: ").append(truncate(summary, 500)).append("\n");
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    private int parseLimit(Map<String, Object> parameters) {
        Object raw = parameters.get("limit");
        if (raw == null) return DEFAULT_LIMIT;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue()
                                          : Integer.parseInt(raw.toString().trim());
            return Math.max(1, Math.min(MAX_LIMIT, n));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private static String cleanText(Element el) {
        return el == null ? "" : el.text().replaceAll("\\s+", " ").trim();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n).trim() + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("search", "get"));
        operation.put("description", "'search' for a query, 'get' for a single arXiv ID. Default: 'search'.");
        props.put("operation", operation);

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "Keywords for operation='search' (e.g. 'multi-agent reinforcement learning').");
        props.put("query", query);

        Map<String, Object> id = new HashMap<>();
        id.put("type", "string");
        id.put("description", "arXiv ID for operation='get' (e.g. '2401.12345').");
        props.put("id", id);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "Max results for 'search' (1–" + MAX_LIMIT + "). Default: " + DEFAULT_LIMIT + ".");
        props.put("limit", limit);

        Map<String, Object> sortBy = new HashMap<>();
        sortBy.put("type", "string");
        sortBy.put("enum", List.of("relevance", "lastUpdatedDate", "submittedDate"));
        sortBy.put("description", "Sort order for 'search'. Default: 'relevance'.");
        props.put("sort_by", sortBy);

        schema.put("properties", props);
        // No universal 'required' — 'query' is required for search, 'id' for get; tool enforces inline.
        schema.put("required", new String[]{});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return true; }
    @Override public String getCategory() { return "research"; }
    @Override public List<String> getTags() { return List.of("arxiv", "research", "academic", "papers", "science"); }

    @Override
    public String getTriggerWhen() {
        return "User asks for scientific papers, academic preprints, recent research in a field, " +
               "or wants to find a specific arXiv paper by ID.";
    }

    @Override
    public String getAvoidWhen() {
        return "User wants general web content, news articles, or non-academic sources — use web_search or wikipedia instead.";
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Numbered list of papers with title, authors, date, abstract, and PDF link."
        );
    }

    @Override
    public String smokeTest() {
        try {
            Object result = execute(Map.of("operation", "search", "query", "deep learning", "limit", 1));
            String s = result == null ? "" : result.toString();
            return s.startsWith("Error") ? "arXiv API unreachable: " + s : null;
        } catch (Exception e) {
            return "arXiv API unreachable: " + e.getMessage();
        }
    }

    public record Request(String operation, String query, String id, Integer limit, String sort_by) {}
}
