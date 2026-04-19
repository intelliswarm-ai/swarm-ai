package ai.intelliswarm.swarmai.tool.productivity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JiraTool Unit Tests")
class JiraToolTest {

    private RestTemplate restTemplate;
    private JiraTool tool;
    private ObjectMapper mapper;

    private static final String SEARCH_RESPONSE = """
        {
          "total": 2,
          "issues": [
            {
              "key": "ACME-1",
              "fields": {
                "summary": "Fix login bug",
                "status":   { "name": "In Progress" },
                "issuetype":{ "name": "Bug" },
                "priority": { "name": "High" },
                "assignee": { "displayName": "Jane Doe" }
              }
            },
            {
              "key": "ACME-2",
              "fields": {
                "summary": "Add SSO",
                "status":   { "name": "To Do" },
                "issuetype":{ "name": "Task" },
                "priority": { "name": "Medium" },
                "assignee": null
              }
            }
          ]
        }
        """;

    private static final String ISSUE_DETAIL = """
        {
          "key": "ACME-42",
          "fields": {
            "summary": "Improve search performance",
            "status":   { "name": "In Progress" },
            "issuetype":{ "name": "Story" },
            "priority": { "name": "High" },
            "assignee": { "displayName": "Jane Doe" },
            "reporter": { "displayName": "John Smith" },
            "created":  "2026-01-15T09:30:00.000+0000",
            "updated":  "2026-01-20T14:00:00.000+0000",
            "description": {
              "type": "doc",
              "content": [ { "type": "paragraph", "content": [ { "type": "text", "text": "Users report 2s+ search latency." } ] } ]
            },
            "comment": {
              "comments": [
                {
                  "author": { "displayName": "Jane Doe" },
                  "created": "2026-01-16T10:00:00.000+0000",
                  "body": {
                    "type": "doc",
                    "content": [ { "type": "paragraph", "content": [ { "type": "text", "text": "Profiled — N+1 in SearchService." } ] } ]
                  }
                }
              ]
            }
          }
        }
        """;

    private static final String CREATE_RESPONSE = """
        { "id": "10042", "key": "ACME-42", "self": "https://jira.example/rest/api/3/issue/10042" }
        """;

