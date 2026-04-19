package ai.intelliswarm.swarmai.tool.vector;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PineconeVectorTool Unit Tests")
class PineconeVectorToolTest {

    private RestTemplate restTemplate;
    private PineconeVectorTool tool;
    private ObjectMapper mapper;

    private static final String QUERY_RESPONSE = """
        {
          "matches": [
            { "id": "doc-1", "score": 0.9123, "metadata": { "title": "Hello world", "source": "wiki" } },
            { "id": "doc-2", "score": 0.7411, "metadata": { "title": "Another doc" } }
          ],
          "namespace": "default"
        }
        """;

    private static final String UPSERT_RESPONSE = """
        { "upsertedCount": 3 }
        """;

    private static final String STATS_RESPONSE = """
        {
          "dimension": 1536,
          "indexFullness": 0.03,
          "totalVectorCount": 120,
          "namespaces": {
            "": { "vectorCount": 90 },
            "user-42": { "vectorCount": 30 }
          }
        }
        """;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        mapper = new ObjectMapper();
        tool = new PineconeVectorTool(restTemplate, mapper);
        ReflectionTestUtils.setField(tool, "apiKey", "pcsk_fake");
        ReflectionTestUtils.setField(tool, "indexHost", "https://idx-xyz.svc.us-east1-gcp.pinecone.io");
    }

    @Test void functionName() { assertEquals("pinecone", tool.getFunctionName()); }

    @Test void writePermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.WORKSPACE_WRITE,
            tool.getPermissionLevel());
    }

    // ===== Auth =====

    @Test
    @DisplayName("every request sets Api-Key header + JSON content type")
    void headers() {
        stub200("{}");

        tool.execute(Map.of("operation", "stats"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        HttpHeaders h = entity.getValue().getHeaders();
        assertEquals("pcsk_fake", h.getFirst("Api-Key"));
        assertTrue(h.getFirst(HttpHeaders.CONTENT_TYPE).startsWith("application/json"));
    }

    @Test
    @DisplayName("missing API key → setup hint, no network call")
    void missingKey() {
        ReflectionTestUtils.setField(tool, "apiKey", "");
        Object out = tool.execute(Map.of("operation", "stats"));
        assertTrue(out.toString().contains("PINECONE_API_KEY"));
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("missing index host → setup hint")
    void missingHost() {
        ReflectionTestUtils.setField(tool, "indexHost", "");
        Object out = tool.execute(Map.of("operation", "stats"));
        assertTrue(out.toString().contains("PINECONE_INDEX_HOST"));
    }

    @Test
    @DisplayName("host gets https:// prefix when missing; no trailing slash")
    void hostNormalization() {
        ReflectionTestUtils.setField(tool, "indexHost", "idx-xyz.svc.us-east1-gcp.pinecone.io/");
        stub200("{}");

        tool.execute(Map.of("operation", "stats"));

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        String url = uri.getValue().toString();
        assertTrue(url.startsWith("https://"), url);
        assertFalse(url.contains(".io//"), url);
    }

    // ===== query =====

    @Test
    @DisplayName("query: formats id + score + metadata for each match")
    void queryFormat() {
        stub200(QUERY_RESPONSE);

        Object out = tool.execute(Map.of(
            "operation", "query",
            "vector", List.of(0.1, 0.2, 0.3),
            "top_k", 2));

        String s = out.toString();
        assertTrue(s.contains("2 match(es)"));
        assertTrue(s.contains("id=`doc-1`"));
        assertTrue(s.contains("score=0.9123"));
        assertTrue(s.contains("title: Hello world"));
        assertTrue(s.contains("source: wiki"));
    }

    @Test
    @DisplayName("query: sends vector, topK, includeMetadata, namespace, filter in body")
    void queryBody() throws Exception {
        stub200(QUERY_RESPONSE);

        String rawFilter = "{\"tag\": {\"$eq\": \"primary\"}}";
        tool.execute(Map.of(
            "operation", "query",
            "vector", List.of(1.0, 2.0, 3.0),
            "top_k", 7,
            "namespace", "users",
            "include_metadata", true,
            "filter", rawFilter));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals(7, body.path("topK").asInt());
        assertTrue(body.path("includeMetadata").asBoolean());
        assertEquals("users", body.path("namespace").asText());
        assertEquals(3, body.path("vector").size());
        assertEquals(1.0, body.path("vector").get(0).asDouble());
        assertEquals("primary", body.path("filter").path("tag").path("$eq").asText());
    }

    @Test
    @DisplayName("query: by id alternative — sends id instead of vector")
    void queryById() throws Exception {
        stub200(QUERY_RESPONSE);

        tool.execute(Map.of("operation", "query", "id", "doc-1", "top_k", 3));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("doc-1", body.path("id").asText());
        assertFalse(body.has("vector"));
    }

    @Test
    @DisplayName("query: missing vector AND id → error, no network call")
    void queryRequiresVectorOrId() {
        Object out = tool.execute(Map.of("operation", "query"));
        assertTrue(out.toString().startsWith("Error"));
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("query: empty matches → clean 'no matches' message")
    void queryNoMatches() {
        stub200("{\"matches\": []}");

        Object out = tool.execute(Map.of("operation", "query", "vector", List.of(0.1)));

        assertTrue(out.toString().contains("no matches"));
    }

    // ===== upsert =====

    @Test
    @DisplayName("upsert: serialises vectors with id+values+metadata, returns confirmation")
    void upsertFormat() throws Exception {
        stub200(UPSERT_RESPONSE);

        Object out = tool.execute(Map.of(
            "operation", "upsert",
            "namespace", "users",
            "vectors", List.of(
                Map.of("id", "v1", "values", List.of(0.1, 0.2), "metadata", Map.of("tag", "a")),
                Map.of("id", "v2", "values", List.of(0.3, 0.4)),
                Map.of("id", "v3", "values", List.of(0.5, 0.6))
            )));

        assertTrue(out.toString().contains("Upserted 3 vector(s)"), out.toString());

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals("users", body.path("namespace").asText());
        assertEquals(3, body.path("vectors").size());
        assertEquals("v1", body.path("vectors").get(0).path("id").asText());
        assertEquals("a", body.path("vectors").get(0).path("metadata").path("tag").asText());
    }

    @Test
    @DisplayName("upsert: entries missing 'id' or 'values' are rejected")
    void upsertMissingFields() {
        Object out1 = tool.execute(Map.of("operation", "upsert",
            "vectors", List.of(Map.of("id", "v1"))));
        assertTrue(out1.toString().startsWith("Error"));

        Object out2 = tool.execute(Map.of("operation", "upsert",
            "vectors", List.of(Map.of("values", List.of(0.1)))));
        assertTrue(out2.toString().startsWith("Error"));
    }

    @Test
    @DisplayName("upsert: enforces 100-item batch cap")
    void upsertBatchCap() {
        List<Map<String, Object>> big = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            big.add(Map.of("id", "v" + i, "values", List.of(0.0)));
        }
        Object out = tool.execute(Map.of("operation", "upsert", "vectors", big));
        assertTrue(out.toString().contains("batch too large"));
    }

    // ===== delete =====

    @Test
    @DisplayName("delete: by ids")
    void deleteByIds() throws Exception {
        stub200("{}");

        Object out = tool.execute(Map.of(
            "operation", "delete",
            "ids", List.of("v1", "v2"),
            "namespace", "users"));

        assertTrue(out.toString().contains("Requested deletion of 2"), out.toString());
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertEquals(2, body.path("ids").size());
        assertEquals("users", body.path("namespace").asText());
    }

    @Test
    @DisplayName("delete: delete_all=true")
    void deleteAll() throws Exception {
        stub200("{}");

        Object out = tool.execute(Map.of(
            "operation", "delete",
            "delete_all", true,
            "namespace", "tmp"));

        assertTrue(out.toString().contains("Deleted all vectors"));
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.POST), entity.capture(), eq(String.class));
        JsonNode body = mapper.readTree((String) entity.getValue().getBody());
        assertTrue(body.path("deleteAll").asBoolean());
    }

    @Test
    @DisplayName("delete: neither ids nor delete_all → error")
    void deleteRequiresEither() {
        Object out = tool.execute(Map.of("operation", "delete"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== stats =====

    @Test
    @DisplayName("stats: renders dimension, total vectors, namespace breakdown")
    void statsFormat() {
        stub200(STATS_RESPONSE);

        Object out = tool.execute(Map.of("operation", "stats"));

        String s = out.toString();
        assertTrue(s.contains("Dimension:       1536"));
        assertTrue(s.contains("Total vectors:   120"));
        assertTrue(s.contains("3.0%"));
        assertTrue(s.contains("(default): 90"));
        assertTrue(s.contains("user-42: 30"));
    }

    // ===== Error surfaces =====

    @Test
    @DisplayName("401 → token rejection hint")
    void unauthorized() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));
        Object out = tool.execute(Map.of("operation", "stats"));
        assertTrue(out.toString().contains("401"));
    }

    @Test
    @DisplayName("unknown operation → helpful error")
    void unknownOp() {
        Object out = tool.execute(Map.of("operation", "archive"));
        assertTrue(out.toString().contains("unknown operation"));
    }

    @SuppressWarnings("unchecked")
    private void stub200(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }
}
