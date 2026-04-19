package ai.intelliswarm.swarmai.tool.productivity;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notion Tool — search pages/databases, retrieve pages, query databases.
 *
 * Requires NOTION_TOKEN — create an internal integration at
 * https://www.notion.so/profile/integrations, then share specific pages/databases
 * with it (Notion scopes access by explicit sharing, not by token).
 *
 * Uses the stable API version 2022-06-28. Write operations are intentionally
 * omitted from v1 of this tool — they'd need a permission-level bump to
 * WRITE/DANGEROUS and careful page-structure modelling; out-of-scope for MVP.
 */
@Component
public class NotionTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(NotionTool.class);
    private static final String API_BASE = "https://api.notion.com/v1";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.notion.token:}")
    private String token = "";

    public NotionTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    NotionTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "notion"; }

    @Override
    public String getDescription() {
        return "Read Notion workspaces: search pages & databases by keyword, retrieve a specific page " +
               "(metadata + block text), or query a database with filters/sorts. Requires NOTION_TOKEN " +
               "and that the integration has been explicitly shared with target pages.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String effectiveToken = pickToken();
        if (effectiveToken == null || effectiveToken.isBlank()) {
            return "Error: Notion token not configured. Set NOTION_TOKEN env var " +
                   "or swarmai.tools.notion.token property. Integration must also be shared with target pages.";
        }
        String operation = asString(parameters.getOrDefault("operation", "search")).toLowerCase();
        logger.info("NotionTool: op={} params={}", operation, parameters.keySet());

        try {
            return switch (operation) {
                case "search"         -> search(effectiveToken, parameters);
                case "retrieve_page"  -> retrievePage(effectiveToken, parameters);
                case "query_database" -> queryDatabase(effectiveToken, parameters);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'search', 'retrieve_page', or 'query_database'.";
            };
        } catch (HttpClientErrorException.Unauthorized e) {
            return "Error: Notion rejected the token (401). Verify NOTION_TOKEN is a valid integration token.";
        } catch (HttpClientErrorException.Forbidden e) {
            return "Error: Notion returned 403. The integration must be explicitly shared with this " +
                   "page or database — open the page in Notion → '⋯' → 'Add connections' → pick the integration.";
        } catch (HttpClientErrorException.NotFound e) {
            return "Error: Notion page or database not found (404). Check the ID and that the " +
                   "integration has access.";
        } catch (RestClientException e) {
            logger.warn("NotionTool network error: {}", e.getMessage());
            return "Error: Notion request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("NotionTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String search(String token, Map<String, Object> parameters) throws Exception {
        String query = asString(parameters.get("query"));
        int pageSize = parsePageSize(parameters);
        String filterType = asString(parameters.get("filter_type")); // 'page' or 'database' — optional

        ObjectNode body = objectMapper.createObjectNode();
        if (query != null && !query.isBlank()) body.put("query", query);
        body.put("page_size", pageSize);
        if ("page".equals(filterType) || "database".equals(filterType)) {
            ObjectNode filter = body.putObject("filter");
            filter.put("property", "object");
            filter.put("value", filterType);
        }

        ResponseEntity<String> response = post(token, "/search", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Notion search returned HTTP " + response.getStatusCode().value();
        }
        return formatSearch(response.getBody(), query);
    }

    private String retrievePage(String token, Map<String, Object> parameters) throws Exception {
        String pageId = asString(parameters.get("page_id"));
        if (pageId == null || pageId.isBlank()) {
            return "Error: 'page_id' is required for operation='retrieve_page'.";
        }
        String id = pageId.replace("-", "");

        // Page metadata
        ResponseEntity<String> metaResp = get(token, "/pages/" + id);
        if (!metaResp.getStatusCode().is2xxSuccessful() || metaResp.getBody() == null) {
            return "Error: Notion page returned HTTP " + metaResp.getStatusCode().value();
        }
        // Page body blocks
        ResponseEntity<String> blocksResp = get(token, "/blocks/" + id + "/children?page_size=100");
        return formatPage(metaResp.getBody(),
                          blocksResp.getStatusCode().is2xxSuccessful() ? blocksResp.getBody() : null);
    }

    private String queryDatabase(String token, Map<String, Object> parameters) throws Exception {
        String dbId = asString(parameters.get("database_id"));
        if (dbId == null || dbId.isBlank()) {
            return "Error: 'database_id' is required for operation='query_database'.";
        }
        int pageSize = parsePageSize(parameters);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("page_size", pageSize);

        // Optional raw JSON pass-through for power users who know Notion's filter syntax.
        String rawFilter = asString(parameters.get("filter"));
        if (rawFilter != null && !rawFilter.isBlank()) {
            body.set("filter", objectMapper.readTree(rawFilter));
        }
        String rawSorts = asString(parameters.get("sorts"));
        if (rawSorts != null && !rawSorts.isBlank()) {
            body.set("sorts", objectMapper.readTree(rawSorts));
        }

        ResponseEntity<String> response = post(token, "/databases/" + dbId.replace("-", "") + "/query",
                                               body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Notion query_database returned HTTP " + response.getStatusCode().value();
        }
        return formatDatabaseQuery(response.getBody());
    }

    // ---------- formatting ----------

    private String formatSearch(String json, String query) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "No Notion results" + (query == null ? "" : " for '" + query + "'") + ".";
        }
        StringBuilder out = new StringBuilder();
        out.append("Notion search results");
        if (query != null && !query.isBlank()) out.append(" for '").append(query).append("'");
        out.append(":\n\n");
        int i = 1;
        for (JsonNode r : results) {
            String kind = r.path("object").asText(); // "page" or "database"
            String id = r.path("id").asText();
            String title = extractTitle(r);
            String url = r.path("url").asText("");
            out.append(i++).append(". **").append(title).append("**  _(")
               .append(kind).append(")_\n");
            out.append("   id: `").append(id).append("`\n");
            if (!url.isBlank()) out.append("   url: ").append(url).append('\n');
        }
        if (root.path("has_more").asBoolean(false)) {
            out.append("\n… more results available (use a more specific query).\n");
        }
        return out.toString().trim();
    }

    private String formatPage(String metaJson, String blocksJson) throws Exception {
        JsonNode meta = objectMapper.readTree(metaJson);
        String title = extractTitle(meta);
        String id = meta.path("id").asText();
        String url = meta.path("url").asText("");

        StringBuilder out = new StringBuilder();
        out.append("**").append(title).append("**\n");
        out.append("id: `").append(id).append("`\n");
        if (!url.isBlank()) out.append("url: ").append(url).append('\n');
        out.append('\n');

        if (blocksJson != null) {
            JsonNode results = objectMapper.readTree(blocksJson).path("results");
            if (results.isArray() && !results.isEmpty()) {
                for (JsonNode block : results) {
                    String text = extractBlockText(block);
                    if (!text.isBlank()) out.append(text).append('\n');
                }
            } else {
                out.append("_(no body blocks)_\n");
            }
        }
        return out.toString().trim();
    }

    private String formatDatabaseQuery(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return "No matching rows in database.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Database query returned ").append(results.size()).append(" row(s):\n\n");
        int i = 1;
        for (JsonNode r : results) {
            String title = extractTitle(r);
            out.append(i++).append(". **").append(title).append("**\n");
            out.append("   id: `").append(r.path("id").asText()).append("`\n");
            // Print each property name + summarised value
            JsonNode props = r.path("properties");
            if (props.isObject()) {
                props.fieldNames().forEachRemaining(name -> {
                    JsonNode prop = props.path(name);
                    String val = summariseProperty(prop);
                    if (!val.isBlank()) out.append("   ").append(name).append(": ").append(val).append('\n');
                });
            }
        }
        return out.toString().trim();
    }

    private static String extractTitle(JsonNode resource) {
        // Page: properties.{any title prop}.title[*].plain_text
        JsonNode props = resource.path("properties");
        if (props.isObject()) {
            for (JsonNode prop : props) {
                if ("title".equals(prop.path("type").asText())) {
                    JsonNode arr = prop.path("title");
                    if (arr.isArray() && arr.size() > 0) {
                        return arr.get(0).path("plain_text").asText();
                    }
                }
            }
        }
        // Database: title[*].plain_text
        JsonNode dbTitle = resource.path("title");
        if (dbTitle.isArray() && dbTitle.size() > 0) {
            return dbTitle.get(0).path("plain_text").asText();
        }
        return "(untitled)";
    }

    private static String extractBlockText(JsonNode block) {
        String type = block.path("type").asText();
        JsonNode typed = block.path(type);
        // Common text-bearing blocks keep rich_text at the typed payload.
        JsonNode rich = typed.path("rich_text");
        if (rich.isArray() && rich.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode piece : rich) sb.append(piece.path("plain_text").asText());
            String prefix = switch (type) {
                case "heading_1"         -> "# ";
                case "heading_2"         -> "## ";
                case "heading_3"         -> "### ";
                case "bulleted_list_item"-> "• ";
                case "numbered_list_item"-> "1. ";
                case "to_do" -> typed.path("checked").asBoolean(false) ? "[x] " : "[ ] ";
                case "quote" -> "> ";
                default -> "";
            };
            return prefix + sb;
        }
        return "";
    }

    private static String summariseProperty(JsonNode prop) {
        String type = prop.path("type").asText();
        JsonNode typed = prop.path(type);
        return switch (type) {
            case "title", "rich_text" -> joinPlainText(typed);
            case "number"    -> typed.isNumber() ? typed.asText() : "";
            case "select"    -> typed.path("name").asText("");
            case "multi_select" -> {
                if (!typed.isArray()) yield "";
                StringBuilder sb = new StringBuilder();
                for (JsonNode v : typed) { if (sb.length() > 0) sb.append(", "); sb.append(v.path("name").asText()); }
                yield sb.toString();
            }
            case "status"    -> typed.path("name").asText("");
            case "checkbox"  -> typed.isBoolean() ? String.valueOf(typed.asBoolean()) : "";
            case "date"      -> {
                String start = typed.path("start").asText("");
                String end = typed.path("end").asText("");
                yield end.isBlank() ? start : (start + " → " + end);
            }
            case "url", "email", "phone_number" -> typed.asText("");
            case "people" -> {
                if (!typed.isArray()) yield "";
                StringBuilder sb = new StringBuilder();
                for (JsonNode v : typed) { if (sb.length() > 0) sb.append(", "); sb.append(v.path("name").asText()); }
                yield sb.toString();
            }
            default -> "";
        };
    }

    private static String joinPlainText(JsonNode arr) {
        if (!arr.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode piece : arr) sb.append(piece.path("plain_text").asText());
        return sb.toString();
    }

    // ---------- HTTP helpers ----------

    private ResponseEntity<String> get(String token, String path) {
        return restTemplate.exchange(URI.create(API_BASE + path), HttpMethod.GET,
            new HttpEntity<>(headers(token)), String.class);
    }

    private ResponseEntity<String> post(String token, String path, String body) {
        return restTemplate.exchange(URI.create(API_BASE + path), HttpMethod.POST,
            new HttpEntity<>(body, headers(token)), String.class);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.set("Notion-Version", NOTION_VERSION);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private String pickToken() {
        if (token != null && !token.isBlank()) return token;
        String env = System.getenv("NOTION_TOKEN");
        return env != null && !env.isBlank() ? env : null;
    }

    private int parsePageSize(Map<String, Object> parameters) {
        Object raw = parameters.get("page_size");
        if (raw == null) return DEFAULT_PAGE_SIZE;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(1, Math.min(MAX_PAGE_SIZE, n));
        } catch (NumberFormatException e) {
            return DEFAULT_PAGE_SIZE;
        }
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("search", "retrieve_page", "query_database"));
        operation.put("description", "Which Notion API to call. Default: 'search'.");
        props.put("operation", operation);

        Map<String, Object> query = new HashMap<>();
        query.put("type", "string");
        query.put("description", "Keyword (for 'search'). Empty returns all accessible pages/dbs.");
        props.put("query", query);

        Map<String, Object> pageId = new HashMap<>();
        pageId.put("type", "string");
        pageId.put("description", "Notion page UUID (for 'retrieve_page'). Hyphens optional.");
        props.put("page_id", pageId);

        Map<String, Object> dbId = new HashMap<>();
        dbId.put("type", "string");
        dbId.put("description", "Notion database UUID (for 'query_database').");
        props.put("database_id", dbId);

        Map<String, Object> filterType = new HashMap<>();
        filterType.put("type", "string");
        filterType.put("enum", List.of("page", "database"));
        filterType.put("description", "Limit 'search' to pages or databases only.");
        props.put("filter_type", filterType);

        Map<String, Object> filter = new HashMap<>();
        filter.put("type", "string");
        filter.put("description", "Raw Notion filter JSON (for 'query_database' power users).");
        props.put("filter", filter);

        Map<String, Object> sorts = new HashMap<>();
        sorts.put("type", "string");
        sorts.put("description", "Raw Notion sorts JSON array (for 'query_database').");
        props.put("sorts", sorts);

        Map<String, Object> pageSize = new HashMap<>();
        pageSize.put("type", "integer");
        pageSize.put("description", "Max rows per response (1–" + MAX_PAGE_SIZE + "). Default: " + DEFAULT_PAGE_SIZE + ".");
        props.put("page_size", pageSize);

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; } // Notion content mutates
    @Override public String getCategory() { return "productivity"; }
    @Override public List<String> getTags() { return List.of("notion", "knowledge-base", "docs", "database"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }

    @Override
    public String getTriggerWhen() {
        return "User asks to find, read, or query content in their Notion workspace: search pages, " +
               "retrieve a specific note, or filter rows in a Notion database.";
    }

    @Override
    public String getAvoidWhen() {
        return "User wants to create or modify Notion content (not supported in this tool yet), or the " +
               "content lives outside Notion — use web_search for public pages.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("NOTION_TOKEN").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Markdown with titles, ids, urls, and page body for retrieved items.");
    }

    @Override
    public String smokeTest() {
        String t = pickToken();
        if (t == null) return "NOTION_TOKEN not configured";
        try {
            ObjectNode probe = objectMapper.createObjectNode();
            probe.put("page_size", 1);
            ResponseEntity<String> r = post(t, "/search", probe.toString());
            return r.getStatusCode().is2xxSuccessful() ? null
                : "Notion unreachable: HTTP " + r.getStatusCode().value();
        } catch (Exception e) {
            return "Notion unreachable: " + e.getMessage();
        }
    }

    public record Request(String operation, String query, String page_id, String database_id,
                          String filter_type, String filter, String sorts, Integer page_size) {}
}
