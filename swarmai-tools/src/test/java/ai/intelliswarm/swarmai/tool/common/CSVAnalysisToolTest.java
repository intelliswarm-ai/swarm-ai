package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CSVAnalysisTool Tests")
class CSVAnalysisToolTest {

    private CSVAnalysisTool csvTool;

    @TempDir
    Path tempDir;

    private static final String SAMPLE_CSV = "Name,Age,City,Salary\nAlice,30,Boston,75000\nBob,25,Seattle,65000\nCharlie,35,Denver,85000\nDiana,28,Boston,70000\nEve,32,Seattle,90000";

    @BeforeEach
    void setUp() {
        csvTool = new CSVAnalysisTool(tempDir);
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("csv_analysis", csvTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(csvTool.getDescription());
        assertTrue(csvTool.getDescription().contains("CSV"));
        assertTrue(csvTool.getDescription().contains("stats"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(csvTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = csvTool.getParameterSchema();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("path"));
        assertTrue(properties.containsKey("csv_content"));
        assertTrue(properties.containsKey("operation"));
        assertTrue(properties.containsKey("column"));
    }

    // ==================== Describe ====================

    @Test
    @DisplayName("Should describe CSV from inline content")
    void testDescribeInline() {
        Object result = csvTool.execute(Map.of("csv_content", SAMPLE_CSV));

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("**Rows:** 5"), "Should count 5 rows");
        assertTrue(r.contains("**Columns:** 4"), "Should count 4 columns");
        assertTrue(r.contains("Name"), "Should list Name column");
        assertTrue(r.contains("Age"), "Should list Age column");
        assertTrue(r.contains("numeric"), "Should detect numeric columns");
    }

    @Test
    @DisplayName("Should describe CSV from file")
    void testDescribeFromFile() throws IOException {
        Path csvFile = tempDir.resolve("data.csv");
        Files.writeString(csvFile, SAMPLE_CSV);

        Object result = csvTool.execute(Map.of("path", "data.csv"));

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("**Rows:** 5"), "Should count rows");
    }

    @Test
    @DisplayName("Should default to describe operation")
    void testDefaultOperation() {
        Object result = csvTool.execute(Map.of("csv_content", SAMPLE_CSV));
        assertTrue(result.toString().contains("CSV Overview"), "Should default to describe");
    }

    // ==================== Stats ====================

    @Test
    @DisplayName("Should compute stats for all columns")
    void testStatsAll() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "stats");

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Age"), "Should include Age stats");
        assertTrue(r.contains("Salary"), "Should include Salary stats");
        assertTrue(r.contains("Min"), "Should show min");
        assertTrue(r.contains("Max"), "Should show max");
        assertTrue(r.contains("Mean"), "Should show mean");
    }

    @Test
    @DisplayName("Should compute stats for specific column")
    void testStatsSpecificColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "stats");
        params.put("column", "Salary");

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertTrue(r.contains("Salary"), "Should include Salary");
        assertTrue(r.contains("65000") || r.contains("Min"), "Should show salary stats");
        assertTrue(r.contains("90000") || r.contains("Max"), "Should show max salary");
    }

    @Test
    @DisplayName("Should handle non-existent column in stats")
    void testStatsInvalidColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "stats");
        params.put("column", "NonExistent");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("not found"), "Should indicate column not found");
    }

    // ==================== Head ====================

    @Test
    @DisplayName("Should show first N rows")
    void testHead() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "head");
        params.put("rows", 3);

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error");
        assertTrue(r.contains("Alice"), "Should include first row");
        assertTrue(r.contains("Bob"), "Should include second row");
        assertTrue(r.contains("Charlie"), "Should include third row");
        assertTrue(r.contains("First 3"), "Should indicate 3 rows");
    }

    @Test
    @DisplayName("Should default to 10 rows for head")
    void testHeadDefault() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "head");

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertTrue(r.contains("First 5 of 5"), "Should show all 5 rows when less than default");
    }

    // ==================== Filter ====================

    @Test
    @DisplayName("Should filter by column value")
    void testFilter() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "filter");
        params.put("column", "City");
        params.put("value", "Boston");

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Alice"), "Should match Alice (Boston)");
        assertTrue(r.contains("Diana"), "Should match Diana (Boston)");
        assertFalse(r.contains("Bob"), "Should not match Bob (Seattle)");
        assertTrue(r.contains("**Matches:** 2"), "Should find 2 matches");
    }

    @Test
    @DisplayName("Should filter case-insensitively")
    void testFilterCaseInsensitive() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "filter");
        params.put("column", "City");
        params.put("value", "boston");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("Alice"), "Should match case-insensitively");
    }

    @Test
    @DisplayName("Should handle filter with no matches")
    void testFilterNoMatches() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "filter");
        params.put("column", "City");
        params.put("value", "Nowhere");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("No matching rows"), "Should indicate no matches");
    }

    @Test
    @DisplayName("Should require column for filter")
    void testFilterMissingColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "filter");
        params.put("value", "test");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error without column");
    }

    // ==================== Count ====================

    @Test
    @DisplayName("Should count by column")
    void testCount() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "count");
        params.put("column", "City");

        Object result = csvTool.execute(params);

        String r = result.toString();
        assertFalse(r.contains("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Boston") && r.contains("2"), "Boston should have count 2");
        assertTrue(r.contains("Seattle") && r.contains("2"), "Seattle should have count 2");
        assertTrue(r.contains("Denver") && r.contains("1"), "Denver should have count 1");
        assertTrue(r.contains("**Total groups:** 3"), "Should have 3 groups");
    }

    @Test
    @DisplayName("Should require column for count")
    void testCountMissingColumn() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "count");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error without column");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle missing path and content")
    void testMissingInput() {
        Object result = csvTool.execute(Map.of("operation", "describe"));
        assertTrue(result.toString().contains("Error"), "Should error without input");
    }

    @Test
    @DisplayName("Should handle invalid operation")
    void testInvalidOperation() {
        Map<String, Object> params = new HashMap<>();
        params.put("csv_content", SAMPLE_CSV);
        params.put("operation", "invalid");

        Object result = csvTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on invalid operation");
    }

    @Test
    @DisplayName("Should handle non-existent file")
    void testNonExistentFile() {
        Object result = csvTool.execute(Map.of("path", "missing.csv"));
        assertTrue(result.toString().contains("Error"), "Should error on missing file");
    }

    @Test
    @DisplayName("Should handle empty CSV")
    void testEmptyCSV() {
        Object result = csvTool.execute(Map.of("csv_content", "Name,Age\n"));
        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not hard-error on empty CSV");
        assertTrue(r.contains("**Rows:** 0") || r.contains("0"), "Should show 0 rows");
    }

    @Test
    @DisplayName("Should prevent path traversal")
    void testPathTraversal() {
        Object result = csvTool.execute(Map.of("path", "../../etc/passwd"));
        assertTrue(result.toString().contains("Error"), "Should block traversal");
    }
}