    private static final String COMMENT_RESPONSE = """
        { "id": "99", "body": "ok" }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        mapper = new ObjectMapper();
        tool = new JiraTool(restTemplate, mapper);
        ReflectionTestUtils.setField(tool, "baseUrl", "https://jira.example.atlassian.net");
        ReflectionTestUtils.setField(tool, "email", "me@example.com");
        ReflectionTestUtils.setField(tool, "apiToken", "TOKEN123");
    }

    @Test void functionName() { assertEquals("jira", tool.getFunctionName()); }

    @Test
    void writePermission() {
        // Because add_comment and create_issue mutate state.
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.WORKSPACE_WRITE, tool.getPermissionLevel());
    }

    // ===== Auth header =====

    @Test
    @DisplayName("Basic auth header = base64(email:token)")
    void basicAuthHeader() {
        stub200POST(SEARCH_RESPONSE);

        tool.execute(Map.of("operation", "search_issues", "jql", "project = ACME"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        HttpHeaders h = entity.getValue().getHeaders();
        String expected = "Basic " + Base64.getEncoder()
            .encodeToString("me@example.com:TOKEN123".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, h.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("missing credentials → setup hint, no network call")
    void missingCreds() {
        ReflectionTestUtils.setField(tool, "apiToken", "");
        Object out = tool.execute(Map.of("operation", "search_issues", "jql", "project = ACME"));
        assertTrue(out.toString().contains("credentials missing"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("trailing slash in base URL is stripped")
    void baseUrlTrailingSlash() {
        ReflectionTestUtils.setField(tool, "baseUrl", "https://jira.example.atlassian.net/");
        stub200POST(SEARCH_RESPONSE);

        tool.execute(Map.of("operation", "search_issues", "jql", "project = ACME"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        // The URL should not have a double slash between host and /rest/...
        assertFalse(uri.getValue().toString().contains(".net//"), uri.getValue().toString());
    }

    // ===== search_issues =====

    @Test
    @DisplayName("search_issues: formats key, summary, status, type, priority, assignee, url")
    void searchFormat() {
        stub200POST(SEARCH_RESPONSE);

        Object out = tool.execute(Map.of("operation", "search_issues",
                                         "jql", "project = ACME"));

        String s = out.toString();
        assertTrue(s.contains("• **ACME-1**"));
        assertTrue(s.contains("Fix login bug"));
        assertTrue(s.contains("status: In Progress"));
        assertTrue(s.contains("type: Bug"));
        assertTrue(s.contains("assignee: Jane Doe"));
        assertTrue(s.contains("/browse/ACME-1"));
        assertTrue(s.contains("assignee: (unassigned)"), "Null assignee → '(unassigned)'");
    }

    @Test
    @DisplayName("search_issues: body contains JQL + maxResults + requested fields")
    void searchBody() throws Exception {
        stub200POST(SEARCH_RESPONSE);

        tool.execute(Map.of("operation", "search_issues", "jql", "assignee = currentUser()",
                            "max_results", 75));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("assignee = currentUser()", body.path("jql").asText());
        assertEquals(75, body.path("maxResults").asInt());
        assertTrue(body.path("fields").toString().contains("\"summary\""));
    }

    @Test
    @DisplayName("search_issues: missing JQL is an error")
    void searchRequiresJql() {
        Object out = tool.execute(Map.of("operation", "search_issues"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== get_issue =====

    @Test
    @DisplayName("get_issue: renders description + comments via ADF walker")
    void getIssueFormat() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(ISSUE_DETAIL, HttpStatus.OK));

        Object out = tool.execute(Map.of("operation", "get_issue", "issue_key", "ACME-42"));

        String s = out.toString();
        assertTrue(s.contains("ACME-42"));
        assertTrue(s.contains("Improve search performance"));
        assertTrue(s.contains("Users report 2s+ search latency"), "ADF description must be extracted");
        assertTrue(s.contains("Profiled — N+1 in SearchService"), "ADF comment must be extracted");
        assertTrue(s.contains("/browse/ACME-42"));
    }

    @Test
    @DisplayName("get_issue: requires issue_key")
    void getIssueRequiresKey() {
        Object out = tool.execute(Map.of("operation", "get_issue"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== create_issue =====

    @Test
    @DisplayName("create_issue: sends ADF description + returns new key + URL")
    void createIssue() throws Exception {
        stub200POST(CREATE_RESPONSE);

        Object out = tool.execute(Map.of(
            "operation", "create_issue",
            "project", "ACME",
            "summary", "Investigate slow DB queries",
            "description", "Observed during peak hours.",
            "issue_type", "Bug"
        ));

        String s = out.toString();
        assertTrue(s.contains("Created issue **ACME-42**"));
        assertTrue(s.contains("/browse/ACME-42"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("ACME", body.path("fields").path("project").path("key").asText());
        assertEquals("Investigate slow DB queries", body.path("fields").path("summary").asText());
        assertEquals("Bug", body.path("fields").path("issuetype").path("name").asText());
        // Description must be a valid ADF doc, not a plain string.
        assertEquals("doc", body.path("fields").path("description").path("type").asText());
        assertEquals("Observed during peak hours.",
            body.path("fields").path("description").path("content").get(0)
                 .path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("create_issue: missing project or summary is an error")
    void createIssueMissing() {
        Object out1 = tool.execute(Map.of("operation", "create_issue", "project", "ACME"));
        assertTrue(out1.toString().startsWith("Error"));
        Object out2 = tool.execute(Map.of("operation", "create_issue", "summary", "x"));
        assertTrue(out2.toString().startsWith("Error"));
    }

    // ===== add_comment =====

    @Test
    @DisplayName("add_comment: posts ADF body and returns confirmation")
    void addComment() throws Exception {
        stub200POST(COMMENT_RESPONSE);

        Object out = tool.execute(Map.of(
            "operation", "add_comment",
            "issue_key", "ACME-10",
            "comment", "Nice work on the repro."
        ));

        assertTrue(out.toString().contains("Comment added to ACME-10"));
        assertTrue(out.toString().contains("/browse/ACME-10"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("doc", body.path("body").path("type").asText());
    }

    // ===== Error surfaces =====

    @Test
    @DisplayName("401 → 'Jira rejected credentials' message")
    void unauthorized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        Object out = tool.execute(Map.of("operation", "search_issues", "jql", "project = ACME"));
        assertTrue(out.toString().contains("401"), out.toString());
    }

    @Test
    @DisplayName("404 → 'Jira issue/project not found' message")
    void notFound() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        Object out = tool.execute(Map.of("operation", "get_issue", "issue_key", "ZZZZ-1"));
        assertTrue(out.toString().contains("404"));
    }

    @Test
    @DisplayName("unknown operation → helpful error")
    void unknownOp() {
        Object out = tool.execute(Map.of("operation", "transition", "issue_key", "X-1"));
        assertTrue(out.toString().contains("unknown operation"));
    }

    @SuppressWarnings("unchecked")
    private void stub200POST(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
