package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SemanticSearchTool Tests")
class SemanticSearchToolTest {

    private SemanticSearchTool searchTool;

    @BeforeEach
    void setUp() {
        // No VectorStore configured — tests validation without real vector DB
        searchTool = new SemanticSearchTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("semantic_search", searchTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(searchTool.getDescription());
        assertTrue(searchTool.getDescription().contains("semantic"));
        assertTrue(searchTool.getDescription().contains("vector"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(searchTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = searchTool.getParameterSchema();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("top_k"));
        assertTrue(properties.containsKey("threshold"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("query", required[0]);
    }

    @Test
    @DisplayName("Should have high max response length")
    void testMaxResponseLength() {
        assertTrue(searchTool.getMaxResponseLength() >= 10000);
    }

    // ==================== No Vector Store ====================

    @Test
    @DisplayName("Should return clear error when no vector store configured")
    void testNoVectorStore() {
        Object result = searchTool.execute(Map.of("query", "test query"));

        String r = result.toString();
        assertTrue(r.contains("Error"), "Should indicate error");
        assertTrue(r.contains("No vector store configured"), "Should explain no vector store. Got: " + r);
    }

    // ==================== Validation ====================

    @Test
    @DisplayName("Should handle null query")
    void testNullQuery() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", null);

        Object result = searchTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null query");
    }

    @Test
    @DisplayName("Should handle empty query")
    void testEmptyQuery() {
        Object result = searchTool.execute(Map.of("query", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty query");
    }

    @Test
    @DisplayName("Should accept all optional parameters without validation error")
    void testAllParameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "test query");
        params.put("top_k", 10);
        params.put("threshold", 0.5);

        Object result = searchTool.execute(params);
        // Should fail on "no vector store", not on parameter validation
        assertTrue(result.toString().contains("No vector store configured"),
            "Should only fail on missing store, not params. Got: " + result);
    }

    @Test
    @DisplayName("Should cap top_k at maximum")
    void testTopKCapped() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", "test");
        params.put("top_k", 9999);

        Object result = searchTool.execute(params);
        // Should not error on top_k validation — it's capped internally
        assertTrue(result.toString().contains("No vector store configured"),
            "Should cap top_k silently");
    }
}
