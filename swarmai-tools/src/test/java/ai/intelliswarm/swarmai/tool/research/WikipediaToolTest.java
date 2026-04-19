package ai.intelliswarm.swarmai.tool.research;

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
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WikipediaTool Unit Tests")
class WikipediaToolTest {

    private RestTemplate restTemplate;
    private WikipediaTool tool;

    private static final String SUMMARY_JSON_EINSTEIN = """
        {
          "type": "standard",
          "title": "Albert Einstein",
          "description": "German-born theoretical physicist (1879–1955)",
          "extract": "Albert Einstein was a German-born theoretical physicist, widely acknowledged to be one of the greatest physicists of all time.",
          "content_urls": {
            "desktop": { "page": "https://en.wikipedia.org/wiki/Albert_Einstein" }
          }
        }
        """;

    private static final String SUMMARY_JSON_DISAMBIGUATION = """
        { "type": "disambiguation", "title": "Mercury", "extract": "Mercury may refer to..." }
        """;

    private static final String SEARCH_JSON = """
        {
          "query": {
            "search": [
              { "title": "Apple Inc.", "snippet": "American multinational <span>technology</span> company" },
              { "title": "Apple",      "snippet": "Fruit of the apple tree" }
            ]
          }
        }
        """;

    private static final String EMPTY_SEARCH_JSON = """
        { "query": { "search": [] } }
        """;

