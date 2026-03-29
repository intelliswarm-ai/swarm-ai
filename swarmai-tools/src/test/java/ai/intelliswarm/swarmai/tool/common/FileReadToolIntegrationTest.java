package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FileReadTool that read real project files.
 * These tests are tagged as "integration" and can be run separately.
 *
 * To run: mvn test -Dgroups=integration -Dtest=FileReadToolIntegrationTest
 */
@Tag("integration")
@DisplayName("FileReadTool Integration Tests")
class FileReadToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FileReadToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private FileReadTool fileReadTool;

    @BeforeEach
    void setUp() {
        // No base directory restriction — allow reading real project files
        fileReadTool = new FileReadTool();

        try {
            Path outputPath = Paths.get(OUTPUT_DIR);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
                logger.info("Created output directory: {}", outputPath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("Failed to create output directory: {}", e.getMessage());
        }
    }

    private void writeOutputToFile(String testName, String filePath, String output) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String sanitizedPath = filePath.replaceAll("[^a-zA-Z0-9._-]", "_");
            String filename = String.format("FileRead_%s_%s_%s.md", testName, sanitizedPath, timestamp);
            Path outputFilePath = Paths.get(OUTPUT_DIR, filename);

            StringBuilder content = new StringBuilder();
            content.append("# FileReadTool Integration Test Output\n\n");
            content.append(String.format("**Test Name:** %s\n", testName));
            content.append(String.format("**File Path:** %s\n", filePath));
            content.append(String.format("**Timestamp:** %s\n", LocalDateTime.now()));
            content.append(String.format("**Output Length:** %d characters\n\n", output.length()));
            content.append("---\n\n");
            content.append(output);

            try (FileWriter writer = new FileWriter(outputFilePath.toFile())) {
                writer.write(content.toString());
            }

            logger.info("Test output written to: {}", outputFilePath.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write test output to file: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Should read pom.xml from project root")
    void testReadPomXml() {
        logger.info("Testing reading pom.xml...");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "pom.xml");
        params.put("limit", 50);

        Object result = fileReadTool.execute(params);

        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();

        writeOutputToFile("Read_PomXml", "pom.xml", resultStr);

        logger.info("Result length: {} characters", resultStr.length());

        assertFalse(resultStr.contains("Error"),
            "Should not contain error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("**Format:** xml"), "Should detect XML format");
        assertTrue(resultStr.contains("swarmai") || resultStr.contains("intelliswarm") || resultStr.contains("modelVersion"),
            "Should contain project content");
    }

    @Test
    @DisplayName("Should read application.yml")
    void testReadApplicationYml() {
        logger.info("Testing reading application.yml...");

        Map<String, Object> params = Map.of("path", "src/main/resources/application.yml");
        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Read_ApplicationYml", "src/main/resources/application.yml", resultStr);

        assertFalse(resultStr.contains("Error"),
            "Should not contain error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("**Format:** yaml"), "Should detect YAML format");
        assertTrue(resultStr.contains("spring") || resultStr.contains("swarmai"),
            "Should contain application config");
    }

    @Test
    @DisplayName("Should read a Java source file with line range")
    void testReadJavaSourceWithRange() {
        logger.info("Testing reading Java source file with line range...");

        Map<String, Object> params = new HashMap<>();
        params.put("path", "src/main/java/ai/intelliswarm/swarmai/tool/base/BaseTool.java");
        params.put("offset", 1);
        params.put("limit", 20);

        Object result = fileReadTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Read_BaseTool_Lines1to20", "BaseTool.java", resultStr);

        assertFalse(resultStr.contains("Error"),
            "Should not contain error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("package") || resultStr.contains("BaseTool"),
            "Should contain Java source code");
    }

    @Test
    @DisplayName("Should read multiple file formats in sequence")
    void testReadMultipleFormats() {
        logger.info("Testing reading multiple file formats...");

        // Read XML (pom.xml)
        Object xmlResult = fileReadTool.execute(Map.of("path", "pom.xml", "limit", 10));
        assertNotNull(xmlResult);
        assertTrue(xmlResult.toString().contains("**Format:** xml"), "pom.xml should detect as XML");

        // Read YAML
        Object yamlResult = fileReadTool.execute(Map.of("path", "src/main/resources/application.yml", "limit", 10));
        assertNotNull(yamlResult);
        assertTrue(yamlResult.toString().contains("**Format:** yaml"), "application.yml should detect as YAML");

        // Read Java file (text)
        Object javaResult = fileReadTool.execute(Map.of(
            "path", "src/main/java/ai/intelliswarm/swarmai/tool/common/CalculatorTool.java", "limit", 10));
        assertNotNull(javaResult);
        assertFalse(javaResult.toString().contains("Error"), "Should read Java file");

        logger.info("All format reads completed successfully");
    }

    @Test
    @DisplayName("Should handle large file efficiently")
    void testLargeFilePerformance() {
        logger.info("Testing large file reading performance...");

        // Read pom.xml (typically 200+ lines) with a limit
        long startTime = System.currentTimeMillis();

        Map<String, Object> params = new HashMap<>();
        params.put("path", "pom.xml");
        params.put("limit", 100);

        Object result = fileReadTool.execute(params);
        long duration = System.currentTimeMillis() - startTime;

        logger.info("File read took {} ms", duration);

        assertNotNull(result);
        assertFalse(result.toString().contains("Error"), "Should not error on large file");
        assertTrue(duration < 5000, "File read should complete within 5 seconds. Actual: " + duration + " ms");
    }

    @Test
    @DisplayName("Should block .env file access even without base directory")
    void testSecurityDenyPatterns() {
        logger.info("Testing security deny patterns...");

        Object result = fileReadTool.execute(Map.of("path", ".env"));

        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error") && resultStr.contains("denied"),
            "Should deny .env access even without base directory. Got: " + resultStr);
    }

    @Test
    @DisplayName("Should include metadata in all responses")
    void testMetadataPresence() {
        logger.info("Testing metadata presence...");

        Object result = fileReadTool.execute(Map.of("path", "pom.xml", "limit", 5));

        assertNotNull(result);
        String resultStr = result.toString();

        assertTrue(resultStr.contains("**File:**"), "Should have file path");
        assertTrue(resultStr.contains("**Size:**"), "Should have file size");
        assertTrue(resultStr.contains("**Format:**"), "Should have format");
        assertTrue(resultStr.contains("**Last Modified:**"), "Should have last modified");
        assertTrue(resultStr.contains("---"), "Should have separator between metadata and content");
    }
}
