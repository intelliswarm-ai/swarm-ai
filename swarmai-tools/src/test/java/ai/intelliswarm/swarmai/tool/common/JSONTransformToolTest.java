package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JSONTransformTool.
 * Tests all operations: parse, extract, keys, flatten, to_csv, count.
 */
@DisplayName("JSONTransformTool Tests")
class JSONTransformToolTest {

    private JSONTransformTool jsonTransformTool;

    private static final String SAMPLE_JSON = """
        {
          "name": "SwarmAI",
          "version": "1.0",
          "features": ["agents", "tools", "workflows"],
          "config": {
            "maxRpm": 30,
            "verbose": true,
            "models": {
              "default": "gpt-4o-mini",
              "fallback": "llama3.2"
            }
          },
          "users": [
            {"id": 1, "name": "Alice", "role": "admin"},
            {"id": 2, "name": "Bob", "role": "user"},
            {"id": 3, "name": "Charlie", "role": "user"}
          ]
        }
        """;

    @BeforeEach
    void setUp() {
        jsonTransformTool = new JSONTransformTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("json_transform", jsonTransformTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = jsonTransformTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("parse"), "Should mention parse");
        assertTrue(description.contains("extract"), "Should mention extract");
        assertTrue(description.contains("flatten"), "Should mention flatten");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(jsonTransformTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = jsonTransformTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("json"), "Should have 'json' parameter");
        assertTrue(properties.containsKey("operation"), "Should have 'operation' parameter");
        assertTrue(properties.containsKey("path"), "Should have 'path' parameter");

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("json", required[0]);
    }

    // ==================== Parse Operation ====================

