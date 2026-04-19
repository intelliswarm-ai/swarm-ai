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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jira Tool — search issues (JQL), retrieve single issue, create issue, add comment.
 *
 * Supports Jira Cloud REST API v3. Auth is Basic with email + API token:
 * generate an API token at https://id.atlassian.com/manage-profile/security/api-tokens.
 *
 * Required config (any of env / Spring properties / per-call params):
 *   - JIRA_BASE_URL     : e.g. https://your-domain.atlassian.net
 *   - JIRA_EMAIL        : account email
 *   - JIRA_API_TOKEN    : API token
 *
 * Write operations (create_issue, add_comment) escalate permission level to WORKSPACE_WRITE.
 */
@Component
public class JiraTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(JiraTool.class);
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int MAX_MAX_RESULTS = 100;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.jira.base-url:}")
    private String baseUrl = "";

    @Value("${swarmai.tools.jira.email:}")
    private String email = "";

    @Value("${swarmai.tools.jira.api-token:}")
    private String apiToken = "";

    public JiraTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    JiraTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "jira"; }

    @Override
    public String getDescription() {
        return "Work with Jira Cloud: search_issues (JQL), get_issue (by key/ID), create_issue " +
               "(project+summary+type), or add_comment. Requires JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN. " +
               "Write operations require WORKSPACE_WRITE permission level.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Credentials c = resolveCredentials(parameters);
        if (c == null) {
            return "Error: Jira credentials missing. Provide JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN " +
                   "(env vars, Spring properties, or per-call params 'base_url'/'email'/'api_token').";
        }

        String operation = asString(parameters.getOrDefault("operation", "search_issues")).toLowerCase();
        logger.info("JiraTool: op={} base={}", operation, c.baseUrl);

        try {
            return switch (operation) {
                case "search_issues" -> searchIssues(c, parameters);
                case "get_issue"     -> getIssue(c, parameters);
                case "create_issue"  -> createIssue(c, parameters);
                case "add_comment"   -> addComment(c, parameters);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'search_issues', 'get_issue', 'create_issue', or 'add_comment'.";
            };
        } catch (HttpClientErrorException.Unauthorized e) {
            return "Error: Jira rejected credentials (401). Verify JIRA_EMAIL and JIRA_API_TOKEN are valid.";
        } catch (HttpClientErrorException.Forbidden e) {
            return "Error: Jira returned 403. The account lacks permission on this project/issue.";
        } catch (HttpClientErrorException.NotFound e) {
            return "Error: Jira issue/project not found (404). Check the key, id, or project.";
        } catch (HttpClientErrorException.BadRequest e) {
            return "Error: Jira returned 400 (bad request). Response: " + truncate(e.getResponseBodyAsString(), 500);
        } catch (RestClientException e) {
            logger.warn("JiraTool network error: {}", e.getMessage());
            return "Error: Jira request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("JiraTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String searchIssues(Credentials c, Map<String, Object> parameters) throws Exception {
        String jql = asString(parameters.get("jql"));
        if (jql == null || jql.isBlank()) {
            return "Error: 'jql' parameter is required for search_issues (e.g. " +
                   "'project = ACME AND status = \"In Progress\"').";
        }
        int maxResults = parseMax(parameters);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("jql", jql);
        body.put("maxResults", maxResults);
        ArrayNode fields = body.putArray("fields");
        fields.add("summary");
        fields.add("status");
        fields.add("assignee");
        fields.add("priority");
        fields.add("issuetype");
        fields.add("created");
        fields.add("updated");

        ResponseEntity<String> response = post(c, "/rest/api/3/search/jql", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Jira search returned HTTP " + response.getStatusCode().value();
        }
        return formatSearch(response.getBody(), jql, c.baseUrl);
    }

    private String getIssue(Credentials c, Map<String, Object> parameters) throws Exception {
        String key = asString(parameters.get("issue_key"));
        if (key == null || key.isBlank()) {
            return "Error: 'issue_key' is required for get_issue (e.g. 'ACME-123').";
        }
        ResponseEntity<String> response = get(c, "/rest/api/3/issue/" + key);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Jira get_issue returned HTTP " + response.getStatusCode().value();
        }
        return formatIssue(response.getBody(), c.baseUrl);
    }

    private String createIssue(Credentials c, Map<String, Object> parameters) throws Exception {
        String project = asString(parameters.get("project"));
        String summary = asString(parameters.get("summary"));
        if (project == null || project.isBlank() || summary == null || summary.isBlank()) {
            return "Error: 'project' and 'summary' are required for create_issue.";
        }
        String issueType = asString(parameters.getOrDefault("issue_type", "Task"));
        String description = asString(parameters.get("description"));

        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode fields = body.putObject("fields");
        fields.putObject("project").put("key", project);
        fields.put("summary", summary);
        fields.putObject("issuetype").put("name", issueType);
        if (description != null && !description.isBlank()) {
            fields.set("description", adfParagraph(description));
        }

        ResponseEntity<String> response = post(c, "/rest/api/3/issue", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Jira create_issue returned HTTP " + response.getStatusCode().value();
        }
        JsonNode n = objectMapper.readTree(response.getBody());
        String newKey = n.path("key").asText("(unknown)");
        return "Created issue **" + newKey + "**\nurl: " + c.baseUrl + "/browse/" + newKey;
    }

    private String addComment(Credentials c, Map<String, Object> parameters) throws Exception {
        String key = asString(parameters.get("issue_key"));
        String comment = asString(parameters.get("comment"));
        if (key == null || key.isBlank() || comment == null || comment.isBlank()) {
            return "Error: 'issue_key' and 'comment' are required for add_comment.";
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.set("body", adfParagraph(comment));

        ResponseEntity<String> response = post(c, "/rest/api/3/issue/" + key + "/comment", body.toString());
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: Jira add_comment returned HTTP " + response.getStatusCode().value();
        }
        return "Comment added to " + key + "\nurl: " + c.baseUrl + "/browse/" + key;
    }

    // ---------- formatting ----------

    private String formatSearch(String json, String jql, String baseUrl) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode issues = root.path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return "No Jira issues match: `" + jql + "`";
        }
        StringBuilder out = new StringBuilder();
        out.append("Jira issues for JQL `").append(jql).append("` (")
           .append(root.path("total").asInt(issues.size())).append(" total):\n\n");
        for (JsonNode issue : issues) {
            String key = issue.path("key").asText();
            JsonNode fields = issue.path("fields");
            out.append("• **").append(key).append("** — ").append(fields.path("summary").asText("(no summary)"))
               .append('\n');
            out.append("  status: ").append(fields.path("status").path("name").asText("?"))
               .append(" · type: ").append(fields.path("issuetype").path("name").asText("?"))
               .append(" · priority: ").append(fields.path("priority").path("name").asText("?"))
               .append(" · assignee: ").append(fields.path("assignee").path("displayName").asText("(unassigned)"))
               .append('\n');
            out.append("  url: ").append(baseUrl).append("/browse/").append(key).append('\n');
        }
        return out.toString().trim();
    }

    private String formatIssue(String json, String baseUrl) throws Exception {
        JsonNode issue = objectMapper.readTree(json);
        String key = issue.path("key").asText();
        JsonNode fields = issue.path("fields");

        StringBuilder out = new StringBuilder();
        out.append("**").append(key).append("** — ").append(fields.path("summary").asText("(no summary)")).append("\n\n");
        out.append("Status:   ").append(fields.path("status").path("name").asText("?")).append('\n');
        out.append("Type:     ").append(fields.path("issuetype").path("name").asText("?")).append('\n');
        out.append("Priority: ").append(fields.path("priority").path("name").asText("?")).append('\n');
        out.append("Assignee: ").append(fields.path("assignee").path("displayName").asText("(unassigned)")).append('\n');
        out.append("Reporter: ").append(fields.path("reporter").path("displayName").asText("?")).append('\n');
        out.append("Created:  ").append(fields.path("created").asText("?")).append('\n');
        out.append("Updated:  ").append(fields.path("updated").asText("?")).append('\n');
        out.append("URL:      ").append(baseUrl).append("/browse/").append(key).append('\n');

        String desc = extractAdfText(fields.path("description"));
        if (!desc.isBlank()) {
            out.append("\n**Description**\n").append(desc).append('\n');
        }
        JsonNode comments = fields.path("comment").path("comments");
        if (comments.isArray() && !comments.isEmpty()) {
            out.append("\n**Comments** (").append(comments.size()).append(")\n");
            for (JsonNode c : comments) {
                String author = c.path("author").path("displayName").asText("?");
                String when = c.path("created").asText("?");
                String cText = extractAdfText(c.path("body"));
                out.append("— ").append(author).append(" @ ").append(when).append('\n');
                if (!cText.isBlank()) out.append("  ").append(cText.replace("\n", "\n  ")).append('\n');
            }
        }
        return out.toString().trim();
    }

    /** Jira v3 uses Atlassian Document Format — convert a simple plain string to a minimal ADF paragraph. */
    private ObjectNode adfParagraph(String text) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("type", "doc");
        doc.put("version", 1);
        ArrayNode content = doc.putArray("content");
        ObjectNode para = content.addObject();
        para.put("type", "paragraph");
        ArrayNode paraContent = para.putArray("content");
        ObjectNode txt = paraContent.addObject();
        txt.put("type", "text");
        txt.put("text", text);
        return doc;
    }

    /** Walk an ADF tree and return concatenated text. Good enough for description/comment display. */
    private static String extractAdfText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if ("text".equals(node.path("type").asText())) {
            return node.path("text").asText("");
        }
        StringBuilder sb = new StringBuilder();
        JsonNode content = node.path("content");
        if (content.isArray()) {
            String type = node.path("type").asText();
            for (JsonNode child : content) {
                String childText = extractAdfText(child);
                sb.append(childText);
            }
            // Block-level separators
            if ("paragraph".equals(type) || type.startsWith("heading")) sb.append('\n');
        }
        return sb.toString();
    }

    // ---------- HTTP helpers ----------

    private ResponseEntity<String> get(Credentials c, String path) {
        return restTemplate.exchange(URI.create(c.baseUrl + path), HttpMethod.GET,
            new HttpEntity<>(headers(c)), String.class);
    }

    private ResponseEntity<String> post(Credentials c, String path, String body) {
        return restTemplate.exchange(URI.create(c.baseUrl + path), HttpMethod.POST,
            new HttpEntity<>(body, headers(c)), String.class);
    }

    private HttpHeaders headers(Credentials c) {
        HttpHeaders h = new HttpHeaders();
        String basic = Base64.getEncoder()
            .encodeToString((c.email + ":" + c.apiToken).getBytes(StandardCharsets.UTF_8));
        h.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        return h;
    }

    private Credentials resolveCredentials(Map<String, Object> parameters) {
        String b = firstNonBlank(asString(parameters.get("base_url")), baseUrl, System.getenv("JIRA_BASE_URL"));
        String e = firstNonBlank(asString(parameters.get("email")),    email,   System.getenv("JIRA_EMAIL"));
        String t = firstNonBlank(asString(parameters.get("api_token")), apiToken, System.getenv("JIRA_API_TOKEN"));
        if (b == null || e == null || t == null) return null;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return new Credentials(b, e, t);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private int parseMax(Map<String, Object> parameters) {
        Object raw = parameters.get("max_results");
        if (raw == null) return DEFAULT_MAX_RESULTS;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(1, Math.min(MAX_MAX_RESULTS, n));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_RESULTS;
        }
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n) + "…"; }

    private record Credentials(String baseUrl, String email, String apiToken) {}

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("search_issues", "get_issue", "create_issue", "add_comment"));
        operation.put("description", "Which Jira operation to perform. Default: 'search_issues'.");
        props.put("operation", operation);

        addStringProp(props, "jql", "JQL query for 'search_issues'.");
        addStringProp(props, "issue_key", "Issue key (e.g. 'ACME-42') for 'get_issue' / 'add_comment'.");
        addStringProp(props, "project", "Project key (e.g. 'ACME') for 'create_issue'.");
        addStringProp(props, "summary", "Issue summary for 'create_issue'.");
        addStringProp(props, "issue_type", "Issue type name for 'create_issue'. Default: 'Task'.");
        addStringProp(props, "description", "Plain-text description for 'create_issue'.");
        addStringProp(props, "comment", "Comment text for 'add_comment'.");

        Map<String, Object> max = new HashMap<>();
        max.put("type", "integer");
        max.put("description", "Max rows for 'search_issues' (1–" + MAX_MAX_RESULTS + ").");
        props.put("max_results", max);

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; }
    @Override public String getCategory() { return "productivity"; }
    @Override public List<String> getTags() { return List.of("jira", "atlassian", "tickets", "issues"); }

    @Override
    public PermissionLevel getPermissionLevel() {
        // Base permission level — the tool can write (create/comment), so require WRITE.
        return PermissionLevel.WORKSPACE_WRITE;
    }

    @Override
    public String getTriggerWhen() {
        return "User wants to find, read, or file Jira tickets: search with JQL, look up a specific issue, " +
               "create a new ticket from a conversation, or add a comment to an existing ticket.";
    }

    @Override
    public String getAvoidWhen() {
        return "Work is tracked somewhere other than Atlassian Jira (Linear, GitHub Issues, etc.) — use the " +
               "appropriate tool or github_create_pr instead.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder()
            .env("JIRA_BASE_URL", "JIRA_EMAIL", "JIRA_API_TOKEN")
            .build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Markdown summary of issues, single issue details, or post-action confirmation.");
    }

    @Override
    public String smokeTest() {
        Credentials c = resolveCredentials(Map.of());
        if (c == null) return "Jira credentials not configured";
        try {
            ResponseEntity<String> r = get(c, "/rest/api/3/myself");
            return r.getStatusCode().is2xxSuccessful() ? null
                : "Jira unreachable: HTTP " + r.getStatusCode().value();
        } catch (Exception e) {
            return "Jira unreachable: " + e.getMessage();
        }
    }

    // Credentials (base_url/email/api_token) are config-side only — NOT exposed in the
    // LLM-facing function schema. Models were hallucinating placeholder values for them
    // and overriding the real Spring-property credentials via resolveCredentials().
    public record Request(String operation, String jql, String issue_key, String project, String summary,
                          String issue_type, String description, String comment, Integer max_results) {}
}
