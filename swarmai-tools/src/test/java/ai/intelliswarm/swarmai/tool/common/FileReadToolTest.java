package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileReadTool.
 * Tests the tool's interface methods, format detection, security checks, and error handling.
 */
@DisplayName("FileReadTool Tests")
class FileReadToolTest {

    private FileReadTool fileReadTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Use tempDir as base directory for security-scoped tests
        fileReadTool = new FileReadTool(tempDir);
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("file_read", fileReadTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = fileReadTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("Read file"), "Description should mention reading files");
        assertTrue(description.contains("JSON"), "Description should mention JSON format");
        assertTrue(description.contains("CSV"), "Description should mention CSV format");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(fileReadTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = fileReadTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("path"), "Schema should have 'path' parameter");
        assertTrue(properties.containsKey("format"), "Schema should have 'format' parameter");
        assertTrue(properties.containsKey("offset"), "Schema should have 'offset' parameter");
        assertTrue(properties.containsKey("limit"), "Schema should have 'limit' parameter");

        // Verify path parameter
        @SuppressWarnings("unchecked")
        Map<String, Object> pathParam = (Map<String, Object>) properties.get("path");
        assertEquals("string", pathParam.get("type"));
        assertNotNull(pathParam.get("description"));

        // Verify required fields
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);
        assertEquals("path", required[0]);
    }

    // ==================== Text File Reading ====================

    @Test
    @DisplayName("Should read a plain text file")
    void testReadTextFile() throws IOException {
        Path textFile = tempDir.resolve("sample.txt");
        Files.writeString(textFile, "Hello, World!\nLine two.\nLine three.");

        Map<String, Object> params = Map.of("path", "sample.txt");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Hello, World!"), "Should contain file content");
        assertTrue(resultStr.contains("Line two."), "Should contain all lines");
        assertTrue(resultStr.contains("Line three."), "Should contain last line");
        assertTrue(resultStr.contains("**File:**"), "Should contain metadata header");
        assertTrue(resultStr.contains("**Format:** text"), "Should detect text format");
    }

    @Test
    @DisplayName("Should read a Markdown file")
    void testReadMarkdownFile() throws IOException {
        Path mdFile = tempDir.resolve("readme.md");
        Files.writeString(mdFile, "# Title\n\nSome **bold** text.\n\n- Item 1\n- Item 2");

        Map<String, Object> params = Map.of("path", "readme.md");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("# Title"), "Should preserve markdown");
        assertTrue(resultStr.contains("**Format:** markdown"), "Should detect markdown format");
    }

    // ==================== JSON File Reading ====================

    @Test
    @DisplayName("Should read and pretty-print a JSON file")
    void testReadJsonFile() throws IOException {
        Path jsonFile = tempDir.resolve("data.json");
        Files.writeString(jsonFile, "{\"name\":\"test\",\"value\":42,\"nested\":{\"key\":\"val\"}}");

        Map<String, Object> params = Map.of("path", "data.json");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("\"name\""), "Should contain JSON field");
        assertTrue(resultStr.contains("\"test\""), "Should contain JSON value");
        assertTrue(resultStr.contains("**Format:** json"), "Should detect JSON format");
        // Pretty-printed JSON has newlines
        assertTrue(resultStr.contains("\n"), "Should be pretty-printed");
    }

    @Test
    @DisplayName("Should handle invalid JSON gracefully")
    void testReadInvalidJsonFile() throws IOException {
        Path jsonFile = tempDir.resolve("bad.json");
        Files.writeString(jsonFile, "{ this is not valid json }");

        Map<String, Object> params = Map.of("path", "bad.json");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        // Should fall back to raw text instead of erroring
        assertTrue(resultStr.contains("this is not valid json"), "Should return raw content on invalid JSON");
    }

    // ==================== CSV File Reading ====================

    @Test
    @DisplayName("Should read a CSV file as markdown table")
    void testReadCsvFile() throws IOException {
        Path csvFile = tempDir.resolve("data.csv");
        Files.writeString(csvFile, "Name,Age,City\nAlice,30,Boston\nBob,25,Seattle\nCharlie,35,Denver");

        Map<String, Object> params = Map.of("path", "data.csv");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Format:** csv"), "Should detect CSV format");
        assertTrue(resultStr.contains("| Name"), "Should have markdown table header");
        assertTrue(resultStr.contains("---|"), "Should have markdown table separator");
        assertTrue(resultStr.contains("Alice"), "Should contain data rows");
        assertTrue(resultStr.contains("Bob"), "Should contain all data rows");
    }

    @Test
    @DisplayName("Should read a TSV file as CSV")
    void testReadTsvFile() throws IOException {
        Path tsvFile = tempDir.resolve("data.tsv");
        Files.writeString(tsvFile, "Name\tAge\nAlice\t30\nBob\t25");

        Map<String, Object> params = Map.of("path", "data.tsv");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Alice"), "Should contain TSV data");
        assertTrue(resultStr.contains("Bob"), "Should contain all TSV data");
    }

    // ==================== Line Range Support ====================

    @Test
    @DisplayName("Should read specific line range with offset and limit")
    void testReadWithLineRange() throws IOException {
        Path textFile = tempDir.resolve("lines.txt");
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            content.append("Line ").append(i).append("\n");
        }
        Files.writeString(textFile, content.toString());

        Map<String, Object> params = new HashMap<>();
        params.put("path", "lines.txt");
        params.put("offset", 5);
        params.put("limit", 3);

        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Line 5"), "Should start from offset");
        assertTrue(resultStr.contains("Line 6"), "Should include second line");
        assertTrue(resultStr.contains("Line 7"), "Should include third line");
        assertFalse(resultStr.contains("Line 4"), "Should not include lines before offset");
        assertFalse(resultStr.contains("Line 8"), "Should not exceed limit");
    }

    @Test
    @DisplayName("Should handle offset beyond file length")
    void testReadWithOffsetBeyondFileLength() throws IOException {
        Path textFile = tempDir.resolve("short.txt");
        Files.writeString(textFile, "Line 1\nLine 2");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "short.txt");
        params.put("offset", 100);

        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("empty") || !resultStr.contains("Line 1"),
            "Should indicate empty or not show content for offset beyond file");
    }

    // ==================== Format Detection ====================

    @Test
    @DisplayName("Should auto-detect file format from extension")
    void testAutoFormatDetection() throws IOException {
        // JSON
        Path jsonFile = tempDir.resolve("test.json");
        Files.writeString(jsonFile, "{\"key\": \"value\"}");
        Object jsonResult = fileReadTool.execute(Map.of("path", "test.json"));
        assertTrue(jsonResult.toString().contains("**Format:** json"));

        // CSV
        Path csvFile = tempDir.resolve("test.csv");
        Files.writeString(csvFile, "a,b\n1,2");
        Object csvResult = fileReadTool.execute(Map.of("path", "test.csv"));
        assertTrue(csvResult.toString().contains("**Format:** csv"));

        // YAML
        Path yamlFile = tempDir.resolve("test.yml");
        Files.writeString(yamlFile, "key: value");
        Object yamlResult = fileReadTool.execute(Map.of("path", "test.yml"));
        assertTrue(yamlResult.toString().contains("**Format:** yaml"));

        // XML
        Path xmlFile = tempDir.resolve("test.xml");
        Files.writeString(xmlFile, "<root><item>test</item></root>");
        Object xmlResult = fileReadTool.execute(Map.of("path", "test.xml"));
        assertTrue(xmlResult.toString().contains("**Format:** xml"));

        // Properties
        Path propsFile = tempDir.resolve("test.properties");
        Files.writeString(propsFile, "key=value");
        Object propsResult = fileReadTool.execute(Map.of("path", "test.properties"));
        assertTrue(propsResult.toString().contains("**Format:** properties"));
    }

    @Test
    @DisplayName("Should allow overriding auto-detected format")
    void testFormatOverride() throws IOException {
        Path jsonFile = tempDir.resolve("data.json");
        Files.writeString(jsonFile, "{\"key\": \"value\"}");

        // Force reading as text instead of JSON
        Map<String, Object> params = new HashMap<>();
        params.put("path", "data.json");
        params.put("format", "text");

        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Format:** text"), "Should use overridden format");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle non-existent file gracefully")
    void testReadNonExistentFile() {
        Map<String, Object> params = Map.of("path", "nonexistent.txt");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error for non-existent file");
        assertTrue(resultStr.contains("not found"), "Should indicate file not found");
    }

    @Test
    @DisplayName("Should handle null path gracefully")
    void testReadNullPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", null);

        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error for null path");
    }

    @Test
    @DisplayName("Should handle empty path gracefully")
    void testReadEmptyPath() {
        Map<String, Object> params = Map.of("path", "");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error for empty path");
    }

    @Test
    @DisplayName("Should handle directory path gracefully")
    void testReadDirectory() {
        Map<String, Object> params = Map.of("path", ".");
        // Create a tool without base directory to allow "." to resolve to something
        FileReadTool unboundTool = new FileReadTool();
        Object result = unboundTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") || resultStr.contains("directory"),
            "Should return error for directory path. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should handle empty file")
    void testReadEmptyFile() throws IOException {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.writeString(emptyFile, "");

        Map<String, Object> params = Map.of("path", "empty.txt");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        // Should not error — just return empty or indication
        assertFalse(resultStr.startsWith("Error"), "Should not error on empty file");
    }

    // ==================== Security Tests ====================

    @Test
    @DisplayName("Should prevent path traversal attacks")
    void testPathTraversalPrevention() {
        Map<String, Object> params = Map.of("path", "../../etc/passwd");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should block path traversal");
        assertTrue(resultStr.contains("denied") || resultStr.contains("traversal"),
            "Should mention access denied or traversal. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should deny reading .env files")
    void testDeniedFilePatternEnv() throws IOException {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "SECRET_KEY=abc123");

        Map<String, Object> params = Map.of("path", ".env");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should deny .env file access");
        assertTrue(resultStr.contains("denied"), "Should indicate access denied");
        assertFalse(resultStr.contains("abc123"), "Should NOT leak file contents");
    }

    @Test
    @DisplayName("Should deny reading credential files")
    void testDeniedFilePatternCredentials() throws IOException {
        Path credFile = tempDir.resolve("credentials.json");
        Files.writeString(credFile, "{\"api_key\": \"secret\"}");

        Map<String, Object> params = Map.of("path", "credentials.json");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should deny credentials file access");
        assertFalse(resultStr.contains("secret"), "Should NOT leak file contents");
    }

    @Test
    @DisplayName("Should deny reading private key files")
    void testDeniedFilePatternPrivateKey() throws IOException {
        Path keyFile = tempDir.resolve("server.pem");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----");

        Map<String, Object> params = Map.of("path", "server.pem");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should deny .pem file access");
        assertFalse(resultStr.contains("BEGIN PRIVATE KEY"), "Should NOT leak file contents");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle CSV with quoted fields containing commas")
    void testCsvWithQuotedFields() throws IOException {
        Path csvFile = tempDir.resolve("quoted.csv");
        Files.writeString(csvFile, "Name,Address\n\"Smith, John\",\"123 Main St\"\nDoe,456 Oak Ave");

        Map<String, Object> params = Map.of("path", "quoted.csv");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Smith"), "Should handle quoted CSV fields");
    }

    @Test
    @DisplayName("Should handle file with special characters")
    void testFileWithSpecialCharacters() throws IOException {
        Path textFile = tempDir.resolve("special.txt");
        Files.writeString(textFile, "Unicode: \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4\nSymbols: @#$%^&*()");

        Map<String, Object> params = Map.of("path", "special.txt");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertFalse(resultStr.startsWith("Error"), "Should handle special characters");
    }

    @Test
    @DisplayName("Should include file metadata in response")
    void testResponseContainsMetadata() throws IOException {
        Path textFile = tempDir.resolve("meta.txt");
        Files.writeString(textFile, "Some content here");

        Map<String, Object> params = Map.of("path", "meta.txt");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("**File:**"), "Should include file path in metadata");
        assertTrue(resultStr.contains("**Size:**"), "Should include file size in metadata");
        assertTrue(resultStr.contains("**Format:**"), "Should include format in metadata");
        assertTrue(resultStr.contains("**Last Modified:**"), "Should include last modified in metadata");
    }
}