    @Test
    @DisplayName("Should parse and pretty-print JSON")
    void testParse() {
        Object result = jsonTransformTool.execute(Map.of("json", SAMPLE_JSON));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("JSON Structure"), "Should include structure info");
        assertTrue(resultStr.contains("Object"), "Should identify as Object");
        assertTrue(resultStr.contains("Formatted JSON"), "Should include formatted output");
        assertTrue(resultStr.contains("SwarmAI"), "Should contain data");
    }

    @Test
    @DisplayName("Should parse JSON array")
    void testParseArray() {
        Object result = jsonTransformTool.execute(Map.of("json", "[1, 2, 3]"));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("Array"), "Should identify as Array");
        assertTrue(resultStr.contains("3"), "Should show element count");
    }

    @Test
    @DisplayName("Should default to parse operation")
    void testDefaultOperation() {
        Object result = jsonTransformTool.execute(Map.of("json", "{\"key\": \"value\"}"));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("JSON Structure"), "Should default to parse");
    }

    // ==================== Extract Operation ====================

    @Test
    @DisplayName("Should extract top-level field")
    void testExtractTopLevel() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "name");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("SwarmAI"), "Should extract name value");
        assertTrue(resultStr.contains("string"), "Should show type as string");
    }

    @Test
    @DisplayName("Should extract nested field with dot notation")
    void testExtractNested() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "config.models.default");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("gpt-4o-mini"), "Should extract nested value");
    }

    @Test
    @DisplayName("Should extract array element by index")
    void testExtractArrayElement() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "users[1].name");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("Bob"), "Should extract users[1].name = Bob");
    }

    @Test
    @DisplayName("Should extract entire array")
    void testExtractArray() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "features");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("agents"), "Should contain array elements");
        assertTrue(resultStr.contains("array"), "Should identify as array type");
    }

    @Test
    @DisplayName("Should handle non-existent path gracefully")
    void testExtractNonExistentPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "nonexistent.field");

        Object result = jsonTransformTool.execute(params);

        assertTrue(result.toString().contains("Error"), "Should return error for non-existent path");
        assertTrue(result.toString().contains("not found"), "Should mention path not found");
    }

    @Test
    @DisplayName("Should require path for extract")
    void testExtractWithoutPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");

        Object result = jsonTransformTool.execute(params);

        assertTrue(result.toString().contains("Error"), "Should require path parameter");
    }

    // ==================== Keys Operation ====================

    @Test
    @DisplayName("Should list top-level keys")
    void testKeysTopLevel() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "keys");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("name"), "Should list 'name' key");
        assertTrue(resultStr.contains("version"), "Should list 'version' key");
        assertTrue(resultStr.contains("features"), "Should list 'features' key");
        assertTrue(resultStr.contains("config"), "Should list 'config' key");
        assertTrue(resultStr.contains("users"), "Should list 'users' key");
        assertTrue(resultStr.contains("5 keys"), "Should show total count");
    }

    @Test
    @DisplayName("Should list keys at nested path")
    void testKeysNested() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "keys");
        params.put("path", "config");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("maxRpm"), "Should list config keys");
        assertTrue(resultStr.contains("verbose"), "Should list verbose key");
        assertTrue(resultStr.contains("models"), "Should list models key");
    }

    // ==================== Flatten Operation ====================

    @Test
    @DisplayName("Should flatten nested JSON to dot-notation")
    void testFlatten() {
        String simpleJson = "{\"a\": {\"b\": {\"c\": 42}}, \"x\": [1, 2]}";

        Map<String, Object> params = new HashMap<>();
        params.put("json", simpleJson);
        params.put("operation", "flatten");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("a.b.c"), "Should flatten to dot notation");
        assertTrue(resultStr.contains("42"), "Should include leaf values");
        assertTrue(resultStr.contains("x[0]"), "Should flatten arrays with indices");
        assertTrue(resultStr.contains("x[1]"), "Should flatten all array elements");
    }

    @Test
    @DisplayName("Should flatten with table format")
    void testFlattenTableFormat() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", "{\"key\": \"value\", \"nested\": {\"inner\": true}}");
        params.put("operation", "flatten");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("| Path |"), "Should have table header");
        assertTrue(resultStr.contains("| Value |"), "Should have value column");
    }

    // ==================== To CSV Operation ====================

    @Test
    @DisplayName("Should convert JSON array to CSV")
    void testToCsv() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "to_csv");
        params.put("path", "users");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("CSV Output"), "Should have CSV header");
        assertTrue(resultStr.contains("3 rows"), "Should indicate 3 rows");
        assertTrue(resultStr.contains("id"), "Should have column headers");
        assertTrue(resultStr.contains("name"), "Should have name column");
        assertTrue(resultStr.contains("Alice"), "Should contain data");
        assertTrue(resultStr.contains("Bob"), "Should contain all rows");
    }

    @Test
    @DisplayName("Should convert top-level array to CSV")
    void testToCsvTopLevel() {
        String arrayJson = "[{\"a\": 1, \"b\": \"x\"}, {\"a\": 2, \"b\": \"y\"}]";

        Map<String, Object> params = new HashMap<>();
        params.put("json", arrayJson);
        params.put("operation", "to_csv");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("2 rows"), "Should have 2 rows");
        assertTrue(resultStr.contains("a,b"), "Should have headers");
    }

    @Test
    @DisplayName("Should handle to_csv with non-array input")
    void testToCsvNonArray() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", "{\"key\": \"value\"}");
        params.put("operation", "to_csv");

        Object result = jsonTransformTool.execute(params);

        assertTrue(result.toString().contains("Error"), "Should error on non-array input");
    }

    // ==================== Count Operation ====================

    @Test
    @DisplayName("Should count array elements")
    void testCountArray() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "count");
        params.put("path", "users");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("3"), "Should count 3 users");
        assertTrue(resultStr.contains("array"), "Should identify as array");
    }

    @Test
    @DisplayName("Should count object keys")
    void testCountObjectKeys() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "count");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("5"), "Should count 5 top-level keys");
        assertTrue(resultStr.contains("object"), "Should identify as object");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle null JSON gracefully")
    void testNullJson() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", null);

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null JSON");
    }

    @Test
    @DisplayName("Should handle empty JSON gracefully")
    void testEmptyJson() {
        Object result = jsonTransformTool.execute(Map.of("json", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty JSON");
    }

    @Test
    @DisplayName("Should handle invalid JSON gracefully")
    void testInvalidJson() {
        Object result = jsonTransformTool.execute(Map.of("json", "not json at all"));
        assertTrue(result.toString().contains("Error"), "Should error on invalid JSON");
        assertTrue(result.toString().contains("Invalid JSON"), "Should mention invalid JSON");
    }

    @Test
    @DisplayName("Should handle invalid operation gracefully")
    void testInvalidOperation() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", "{}");
        params.put("operation", "invalid_op");

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on invalid operation");
        assertTrue(result.toString().contains("invalid_op"), "Should mention the invalid operation");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle deeply nested extraction")
    void testDeeplyNestedExtraction() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "config.models.fallback");

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("llama3.2"), "Should extract deeply nested value");
    }

    @Test
    @DisplayName("Should handle array index out of bounds")
    void testArrayIndexOutOfBounds() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "users[99]");

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on out-of-bounds index");
    }

    @Test
    @DisplayName("Should handle CSV with values containing commas")
    void testCsvWithCommas() {
        String json = "[{\"name\": \"Smith, John\", \"city\": \"New York\"}]";

        Map<String, Object> params = new HashMap<>();
        params.put("json", json);
        params.put("operation", "to_csv");

        Object result = jsonTransformTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("\"Smith, John\""), "Should quote values with commas");
    }

    @Test
    @DisplayName("Should handle empty object")
    void testEmptyObject() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", "{}");
        params.put("operation", "keys");

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("0 keys"), "Should count 0 keys for empty object");
    }

    @Test
    @DisplayName("Should handle boolean and null values in extract")
    void testExtractBooleanAndNull() {
        Map<String, Object> params = new HashMap<>();
        params.put("json", SAMPLE_JSON);
        params.put("operation", "extract");
        params.put("path", "config.verbose");

        Object result = jsonTransformTool.execute(params);
        assertTrue(result.toString().contains("true"), "Should extract boolean value");
        assertTrue(result.toString().contains("boolean"), "Should identify boolean type");
    }
}
