package ai.intelliswarm.swarmai.tool.productivity;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotionTool Unit Tests")
class NotionToolTest {

    private RestTemplate restTemplate;
    private NotionTool tool;
    private ObjectMapper mapper;

    private static final String SEARCH_RESPONSE = """
        {
          "results": [
            {
              "object": "page",
              "id": "5c8dbedb-xxxx",
              "url": "https://notion.so/Product-Roadmap-5c8dbedb",
              "properties": {
                "Name": { "type": "title", "title": [ { "plain_text": "Product Roadmap" } ] }
              }
            },
            {
              "object": "database",
              "id": "fffff000-yyyy",
              "url": "https://notion.so/Issues-fffff000",
              "title": [ { "plain_text": "Issues" } ]
            }
          ],
          "has_more": false
        }
        """;

    private static final String PAGE_META = """
        {
          "id": "5c8dbedb-xxxx",
          "url": "https://notion.so/Product-Roadmap-5c8dbedb",
          "properties": {
            "Name": { "type": "title", "title": [ { "plain_text": "Product Roadmap" } ] }
          }
        }
        """;

    private static final String PAGE_BLOCKS = """
        {
          "results": [
            { "type": "heading_1", "heading_1": { "rich_text": [ { "plain_text": "Q2 goals" } ] } },
            { "type": "paragraph", "paragraph": { "rich_text": [ { "plain_text": "Ship the new onboarding." } ] } },
            { "type": "to_do", "to_do": { "rich_text": [ { "plain_text": "Wire analytics" } ], "checked": false } },
            { "type": "to_do", "to_do": { "rich_text": [ { "plain_text": "Draft blog" } ], "checked": true } }
          ]
        }
        """;

