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
 * Search OpenAlex — a free, fully open index of ~240M scholarly works.
 *
 * <p>Public endpoint, no API key. The "polite pool" gives priority routing when a
 * {@code mailto} query parameter is included; we honor an optional
 * {@code OPENALEX_MAILTO} environment variable for this.
 *
 * <p>See: <a href="https://docs.openalex.org/how-to-use-the-api/api-overview">docs.openalex.org</a>
 */
public class OpenAlexTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(OpenAlexTool.class);
    private static final String API = "https://api.openalex.org/works";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 25;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAlexTool() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OpenAlexTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override public String getFunctionName() { return "openalex_search"; }

    @Override
    public String getDescription() {
        return "Search OpenAlex (~240M scholarly works, all fields). Returns title, authors, year, "
             + "venue, OA PDF URL when available, DOI, and citation count. Strong default for "
             + "breadth across disciplines. Polite-pool routing if OPENALEX_MAILTO is set.";
    }

    @Override public String getCategory() { return "research"; }
    @Override public List<String> getTags() { return List.of("research", "academic", "papers", "openalex"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) return "Error: 'query' is required";
        int limit = clamp(asInt(parameters.get("limit"), DEFAULT_LIMIT), 1, MAX_LIMIT);

        StringBuilder url = new StringBuilder(API)
                .append("?search=").append(enc(query))
                .append("&per_page=").append(limit)
                .append("&select=id,title,publication_year,authorships,primary_location,open_access,doi,cited_by_count,abstract_inverted_index");
        String mailto = System.getenv("OPENALEX_MAILTO");
        if (mailto != null && !mailto.isBlank()) url.append("&mailto=").append(enc(mailto));

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "SwarmAI-Rag/1.0")
                    .GET().build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return "Error: OpenAlex returned HTTP " + resp.statusCode();
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "OpenAlex: no results for query '" + query + "'";
            }
            return format(query, results);
        } catch (Exception e) {
            logger.warn("OpenAlex search failed: {}", e.getMessage());
            return "Error: OpenAlex request failed — " + e.getMessage();
        }
    }

    private String format(String query, JsonNode results) {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenAlex results for '").append(query).append("':\n\n");
        int i = 1;
        for (JsonNode p : results) {
            String title = p.path("title").asText("(no title)");
            int year = p.path("publication_year").asInt(0);
            int citations = p.path("cited_by_count").asInt(0);
            String doi = p.path("doi").asText("");
            String oaId = p.path("id").asText("");
            String firstAuthor = "";
            JsonNode auths = p.path("authorships");
            if (auths.isArray() && !auths.isEmpty()) {
                firstAuthor = auths.get(0).path("author").path("display_name").asText("");
            }
            String venue = p.path("primary_location").path("source").path("display_name").asText("");
            String oaPdf = p.path("open_access").path("oa_url").asText("");
            String abstr = reconstructAbstract(p.path("abstract_inverted_index"));

            sb.append(i++).append(". **").append(title).append("**\n");
            if (!firstAuthor.isBlank()) sb.append("   First author: ").append(firstAuthor).append('\n');
            if (year > 0) sb.append("   Year: ").append(year).append('\n');
            if (!venue.isBlank()) sb.append("   Venue: ").append(venue).append('\n');
            if (citations > 0) sb.append("   Citations: ").append(citations).append('\n');
            if (!doi.isBlank()) sb.append("   DOI: ").append(doi).append('\n');
            if (!oaId.isBlank()) sb.append("   ID: ").append(oaId).append('\n');
            if (!oaPdf.isBlank()) sb.append("   PDF: ").append(oaPdf).append('\n');
            if (!abstr.isBlank()) sb.append("   Abstract: ").append(truncate(abstr, 500)).append('\n');
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** OpenAlex returns abstracts as an inverted index (word -> [positions]); reconstruct the original order. */
    private static String reconstructAbstract(JsonNode inverted) {
        if (inverted == null || !inverted.isObject() || inverted.isEmpty()) return "";
        // Find max position to size the array
        int max = 0;
        var fieldsIt = inverted.fields();
        while (fieldsIt.hasNext()) {
            JsonNode posArr = fieldsIt.next().getValue();
            if (posArr.isArray()) for (JsonNode p : posArr) max = Math.max(max, p.asInt(0));
        }
        String[] words = new String[max + 1];
        fieldsIt = inverted.fields();
        while (fieldsIt.hasNext()) {
            var e = fieldsIt.next();
            String word = e.getKey();
            JsonNode posArr = e.getValue();
            if (posArr.isArray()) for (JsonNode p : posArr) {
                int idx = p.asInt(-1);
                if (idx >= 0 && idx <= max) words[idx] = word;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
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
    @Override public boolean isDynamic() { return true; }
    @Override public String getTriggerWhen() { return "User wants broad cross-discipline academic coverage with DOIs and open-access PDFs."; }
    @Override public String getAvoidWhen() { return "User specifically wants arXiv preprints (use arxiv_search) or biomedical-only (use pubmed_search)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (Exception e) { return dflt; }
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n).trim() + "…"; }
}
