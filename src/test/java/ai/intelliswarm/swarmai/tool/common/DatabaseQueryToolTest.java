package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DatabaseQueryTool Tests")
class DatabaseQueryToolTest {

    private DatabaseQueryTool dbTool;

    @BeforeEach
    void setUp() {
        // No DataSource configured — tests validation and safety without real DB
        dbTool = new DatabaseQueryTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("database_query", dbTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(dbTool.getDescription());
        assertTrue(dbTool.getDescription().contains("SQL"));
        assertTrue(dbTool.getDescription().contains("SELECT"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(dbTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = dbTool.getParameterSchema();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("query"));
        assertTrue(properties.containsKey("max_rows"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("query", required[0]);
    }

    // ==================== No Database Configured ====================

    @Test
    @DisplayName("Should return clear error when no database configured")
    void testNoDatabaseConfigured() {
        Object result = dbTool.execute(Map.of("query", "SELECT 1"));

        String r = result.toString();
        assertTrue(r.contains("Error"), "Should indicate error");
        assertTrue(r.contains("No database configured"), "Should explain no DB. Got: " + r);
    }

    // ==================== Query Validation ====================

    @Test
    @DisplayName("Should handle null query")
    void testNullQuery() {
        Map<String, Object> params = new HashMap<>();
        params.put("query", null);

        Object result = dbTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null query");
    }

    @Test
    @DisplayName("Should handle empty query")
    void testEmptyQuery() {
        Object result = dbTool.execute(Map.of("query", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty query");
    }

    // ==================== SQL Safety Tests ====================

    @Test
    @DisplayName("Should block INSERT statements")
    void testBlockInsert() {
        Object result = dbTool.execute(Map.of("query", "INSERT INTO users VALUES (1, 'evil')"));
        assertTrue(result.toString().contains("Error"), "Should block INSERT");
    }

    @Test
    @DisplayName("Should block UPDATE statements")
    void testBlockUpdate() {
        Object result = dbTool.execute(Map.of("query", "UPDATE users SET name='hacked'"));
        assertTrue(result.toString().contains("Error"), "Should block UPDATE");
    }

    @Test
    @DisplayName("Should block DELETE statements")
    void testBlockDelete() {
        Object result = dbTool.execute(Map.of("query", "DELETE FROM users"));
        assertTrue(result.toString().contains("Error"), "Should block DELETE");
    }

    @Test
    @DisplayName("Should block DROP statements")
    void testBlockDrop() {
        Object result = dbTool.execute(Map.of("query", "DROP TABLE users"));
        assertTrue(result.toString().contains("Error"), "Should block DROP");
    }

    @Test
    @DisplayName("Should block ALTER statements")
    void testBlockAlter() {
        Object result = dbTool.execute(Map.of("query", "ALTER TABLE users ADD COLUMN evil TEXT"));
        assertTrue(result.toString().contains("Error"), "Should block ALTER");
    }

    @Test
    @DisplayName("Should block CREATE statements")
    void testBlockCreate() {
        Object result = dbTool.execute(Map.of("query", "CREATE TABLE evil (id INT)"));
        assertTrue(result.toString().contains("Error"), "Should block CREATE");
    }

    @Test
    @DisplayName("Should block TRUNCATE statements")
    void testBlockTruncate() {
        Object result = dbTool.execute(Map.of("query", "TRUNCATE TABLE users"));
        assertTrue(result.toString().contains("Error"), "Should block TRUNCATE");
    }

    @Test
    @DisplayName("Should block GRANT statements")
    void testBlockGrant() {
        Object result = dbTool.execute(Map.of("query", "GRANT ALL ON users TO evil"));
        assertTrue(result.toString().contains("Error"), "Should block GRANT");
    }

    @Test
    @DisplayName("Should block SQL comments (injection vector)")
    void testBlockComments() {
        Object result = dbTool.execute(Map.of("query", "SELECT 1 -- drop table"));
        assertTrue(result.toString().contains("Error"), "Should block SQL comments");

        Object result2 = dbTool.execute(Map.of("query", "SELECT 1 /* evil */"));
        assertTrue(result2.toString().contains("Error"), "Should block block comments");
    }

    @Test
    @DisplayName("Should allow SELECT queries")
    void testAllowSelect() {
        // Will fail on "no database" but should NOT fail on safety check
        Object result = dbTool.execute(Map.of("query", "SELECT * FROM users WHERE id = 1"));
        assertTrue(result.toString().contains("No database configured"),
            "Should only fail on DB, not on safety. Got: " + result);
    }

    @Test
    @DisplayName("Should allow WITH (CTE) queries")
    void testAllowCTE() {
        Object result = dbTool.execute(Map.of("query", "WITH cte AS (SELECT 1 AS id) SELECT * FROM cte"));
        assertTrue(result.toString().contains("No database configured"),
            "Should allow WITH/CTE. Got: " + result);
    }

    @Test
    @DisplayName("Should allow SHOW queries")
    void testAllowShow() {
        Object result = dbTool.execute(Map.of("query", "SHOW TABLES"));
        assertTrue(result.toString().contains("No database configured"),
            "Should allow SHOW. Got: " + result);
    }

    @Test
    @DisplayName("Should allow DESCRIBE queries")
    void testAllowDescribe() {
        Object result = dbTool.execute(Map.of("query", "DESCRIBE users"));
        assertTrue(result.toString().contains("No database configured"),
            "Should allow DESCRIBE. Got: " + result);
    }

    @Test
    @DisplayName("Should allow EXPLAIN queries")
    void testAllowExplain() {
        Object result = dbTool.execute(Map.of("query", "EXPLAIN SELECT * FROM users"));
        assertTrue(result.toString().contains("No database configured"),
            "Should allow EXPLAIN. Got: " + result);
    }
}