    private static final String DB_QUERY_RESPONSE = """
        {
          "results": [
            {
              "id": "111-aaa",
              "properties": {
                "Name":   { "type": "title",  "title": [ { "plain_text": "Fix login bug" } ] },
                "Status": { "type": "status", "status": { "name": "In progress" } },
                "Owner":  { "type": "people", "people": [ { "name": "Jane" } ] }
              }
            }
          ]
        }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        mapper = new ObjectMapper();
        tool = new NotionTool(restTemplate, mapper);
        ReflectionTestUtils.setField(tool, "token", "secret_TESTTOKEN");
    }

    @Test void functionName() { assertEquals("notion", tool.getFunctionName()); }

    // ===== Auth header plumbing =====

    @Test
    @DisplayName("every request carries Bearer token, Notion-Version header, and JSON content type")
    void headersSet() {
        stub200POST(SEARCH_RESPONSE);

        tool.execute(Map.of("operation", "search", "query", "goals"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        HttpHeaders h = entity.getValue().getHeaders();
        assertEquals("Bearer secret_TESTTOKEN", h.getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals("2022-06-28", h.getFirst("Notion-Version"));
        assertTrue(h.getFirst(HttpHeaders.CONTENT_TYPE).startsWith("application/json"));
    }

    // ===== search =====

    @Test
    @DisplayName("search: formats page + database results with ids and object kind")
    void searchFormat() {
        stub200POST(SEARCH_RESPONSE);

        Object out = tool.execute(Map.of("operation", "search", "query", "goals"));

        String s = out.toString();
        assertTrue(s.contains("Product Roadmap"));
        assertTrue(s.contains("(page)"));
        assertTrue(s.contains("Issues"));
        assertTrue(s.contains("(database)"));
        assertTrue(s.contains("id:"));
    }

    @Test
    @DisplayName("search: request body contains query + page_size + filter_type when provided")
    void searchBody() throws Exception {
        stub200POST(SEARCH_RESPONSE);

        tool.execute(Map.of("operation", "search", "query", "goals",
                            "filter_type", "page", "page_size", 50));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        var body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("goals", body.path("query").asText());
        assertEquals(50, body.path("page_size").asInt());
        assertEquals("object", body.path("filter").path("property").asText());
        assertEquals("page", body.path("filter").path("value").asText());
    }

    @Test
    @DisplayName("search: empty results yield clean message")
    void searchEmpty() {
        stub200POST("""
            { "results": [], "has_more": false }
            """);

        Object out = tool.execute(Map.of("operation", "search", "query", "zzz"));

        assertTrue(out.toString().contains("No Notion results"));
    }

    // ===== retrieve_page =====

    @Test
    @DisplayName("retrieve_page: strips hyphens from UUID and formats headings + todos")
    void retrievePageFormat() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(PAGE_META, HttpStatus.OK),
                        new ResponseEntity<>(PAGE_BLOCKS, HttpStatus.OK));

        Object out = tool.execute(Map.of("operation", "retrieve_page",
                                         "page_id", "5c8dbedb-xxxx"));

        String s = out.toString();
        assertTrue(s.contains("Product Roadmap"));
        assertTrue(s.contains("# Q2 goals"), "heading_1 → '# '. Got:\n" + s);
        assertTrue(s.contains("Ship the new onboarding."));
        assertTrue(s.contains("[ ] Wire analytics"), "unchecked todo → '[ ] '");
        assertTrue(s.contains("[x] Draft blog"), "checked todo → '[x] '");
    }

    @Test
    @DisplayName("retrieve_page: missing page_id yields error without calling the API")
    void retrieveRequiresId() {
        Object out = tool.execute(Map.of("operation", "retrieve_page"));
        assertTrue(out.toString().startsWith("Error"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("retrieve_page: URL uses no hyphens in the UUID")
    void retrieveUuidNormalized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(PAGE_META, HttpStatus.OK),
                        new ResponseEntity<>(PAGE_BLOCKS, HttpStatus.OK));

        tool.execute(Map.of("operation", "retrieve_page",
                            "page_id", "5c8dbedb-1111-2222-3333-444455556666"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate, org.mockito.Mockito.atLeastOnce())
            .exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertTrue(uri.getValue().toString().contains("5c8dbedb111122223333444455556666"),
            uri.getValue().toString());
    }

    // ===== query_database =====

    @Test
    @DisplayName("query_database: formats each row with title + summarised properties")
    void queryDatabaseFormat() {
        stub200POST(DB_QUERY_RESPONSE);

        Object out = tool.execute(Map.of(
            "operation", "query_database",
            "database_id", "abc-def"));

        String s = out.toString();
        assertTrue(s.contains("Fix login bug"), s);
        assertTrue(s.contains("Status: In progress"), s);
        assertTrue(s.contains("Owner: Jane"), s);
    }

    @Test
    @DisplayName("query_database: raw filter JSON is forwarded in body")
    void queryDatabaseRawFilter() throws Exception {
        stub200POST(DB_QUERY_RESPONSE);

        String rawFilter = "{\"property\":\"Status\",\"status\":{\"equals\":\"Done\"}}";
        tool.execute(Map.of(
            "operation", "query_database",
            "database_id", "abc",
            "filter", rawFilter));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        var body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("Status", body.path("filter").path("property").asText());
        assertEquals("Done", body.path("filter").path("status").path("equals").asText());
    }

    @Test
    @DisplayName("query_database: missing database_id is an error")
    void queryRequiresDbId() {
        Object out = tool.execute(Map.of("operation", "query_database"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== Auth failures =====

    @Test
    @DisplayName("401 → friendly token error, no exception leak")
    void unauthorized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        Object out = tool.execute(Map.of("operation", "search", "query", "foo"));
        assertTrue(out.toString().contains("401"));
    }

    @Test
    @DisplayName("403 → 'integration must be shared' hint")
    void forbiddenHint() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        Object out = tool.execute(Map.of("operation", "search", "query", "foo"));
        assertTrue(out.toString().contains("shared"), out.toString());
    }

    @Test
    @DisplayName("missing NOTION_TOKEN → setup hint, no network")
    void missingToken() {
        ReflectionTestUtils.setField(tool, "token", "");
        Object out = tool.execute(Map.of("operation", "search", "query", "foo"));
        assertTrue(out.toString().contains("not configured"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("unknown operation → helpful error")
    void unknownOp() {
        Object out = tool.execute(Map.of("operation", "archive", "query", "foo"));
        assertTrue(out.toString().contains("unknown operation"));
    }

    @SuppressWarnings("unchecked")
    private void stub200POST(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