    private static final String PAGE_HTML = """
        <html><body><p>Paris is the capital of France.</p><p>It sits on the Seine.</p></body></html>
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        tool = new WikipediaTool(restTemplate, new ObjectMapper());
    }

    // ==================== Interface contract ====================

    @Test
    @DisplayName("function name is 'wikipedia'")
    void functionName() {
        assertEquals("wikipedia", tool.getFunctionName());
    }

    @Test
    @DisplayName("description mentions Wikipedia and operations")
    void description() {
        String d = tool.getDescription();
        assertNotNull(d);
        assertTrue(d.toLowerCase().contains("wikipedia"));
        assertTrue(d.contains("summary") && d.contains("search") && d.contains("page"));
    }

    @Test
    @DisplayName("parameter schema declares the documented fields")
    void parameterSchema() {
        Map<String, Object> schema = tool.getParameterSchema();
        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("query"));
        assertTrue(props.containsKey("operation"));
        assertTrue(props.containsKey("language"));
        assertTrue(props.containsKey("limit"));
        assertArrayEquals(new String[]{"query"}, (String[]) schema.get("required"));
    }

    @Test
    @DisplayName("not async, cacheable, category=research")
    void metadata() {
        assertFalse(tool.isAsync());
        assertTrue(tool.isCacheable());
        assertEquals("research", tool.getCategory());
        assertTrue(tool.getTags().contains("wikipedia"));
    }

    // ==================== Summary operation ====================

    @Test
    @DisplayName("summary: formats article with title, description, extract, source")
    void summaryHappyPath() {
        stub200(SUMMARY_JSON_EINSTEIN);

        Object out = tool.execute(Map.of("query", "Albert Einstein"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), s);
        assertTrue(s.contains("Albert Einstein"));
        assertTrue(s.contains("German-born theoretical physicist"));
        assertTrue(s.contains("Source:"));
        assertTrue(s.contains("wikipedia.org/wiki/Albert_Einstein"));
    }

    @Test
    @DisplayName("summary: disambiguation pages are flagged, not returned as-is")
    void summaryDisambiguation() {
        stub200(SUMMARY_JSON_DISAMBIGUATION);

        Object out = tool.execute(Map.of("query", "Mercury"));

        assertTrue(out.toString().contains("Disambiguation"), out.toString());
    }

    @Test
    @DisplayName("summary: non-2xx response returns an Error string")
    void summary404() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("", HttpStatus.NOT_FOUND));

        Object out = tool.execute(Map.of("query", "asdlkjfqwoieur"));

        assertTrue(out.toString().startsWith("Error"), out.toString());
    }

    @Test
    @DisplayName("summary: URL-encodes the page title (spaces, unicode)")
    void summaryEncodesTitle() {
        stub200(SUMMARY_JSON_EINSTEIN);

        tool.execute(Map.of("query", "São Paulo"));

        ArgumentCaptor<URI> urlCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET),
                                      any(HttpEntity.class), eq(String.class));
        String url = urlCaptor.getValue().toString();
        assertTrue(url.contains("/api/rest_v1/page/summary/"));
        // '%20' for space, '%C3%A3' for 'ã'
        assertTrue(url.contains("S%C3%A3o%20Paulo"), "actual: " + url);
    }

    @Test
    @DisplayName("summary: sends a custom User-Agent header")
    void summarySendsUserAgent() {
        stub200(SUMMARY_JSON_EINSTEIN);

        tool.execute(Map.of("query", "Java"));

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET),
                                      entityCaptor.capture(), eq(String.class));
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertNotNull(headers.getFirst(HttpHeaders.USER_AGENT));
        assertFalse(headers.getFirst(HttpHeaders.USER_AGENT).isBlank());
    }

    // ==================== Search operation ====================

    @Test
    @DisplayName("search: returns ranked list with snippets stripped of HTML")
    void searchHappyPath() {
        stub200(SEARCH_JSON);

        Object out = tool.execute(Map.of("query", "apple", "operation", "search"));

        String s = out.toString();
        assertTrue(s.contains("Apple Inc."));
        assertTrue(s.contains("Fruit of the apple tree"));
        // HTML inside snippet must be stripped
        assertFalse(s.contains("<span>"), s);
    }

    @Test
    @DisplayName("search: empty results surface a 'No results' message")
    void searchEmpty() {
        stub200(EMPTY_SEARCH_JSON);

        Object out = tool.execute(Map.of("query", "zzzzzzzzz", "operation", "search"));

        assertTrue(out.toString().contains("No Wikipedia results"), out.toString());
    }

    @Test
    @DisplayName("search: clamps 'limit' to the 1..MAX_SEARCH_LIMIT window")
    void searchLimitClamped() {
        stub200(EMPTY_SEARCH_JSON);

        tool.execute(Map.of("query", "foo", "operation", "search", "limit", 9999));

        ArgumentCaptor<URI> urlCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET),
                                      any(HttpEntity.class), eq(String.class));
        String url = urlCaptor.getValue().toString();
        assertTrue(url.contains("srlimit=20"), url);
    }

    // ==================== Page operation ====================

    @Test
    @DisplayName("page: strips HTML and returns plain text")
    void pageStripsHtml() {
        stub200(PAGE_HTML);

        Object out = tool.execute(Map.of("query", "Paris", "operation", "page"));

        String s = out.toString();
        assertTrue(s.contains("Paris is the capital of France"));
        assertTrue(s.contains("It sits on the Seine"));
        assertFalse(s.contains("<p>"), s);
    }

    // ==================== Validation & error paths ====================

    @Test
    @DisplayName("missing 'query' returns an error")
    void missingQueryErrors() {
        Object out = tool.execute(new HashMap<>());
        assertTrue(out.toString().startsWith("Error"), out.toString());
    }

    @Test
    @DisplayName("blank 'query' returns an error")
    void blankQueryErrors() {
        Object out = tool.execute(Map.of("query", "   "));
        assertTrue(out.toString().startsWith("Error"), out.toString());
    }

    @Test
    @DisplayName("invalid language code returns an error (no network call)")
    void invalidLanguage() {
        Object out = tool.execute(Map.of("query", "Paris", "language", "not-a-lang-code!"));
        assertTrue(out.toString().startsWith("Error"), out.toString());
        // Should short-circuit before calling out
        verify(restTemplate, org.mockito.Mockito.never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("unknown operation returns a helpful error")
    void unknownOperation() {
        Object out = tool.execute(Map.of("query", "Paris", "operation", "bogus"));
        assertTrue(out.toString().startsWith("Error"), out.toString());
        assertTrue(out.toString().contains("bogus"));
    }

    @Test
    @DisplayName("language parameter selects the right Wikipedia edition")
    void languageAppliedToUrl() {
        stub200(SUMMARY_JSON_EINSTEIN);

        tool.execute(Map.of("query", "Einstein", "language", "de"));

        ArgumentCaptor<URI> urlCaptor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET),
                                      any(HttpEntity.class), eq(String.class));
        String url = urlCaptor.getValue().toString();
        assertTrue(url.startsWith("https://de.wikipedia.org/"), url);
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private void stub200(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
