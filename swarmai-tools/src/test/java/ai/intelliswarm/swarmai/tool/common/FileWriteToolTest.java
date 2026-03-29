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
 * Unit tests for FileWriteTool.
 * Tests write modes, security checks, error handling, and edge cases.
 */
@DisplayName("FileWriteTool Tests")
class FileWriteToolTest {

    private FileWriteTool fileWriteTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        fileWriteTool = new FileWriteTool(tempDir);
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("file_write", fileWriteTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = fileWriteTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("Write"), "Description should mention writing");
        assertTrue(description.contains("append"), "Description should mention append mode");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(fileWriteTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = fileWriteTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("path"), "Schema should have 'path' parameter");
        assertTrue(properties.containsKey("content"), "Schema should have 'content' parameter");
        assertTrue(properties.containsKey("mode"), "Schema should have 'mode' parameter");

        // Verify required fields
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(2, required.length);
        assertEquals("path", required[0]);
        assertEquals("content", required[1]);
    }

    // ==================== Overwrite Mode ====================

    @Test
    @DisplayName("Should create a new file in overwrite mode")
    void testOverwriteCreateNewFile() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "output.txt");
        params.put("content", "Hello, World!");
        params.put("mode", "overwrite");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("File written successfully"), "Should confirm success. Got: " + resultStr);
        assertTrue(resultStr.contains("output.txt"), "Should include file path");

        // Verify file was actually written
        Path written = tempDir.resolve("output.txt");
        assertTrue(Files.exists(written), "File should exist on disk");
        assertEquals("Hello, World!", Files.readString(written));
    }

    @Test
    @DisplayName("Should overwrite existing file content")
    void testOverwriteExistingFile() throws IOException {
        Path existing = tempDir.resolve("existing.txt");
        Files.writeString(existing, "Old content");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "existing.txt");
        params.put("content", "New content");
        params.put("mode", "overwrite");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals("New content", Files.readString(existing));
    }

    @Test
    @DisplayName("Should default to overwrite mode when mode not specified")
    void testDefaultModeIsOverwrite() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "default_mode.txt");
        params.put("content", "Default mode content");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals("Default mode content", Files.readString(tempDir.resolve("default_mode.txt")));
    }

    // ==================== Append Mode ====================

    @Test
    @DisplayName("Should append to existing file")
    void testAppendToExistingFile() throws IOException {
        Path existing = tempDir.resolve("append.txt");
        Files.writeString(existing, "Line 1\n");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "append.txt");
        params.put("content", "Line 2\n");
        params.put("mode", "append");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals("Line 1\nLine 2\n", Files.readString(existing));
    }

    @Test
    @DisplayName("Should create new file in append mode if it doesn't exist")
    void testAppendCreatesNewFile() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "new_append.txt");
        params.put("content", "First line");
        params.put("mode", "append");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertTrue(Files.exists(tempDir.resolve("new_append.txt")));
        assertEquals("First line", Files.readString(tempDir.resolve("new_append.txt")));
    }

    // ==================== Create Mode ====================

    @Test
    @DisplayName("Should create a new file in create mode")
    void testCreateNewFile() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "brand_new.txt");
        params.put("content", "Brand new file");
        params.put("mode", "create");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals("Brand new file", Files.readString(tempDir.resolve("brand_new.txt")));
    }

    @Test
    @DisplayName("Should fail in create mode if file already exists")
    void testCreateFailsIfFileExists() throws IOException {
        Path existing = tempDir.resolve("already_exists.txt");
        Files.writeString(existing, "Existing content");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "already_exists.txt");
        params.put("content", "Should fail");
        params.put("mode", "create");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error when file exists in create mode. Got: " + resultStr);
        // Original content should be preserved
        assertEquals("Existing content", Files.readString(existing));
    }

    // ==================== Directory Creation ====================

    @Test
    @DisplayName("Should auto-create parent directories")
    void testAutoCreateParentDirectories() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "sub/dir/deep/file.txt");
        params.put("content", "Deep file content");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        Path written = tempDir.resolve("sub/dir/deep/file.txt");
        assertTrue(Files.exists(written), "File should exist in nested directory");
        assertEquals("Deep file content", Files.readString(written));
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle null path gracefully")
    void testNullPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", null);
        params.put("content", "Some content");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for null path");
    }

    @Test
    @DisplayName("Should handle empty path gracefully")
    void testEmptyPath() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "");
        params.put("content", "Some content");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for empty path");
    }

    @Test
    @DisplayName("Should handle null content gracefully")
    void testNullContent() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "file.txt");
        params.put("content", null);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for null content");
    }

    @Test
    @DisplayName("Should handle invalid mode gracefully")
    void testInvalidMode() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "file.txt");
        params.put("content", "data");
        params.put("mode", "invalid_mode");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should return error for invalid mode. Got: " + resultStr);
        assertTrue(resultStr.contains("invalid_mode"), "Should mention the invalid mode");
    }

    @Test
    @DisplayName("Should write empty content without error")
    void testWriteEmptyContent() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "empty.txt");
        params.put("content", "");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals("", Files.readString(tempDir.resolve("empty.txt")));
    }

    // ==================== Security Tests ====================

    @Test
    @DisplayName("Should prevent path traversal attacks")
    void testPathTraversalPrevention() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "../../etc/crontab");
        params.put("content", "malicious");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"), "Should block path traversal");
        assertTrue(resultStr.contains("denied") || resultStr.contains("traversal"),
            "Should mention access denied. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should deny writing .env files")
    void testDeniedFilePatternEnv() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".env");
        params.put("content", "SECRET=value");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should deny .env file write");
        assertFalse(Files.exists(tempDir.resolve(".env")), ".env file should NOT be created");
    }

    @Test
    @DisplayName("Should deny writing credential files")
    void testDeniedFilePatternCredentials() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "credentials.json");
        params.put("content", "{\"key\": \"secret\"}");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should deny credentials file write");
    }

    @Test
    @DisplayName("Should deny writing executable files")
    void testDeniedExecutableExtensions() {
        for (String ext : List.of(".exe", ".bat", ".sh", ".ps1", ".jar", ".class")) {
            Map<String, Object> params = new HashMap<>();
            params.put("path", "malicious" + ext);
            params.put("content", "dangerous");

            Object result = fileWriteTool.execute(params);

            assertNotNull(result);
            assertTrue(result.toString().contains("Error"),
                "Should deny writing " + ext + " files. Got: " + result);
        }
    }

    @Test
    @DisplayName("Should deny writing private key files")
    void testDeniedPrivateKeyFiles() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "server.pem");
        params.put("content", "-----BEGIN PRIVATE KEY-----");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should deny .pem file write");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle multiline content correctly")
    void testMultilineContent() throws IOException {
        String multiline = "Line 1\nLine 2\nLine 3\n\nLine 5 (after blank)";

        Map<String, Object> params = new HashMap<>();
        params.put("path", "multiline.txt");
        params.put("content", multiline);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals(multiline, Files.readString(tempDir.resolve("multiline.txt")));
        assertTrue(result.toString().contains("**Lines:** 5"), "Should report 5 lines");
    }

    @Test
    @DisplayName("Should handle unicode content correctly")
    void testUnicodeContent() throws IOException {
        String unicode = "Unicode: \u00e9\u00e8\u00ea \u00fc\u00f6\u00e4 \u4e16\u754c \ud83d\ude00";

        Map<String, Object> params = new HashMap<>();
        params.put("path", "unicode.txt");
        params.put("content", unicode);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals(unicode, Files.readString(tempDir.resolve("unicode.txt")));
    }

    @Test
    @DisplayName("Should include metadata in success response")
    void testResponseContainsMetadata() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "meta_test.txt");
        params.put("content", "Some content here");
        params.put("mode", "overwrite");

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Path:**"), "Should include file path");
        assertTrue(resultStr.contains("**Size:**"), "Should include file size");
        assertTrue(resultStr.contains("**Lines:**"), "Should include line count");
        assertTrue(resultStr.contains("**Mode:**"), "Should include write mode");
    }

    @Test
    @DisplayName("Should allow writing JSON files")
    void testWriteJsonFile() throws IOException {
        String json = "{\n  \"name\": \"test\",\n  \"value\": 42\n}";

        Map<String, Object> params = new HashMap<>();
        params.put("path", "data.json");
        params.put("content", json);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals(json, Files.readString(tempDir.resolve("data.json")));
    }

    @Test
    @DisplayName("Should allow writing CSV files")
    void testWriteCsvFile() throws IOException {
        String csv = "Name,Age,City\nAlice,30,Boston\nBob,25,Seattle";

        Map<String, Object> params = new HashMap<>();
        params.put("path", "data.csv");
        params.put("content", csv);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals(csv, Files.readString(tempDir.resolve("data.csv")));
    }

    @Test
    @DisplayName("Should allow writing Markdown files")
    void testWriteMarkdownFile() throws IOException {
        String md = "# Report\n\n## Summary\n\nFindings here.\n\n- Item 1\n- Item 2";

        Map<String, Object> params = new HashMap<>();
        params.put("path", "report.md");
        params.put("content", md);

        Object result = fileWriteTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("File written successfully"));
        assertEquals(md, Files.readString(tempDir.resolve("report.md")));
    }
}
