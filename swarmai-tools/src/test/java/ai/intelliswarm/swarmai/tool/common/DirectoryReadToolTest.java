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

/**
 * Unit tests for DirectoryReadTool.
 * Tests listing, glob filtering, recursion, security, and error handling.
 */
@DisplayName("DirectoryReadTool Tests")
class DirectoryReadToolTest {

    private DirectoryReadTool directoryReadTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        directoryReadTool = new DirectoryReadTool(tempDir);

        // Create test directory structure:
        // tempDir/
        //   file1.txt
        //   file2.json
        //   data.csv
        //   sub/
        //     nested.txt
        //     deep/
        //       config.yml
        //       report.json
        Files.writeString(tempDir.resolve("file1.txt"), "text content");
        Files.writeString(tempDir.resolve("file2.json"), "{\"key\": \"value\"}");
        Files.writeString(tempDir.resolve("data.csv"), "a,b\n1,2");
        Files.createDirectories(tempDir.resolve("sub/deep"));
        Files.writeString(tempDir.resolve("sub/nested.txt"), "nested text");
        Files.writeString(tempDir.resolve("sub/deep/config.yml"), "key: value");
        Files.writeString(tempDir.resolve("sub/deep/report.json"), "{\"report\": true}");
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("directory_read", directoryReadTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = directoryReadTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("List"), "Description should mention listing");
        assertTrue(description.contains("glob"), "Description should mention glob patterns");
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(directoryReadTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = directoryReadTool.getParameterSchema();

        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("path"), "Should have 'path' parameter");
        assertTrue(properties.containsKey("pattern"), "Should have 'pattern' parameter");
        assertTrue(properties.containsKey("recursive"), "Should have 'recursive' parameter");
        assertTrue(properties.containsKey("max_results"), "Should have 'max_results' parameter");
    }

    // ==================== Basic Listing ====================

    @Test
    @DisplayName("Should list all files and directories in current directory")
    void testListAllFiles() {
        Map<String, Object> params = Map.of("path", ".");
        Object result = directoryReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error. Got: " + resultStr);
        assertTrue(resultStr.contains("file1.txt"), "Should list file1.txt");
        assertTrue(resultStr.contains("file2.json"), "Should list file2.json");
        assertTrue(resultStr.contains("data.csv"), "Should list data.csv");
        assertTrue(resultStr.contains("sub"), "Should list sub directory");
        assertTrue(resultStr.contains("DIR"), "Should show directory type");
        assertTrue(resultStr.contains("FILE"), "Should show file type");
    }

    @Test
    @DisplayName("Should show metadata table with columns")
    void testMetadataTable() {
        Object result = directoryReadTool.execute(Map.of("path", "."));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("| Name |"), "Should have Name column");
        assertTrue(resultStr.contains("| Type |"), "Should have Type column");
        assertTrue(resultStr.contains("| Size |"), "Should have Size column");
        assertTrue(resultStr.contains("| Modified |"), "Should have Modified column");
        assertTrue(resultStr.contains("|------"), "Should have table separator");
    }

    @Test
    @DisplayName("Should sort directories before files")
    void testDirectoriesFirst() {
        Object result = directoryReadTool.execute(Map.of("path", "."));

        String resultStr = result.toString();
        int dirIndex = resultStr.indexOf("| sub");
        int fileIndex = resultStr.indexOf("| data.csv");
        assertTrue(dirIndex < fileIndex, "Directories should appear before files");
    }

    // ==================== Glob Patterns ====================

    @Test
    @DisplayName("Should filter by *.txt glob pattern")
    void testGlobPatternTxt() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("pattern", "*.txt");

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("file1.txt"), "Should match file1.txt");
        assertFalse(resultStr.contains("file2.json"), "Should not match .json files");
        assertFalse(resultStr.contains("data.csv"), "Should not match .csv files");
    }

    @Test
    @DisplayName("Should filter by *.json glob pattern")
    void testGlobPatternJson() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("pattern", "*.json");

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("file2.json"), "Should match file2.json");
        assertFalse(resultStr.contains("file1.txt"), "Should not match .txt files");
    }

    @Test
    @DisplayName("Should filter by multi-extension glob pattern")
    void testGlobPatternMultiExtension() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("pattern", "*.{json,csv}");

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("file2.json"), "Should match .json");
        assertTrue(resultStr.contains("data.csv"), "Should match .csv");
        assertFalse(resultStr.contains("file1.txt"), "Should not match .txt");
    }

    // ==================== Recursive Search ====================

    @Test
    @DisplayName("Should search recursively when recursive=true")
    void testRecursiveSearch() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("recursive", true);

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("file1.txt"), "Should list top-level files");
        assertTrue(resultStr.contains("nested.txt"), "Should list nested files");
        assertTrue(resultStr.contains("config.yml"), "Should list deeply nested files");
    }

    @Test
    @DisplayName("Should auto-enable recursion for ** patterns")
    void testAutoRecursionForGlobStar() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("pattern", "**/*.json");

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        // Should find JSON files in subdirectories
        assertTrue(resultStr.contains("report.json"), "Should find deeply nested .json files");
    }

    @Test
    @DisplayName("Should not include nested files when not recursive")
    void testNonRecursiveListing() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("recursive", false);

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("file1.txt"), "Should list top-level files");
        assertFalse(resultStr.contains("nested.txt"), "Should NOT list nested files");
        assertFalse(resultStr.contains("config.yml"), "Should NOT list deeply nested files");
    }

    // ==================== Subdirectory Listing ====================

    @Test
    @DisplayName("Should list contents of a subdirectory")
    void testListSubdirectory() {
        Map<String, Object> params = Map.of("path", "sub");
        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error");
        assertTrue(resultStr.contains("nested.txt"), "Should list files in subdirectory");
        assertTrue(resultStr.contains("deep"), "Should list deep subdirectory");
    }

    // ==================== Max Results ====================

    @Test
    @DisplayName("Should respect max_results limit")
    void testMaxResultsLimit() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("max_results", 2);

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("limited to 2"), "Should indicate results are limited");
    }

    // ==================== Empty Directory ====================

    @Test
    @DisplayName("Should handle empty directory")
    void testEmptyDirectory() throws IOException {
        Path emptyDir = tempDir.resolve("empty_dir");
        Files.createDirectory(emptyDir);

        Map<String, Object> params = Map.of("path", "empty_dir");
        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error on empty dir");
        assertTrue(resultStr.contains("no matching files") || resultStr.contains("Total entries:** 0"),
            "Should indicate empty directory");
    }

    @Test
    @DisplayName("Should handle no matches for glob pattern")
    void testNoMatchingFiles() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", ".");
        params.put("pattern", "*.xyz");

        Object result = directoryReadTool.execute(params);

        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), "Should not error on no matches");
        assertTrue(resultStr.contains("no matching files") || resultStr.contains("Total entries:** 0"),
            "Should indicate no matches found");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle non-existent directory")
    void testNonExistentDirectory() {
        Map<String, Object> params = Map.of("path", "nonexistent_dir");
        Object result = directoryReadTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for non-existent directory");
        assertTrue(result.toString().contains("not found"), "Should indicate not found");
    }

    @Test
    @DisplayName("Should handle file path instead of directory")
    void testFileInsteadOfDirectory() {
        Map<String, Object> params = Map.of("path", "file1.txt");
        Object result = directoryReadTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should return error for file path");
        assertTrue(result.toString().contains("Not a directory"), "Should indicate not a directory");
    }

    @Test
    @DisplayName("Should prevent path traversal")
    void testPathTraversal() {
        Map<String, Object> params = Map.of("path", "../../etc");
        Object result = directoryReadTool.execute(params);

        assertNotNull(result);
        assertTrue(result.toString().contains("Error"), "Should block path traversal");
        assertTrue(result.toString().contains("denied") || result.toString().contains("traversal"),
            "Should mention access denied");
    }

    @Test
    @DisplayName("Should include header metadata in response")
    void testResponseMetadata() {
        Object result = directoryReadTool.execute(Map.of("path", "."));

        String resultStr = result.toString();
        assertTrue(resultStr.contains("**Directory:**"), "Should include directory path");
        assertTrue(resultStr.contains("**Total entries:**"), "Should include entry count");
        assertTrue(resultStr.contains("**Recursive:**"), "Should include recursive flag");
    }
}
