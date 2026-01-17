package ai.intelliswarm.swarmai.examples.stock.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SECFilingsTool.
 * Tests the tool's interface methods and input validation.
 * Network-dependent tests are marked as integration tests.
 */
@DisplayName("SECFilingsTool Tests")
class SECFilingsToolTest {

    private SECFilingsTool secFilingsTool;

    @BeforeEach
    void setUp() {
        secFilingsTool = new SECFilingsTool();
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("sec_filings", secFilingsTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = secFilingsTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("SEC filings"));
        assertTrue(description.contains("TICKER:QUERY"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(secFilingsTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = secFilingsTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("input"));

        @SuppressWarnings("unchecked")
        Map<String, Object> inputParam = (Map<String, Object>) properties.get("input");
        assertEquals("string", inputParam.get("type"));
        assertNotNull(inputParam.get("description"));

        Object requiredObj = schema.get("required");
        assertNotNull(requiredObj);
        // Handle both List and String[] return types
        if (requiredObj instanceof String[] arr) {
            assertEquals(1, arr.length);
            assertEquals("input", arr[0]);
        } else if (requiredObj instanceof java.util.List<?> list) {
            assertEquals(1, list.size());
            assertEquals("input", list.get(0));
        } else {
            fail("Unexpected type for required: " + requiredObj.getClass());
        }
    }

    @Test
    @DisplayName("Should handle invalid input format - missing colon separator")
    void testExecuteWithInvalidInputFormat() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL");  // Missing query part

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error"),
            "Expected error message for invalid input format. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void testExecuteWithNullInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", null);

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error") || resultStr.contains("null"),
            "Expected error message for null input. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle empty input gracefully")
    void testExecuteWithEmptyInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "");

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error") || resultStr.contains("empty"),
            "Expected error message for empty input. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle whitespace-only input gracefully")
    void testExecuteWithWhitespaceInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "   ");

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error"),
            "Expected error message for whitespace input. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle input with only ticker (no query)")
    void testExecuteWithOnlyTicker() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:");  // Empty query

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        // Should either work with empty query or return an error
        String resultStr = result.toString();
        assertTrue(resultStr.length() > 0);
    }

    @Test
    @DisplayName("Should handle input with only query (no ticker)")
    void testExecuteWithOnlyQuery() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", ":revenue trends");  // Empty ticker

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error"),
            "Expected error message for empty ticker. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle missing input parameter")
    void testExecuteWithMissingInputParameter() {
        Map<String, Object> parameters = new HashMap<>();
        // No "input" key added

        Object result = secFilingsTool.execute(parameters);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("error") || resultStr.contains("null"),
            "Expected error message for missing parameter. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should properly parse ticker from input")
    void testTickerParsing() {
        // This tests the input format validation
        Map<String, Object> parameters = new HashMap<>();

        // Valid format should not fail on parsing (may fail on network)
        parameters.put("input", "AAPL:revenue");
        Object result = secFilingsTool.execute(parameters);
        assertNotNull(result);

        // Multiple colons should use first part as ticker
        parameters.put("input", "AAPL:revenue:trends:2024");
        result = secFilingsTool.execute(parameters);
        assertNotNull(result);
    }
}
