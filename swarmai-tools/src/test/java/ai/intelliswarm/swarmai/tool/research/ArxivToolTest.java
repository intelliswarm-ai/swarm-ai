package ai.intelliswarm.swarmai.tool.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@DisplayName("ArxivTool Unit Tests")
class ArxivToolTest {

    private RestTemplate restTemplate;
    private ArxivTool tool;

    private static final String ATOM_ONE_PAPER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <id>http://arxiv.org/abs/2401.12345v1</id>
            <title>Multi-Agent Cooperation via Attention</title>
            <summary>We propose a novel multi-agent framework using attention-based credit assignment.</summary>
            <published>2024-01-22T10:00:00Z</published>
            <author><name>Jane Doe</name></author>
            <author><name>John Smith</name></author>
            <link title="pdf" href="http://arxiv.org/pdf/2401.12345v1" rel="related" type="application/pdf"/>
          </entry>
        </feed>
        """;

    private static final String ATOM_EMPTY = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"/>
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        tool = new ArxivTool(restTemplate);
    }

    @Test void functionName() { assertEquals("arxiv_search", tool.getFunctionName()); }

    @Test
    void parameterSchema() {
        Map<String, Object> schema = tool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("operation"));
        assertTrue(props.containsKey("query"));
        assertTrue(props.containsKey("id"));
        assertTrue(props.containsKey("limit"));
        assertTrue(props.containsKey("sort_by"));
    }

    @Test
    @DisplayName("search: happy path parses title, authors, abstract, pdf link")
    void searchHappy() {
        stub200(ATOM_ONE_PAPER);

        Object out = tool.execute(Map.of("operation", "search", "query", "multi-agent"));

        String s = out.toString();
        assertTrue(s.contains("Multi-Agent Cooperation via Attention"));
        assertTrue(s.contains("Jane Doe, John Smith"));
        assertTrue(s.contains("2401.12345"));
        assertTrue(s.contains("pdf"));
        assertTrue(s.contains("attention-based credit assignment"));
    }

    @Test
    @DisplayName("search: URL contains encoded all: prefix and limit")
    void searchUrl() {
        stub200(ATOM_EMPTY);

        tool.execute(Map.of("operation", "search", "query", "graph neural networks", "limit", 3));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertTrue(url.contains("search_query=all%3Agraph"), url);
        assertTrue(url.contains("max_results=3"), url);
    }

    @Test
    @DisplayName("search: clamps limit to MAX_LIMIT")
    void searchLimitClamped() {
        stub200(ATOM_EMPTY);

        tool.execute(Map.of("operation", "search", "query", "foo", "limit", 999));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertTrue(uri.getValue().toString().contains("max_results=30"), uri.getValue().toString());
    }

    @Test
    @DisplayName("search: empty feed yields 'no papers found'")
    void searchEmpty() {
        stub200(ATOM_EMPTY);

        Object out = tool.execute(Map.of("operation", "search", "query", "zzz"));

        assertTrue(out.toString().contains("no papers found"), out.toString());
    }

    @Test
    @DisplayName("search: missing query errors without hitting the network")
    void searchRequiresQuery() {
        Object out = tool.execute(Map.of("operation", "search"));
        assertTrue(out.toString().startsWith("Error"));
        verify(restTemplate, never())
            .exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("get: id is passed via id_list query param")
    void getById() {
        stub200(ATOM_ONE_PAPER);

        tool.execute(Map.of("operation", "get", "id", "2401.12345"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertTrue(uri.getValue().toString().contains("id_list=2401.12345"), uri.getValue().toString());
    }

    @Test
    @DisplayName("get: missing id returns a clear error")
    void getRequiresId() {
        Object out = tool.execute(Map.of("operation", "get"));
        assertTrue(out.toString().startsWith("Error"));
    }

    @Test
    @DisplayName("unknown operation returns a helpful error")
    void unknownOperation() {
        Object out = tool.execute(Map.of("operation", "browse"));
        assertTrue(out.toString().contains("unknown operation"), out.toString());
    }

    @Test
    @DisplayName("non-2xx response returns an error")
    void non2xx() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR));

        Object out = tool.execute(Map.of("operation", "search", "query", "foo"));

        assertTrue(out.toString().startsWith("Error"));
    }

    private void stub200(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
