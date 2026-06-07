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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Search PubMed via NCBI E-utilities — biomedical literature (~36M citations).
 *
 * <p>Two-step API: {@code esearch} returns PMIDs, {@code esummary} returns metadata.
 * No API key required for low-volume use (default rate limit: 3 req/sec).
 * Optionally set {@code NCBI_API_KEY} env var to lift the limit to 10 req/sec.
 *
 * <p>See: <a href="https://www.ncbi.nlm.nih.gov/books/NBK25500/">NCBI E-utilities docs</a>
 */
public class PubMedTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(PubMedTool.class);
    private static final String ESEARCH = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi";
    private static final String ESUMMARY = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi";
    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 25;

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PubMedTool() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    PubMedTool(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override public String getFunctionName() { return "pubmed_search"; }

    @Override
    public String getDescription() {
        return "Search PubMed (NCBI biomedical literature). Returns title, authors, journal, year, "
             + "PMID, and DOI. Use for medical, clinical, biological, or pharmacological hypotheses. "
             + "Set NCBI_API_KEY env var to lift the public rate limit.";
    }

    @Override public String getCategory() { return "research"; }
    @Override public List<String> getTags() { return List.of("research", "academic", "papers", "pubmed", "biomedical"); }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = asString(parameters.get("query"));
        if (query == null || query.isBlank()) return "Error: 'query' is required";
        int limit = clamp(asInt(parameters.get("limit"), DEFAULT_LIMIT), 1, MAX_LIMIT);

        try {
            // 1) esearch -> list of PMIDs
            List<String> pmids = esearch(query, limit);
            if (pmids.isEmpty()) return "PubMed: no results for query '" + query + "'";

            // 2) esummary -> metadata per PMID
            JsonNode summaries = esummary(pmids);
            return format(query, pmids, summaries);
        } catch (Exception e) {
            logger.warn("PubMed search failed: {}", e.getMessage());
            return "Error: PubMed request failed — " + e.getMessage();
        }
    }

    private List<String> esearch(String query, int limit) throws Exception {
        String url = ESEARCH + "?db=pubmed&retmode=json&retmax=" + limit
                + "&term=" + enc(query) + apiKeyParam();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "SwarmAI-Rag/1.0")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("esearch HTTP " + resp.statusCode());
        }
        JsonNode idList = mapper.readTree(resp.body())
                .path("esearchresult").path("idlist");
        List<String> ids = new ArrayList<>();
        if (idList.isArray()) for (JsonNode n : idList) ids.add(n.asText());
        return ids;
    }

    private JsonNode esummary(List<String> pmids) throws Exception {
        String url = ESUMMARY + "?db=pubmed&retmode=json&id=" + String.join(",", pmids) + apiKeyParam();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "SwarmAI-Rag/1.0")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("esummary HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body()).path("result");
    }

    private String format(String query, List<String> pmids, JsonNode summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("PubMed results for '").append(query).append("':\n\n");
        int i = 1;
        for (String pmid : pmids) {
            JsonNode rec = summaries.path(pmid);
            if (rec.isMissingNode() || rec.isEmpty()) continue;
            String title = rec.path("title").asText("(no title)");
            String journal = rec.path("fulljournalname").asText("");
            String pubDate = rec.path("pubdate").asText("");
            String firstAuthor = "";
            JsonNode authors = rec.path("authors");
            if (authors.isArray() && !authors.isEmpty()) firstAuthor = authors.get(0).path("name").asText("");

            String doi = "";
            JsonNode aids = rec.path("articleids");
            if (aids.isArray()) {
                Iterator<JsonNode> it = aids.elements();
                while (it.hasNext()) {
                    JsonNode a = it.next();
                    if ("doi".equalsIgnoreCase(a.path("idtype").asText())) {
                        doi = a.path("value").asText("");
                        break;
                    }
                }
            }

            sb.append(i++).append(". **").append(title).append("**\n");
            if (!firstAuthor.isBlank()) sb.append("   First author: ").append(firstAuthor).append('\n');
            if (!journal.isBlank()) sb.append("   Journal: ").append(journal).append('\n');
            if (!pubDate.isBlank()) sb.append("   Published: ").append(pubDate).append('\n');
            sb.append("   PMID: ").append(pmid).append('\n');
            if (!doi.isBlank()) sb.append("   DOI: ").append(doi).append('\n');
            sb.append("   Link: https://pubmed.ncbi.nlm.nih.gov/").append(pmid).append('\n');
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String apiKeyParam() {
        String k = System.getenv("NCBI_API_KEY");
        return (k == null || k.isBlank()) ? "" : ("&api_key=" + enc(k));
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();
        props.put("query", Map.of("type", "string", "description", "PubMed query (supports MeSH terms, field tags)"));
        props.put("limit", Map.of("type", "integer", "description", "Max results (1.." + MAX_LIMIT + ")"));
        schema.put("properties", props);
        schema.put("required", new String[]{"query"});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return true; }
    @Override public boolean isDynamic() { return true; }
    @Override public String getTriggerWhen() { return "Hypothesis is biomedical, clinical, pharmacological, or epidemiological."; }
    @Override public String getAvoidWhen() { return "Hypothesis is in CS/ML/engineering (use arxiv_search instead)."; }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static int asInt(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try { return v == null ? dflt : Integer.parseInt(v.toString()); } catch (Exception e) { return dflt; }
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
