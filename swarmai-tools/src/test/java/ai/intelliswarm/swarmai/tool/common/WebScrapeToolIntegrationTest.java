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
 * Integration tests for WebScrapeTool that fetch real web pages.
 *
 * To run: mvn test -Dgroups=integration -Dtest=WebScrapeToolIntegrationTest
 */
@Tag("integration")
@DisplayName("WebScrapeTool Integration Tests")
class WebScrapeToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WebScrapeToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private WebScrapeTool webScrapeTool;

    @BeforeEach
    void setUp() {
        webScrapeTool = new WebScrapeTool();

        try {
            Path outputPath = Paths.get(OUTPUT_DIR);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }
        } catch (IOException e) {
            logger.warn("Failed to create output directory: {}", e.getMessage());
        }
    }

    private void writeOutputToFile(String testName, String url, String output) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String sanitizedUrl = url.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0, Math.min(url.length(), 40));
            String filename = String.format("WebScrape_%s_%s_%s.md", testName, sanitizedUrl, timestamp);
            Path filePath = Paths.get(OUTPUT_DIR, filename);

            StringBuilder content = new StringBuilder();
            content.append("# WebScrapeTool Integration Test Output\n\n");
            content.append(String.format("**Test:** %s\n", testName));
            content.append(String.format("**URL:** %s\n", url));
            content.append(String.format("**Timestamp:** %s\n", LocalDateTime.now()));
            content.append(String.format("**Output Length:** %d chars\n\n", output.length()));
            content.append("---\n\n");
            content.append(output);

            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(content.toString());
            }
            logger.info("Output written to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write output: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("Should scrape example.com successfully")
    void testScrapeExampleCom() {
        String url = "https://example.com";
        Object result = webScrapeTool.execute(Map.of("url", url));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("ExampleCom", url, resultStr);
        logger.info("Result length: {} chars", resultStr.length());

        assertFalse(resultStr.contains("Error"),
            "Should not error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("**URL:**"), "Should include URL metadata");
        assertTrue(resultStr.contains("**Title:**"), "Should include title");
        assertTrue(resultStr.contains("Example Domain"), "Should extract example.com title");
    }

    @Test
    @DisplayName("Should scrape Wikipedia page with content extraction")
    void testScrapeWikipedia() {
        String url = "https://en.wikipedia.org/wiki/Java_(programming_language)";
        Object result = webScrapeTool.execute(Map.of("url", url));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Wikipedia_Java", url, resultStr);
        logger.info("Result length: {} chars", resultStr.length());

        assertFalse(resultStr.contains("Error"),
            "Should not error. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("Java"), "Should contain Java content");
        assertTrue(resultStr.length() > 1000, "Wikipedia should produce substantial content");
    }

    @Test
    @DisplayName("Should extract content with CSS selector")
    void testScrapeWithSelector() {
        String url = "https://example.com";
        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        params.put("selector", "p");

        Object result = webScrapeTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("ExampleCom_Selector", url, resultStr);

        assertFalse(resultStr.contains("Error"), "Should not error");
        assertTrue(resultStr.contains("Selected content"), "Should indicate selector was used");
    }

    @Test
    @DisplayName("Should include links when requested")
    void testScrapeWithLinks() {
        String url = "https://example.com";
        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        params.put("include_links", true);

        Object result = webScrapeTool.execute(params);

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("ExampleCom_WithLinks", url, resultStr);

        assertFalse(resultStr.contains("Error"), "Should not error");
        // example.com has at least one link
        assertTrue(resultStr.contains("Links") || resultStr.contains("iana.org"),
            "Should include links section or link content");
    }

    @Test
    @DisplayName("Performance: should complete within 15 seconds")
    void testPerformance() {
        long startTime = System.currentTimeMillis();

        Object result = webScrapeTool.execute(Map.of("url", "https://example.com"));
        long duration = System.currentTimeMillis() - startTime;

        logger.info("Scrape took {} ms", duration);

        assertNotNull(result);
        assertFalse(result.toString().contains("Error"), "Should not error");
        assertTrue(duration < 15000, "Should complete within 15 seconds. Actual: " + duration + " ms");
    }

    @Test
    @DisplayName("Should handle 404 page gracefully")
    void testHandle404() {
        Object result = webScrapeTool.execute(
            Map.of("url", "https://httpstat.us/404"));

        assertNotNull(result);
        String resultStr = result.toString();

        writeOutputToFile("Handle404", "httpstat.us/404", resultStr);

        // Should either return error or return the 404 page content
        assertTrue(resultStr.contains("Error") || resultStr.contains("404") || resultStr.contains("**URL:**"),
            "Should handle 404 gracefully. Got: " + resultStr.substring(0, Math.min(200, resultStr.length())));
    }
}
