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
 * Integration tests for HttpRequestTool that make real HTTP requests.
 *
 * To run: mvn test -Dgroups=integration -Dtest=HttpRequestToolIntegrationTest
 */
@Tag("integration")
@DisplayName("HttpRequestTool Integration Tests")
class HttpRequestToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private HttpRequestTool httpRequestTool;

    @BeforeEach
    void setUp() {
        httpRequestTool = new HttpRequestTool();

        try {
            Path outputPath = Paths.get(OUTPUT_DIR);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to create output directory: {}", e.getMessage());
        }
    }

    private void writeOutputToFile(String testName, String output) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String filename = String.format("HttpRequest_%s_%s.md", testName, timestamp);
            Path filePath = Paths.get(OUTPUT_DIR, filename);

            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write("# HttpRequestTool Integration Test: " + testName + "\n\n");
                writer.write("**Timestamp:** " + LocalDateTime.now() + "\n\n---\n\n");
                writer.write(output);
            }
            logger.info("Output written to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write output: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Should GET a public JSON API")
    void testGetJsonApi() {
        Object result = httpRequestTool.execute(Map.of(
            "url", "https://httpbin.org/get"));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("GET_httpbin", resultStr);
        logger.info("Result length: {} chars", resultStr.length());

        assertFalse(resultStr.contains("Error: HTTP request failed"),
            "Should not fail. Got: " + resultStr.substring(0, Math.min(300, resultStr.length())));
        assertTrue(resultStr.contains("**HTTP") && resultStr.contains("Response"),
            "Should have response header");
    }

    @Test
    @DisplayName("Should POST JSON data")
    void testPostJson() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://httpbin.org/post");
        params.put("method", "POST");
        params.put("body", "{\"name\": \"SwarmAI\", \"version\": \"1.0\"}");

        Object result = httpRequestTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("POST_httpbin", resultStr);

        assertFalse(resultStr.contains("Error: HTTP request failed"),
            "Should not fail. Got: " + resultStr.substring(0, Math.min(300, resultStr.length())));
    }

    @Test
    @DisplayName("Should handle HTTP error responses (404)")
    void testHandle404() {
        Object result = httpRequestTool.execute(Map.of(
            "url", "https://httpbin.org/status/404"));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Handle_404", resultStr);

        assertTrue(resultStr.contains("404") || resultStr.contains("Error"),
            "Should indicate 404 error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
    }

    @Test
    @DisplayName("Should send custom headers")
    void testCustomHeaders() {
        Map<String, Object> params = new HashMap<>();
        params.put("url", "https://httpbin.org/headers");
        params.put("headers", Map.of("X-SwarmAI-Test", "integration-test"));

        Object result = httpRequestTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Custom_Headers", resultStr);

        assertFalse(resultStr.contains("Error: HTTP request failed"),
            "Should not fail. Got: " + resultStr.substring(0, Math.min(300, resultStr.length())));
    }

    @Test
    @DisplayName("Performance: should complete within 15 seconds")
    void testPerformance() {
        long startTime = System.currentTimeMillis();

        Object result = httpRequestTool.execute(Map.of("url", "https://httpbin.org/get"));
        long duration = System.currentTimeMillis() - startTime;

        logger.info("HTTP request took {} ms", duration);

        assertNotNull(result);
        assertTrue(duration < 15000, "Should complete within 15 seconds. Actual: " + duration + " ms");
    }

    @Test
    @DisplayName("Should pretty-print JSON responses")
    void testJsonPrettyPrint() {
        Object result = httpRequestTool.execute(Map.of("url", "https://httpbin.org/json"));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("JSON_PrettyPrint", resultStr);

        assertFalse(resultStr.contains("Error: HTTP request failed"),
            "Should not fail. Got: " + resultStr.substring(0, Math.min(300, resultStr.length())));
        // Pretty-printed JSON contains indentation
        assertTrue(resultStr.contains("\n"), "Should be formatted with newlines");
    }
}
