package ai.intelliswarm.swarmai.tool.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WikipediaTool — make live calls to en.wikipedia.org / de.wikipedia.org.
 *
 * No API key required (Wikipedia is open). Tagged "integration" so CI / IDE runs can exclude
 * these when offline:
 *   mvn test                       # unit only (default, excludes "integration" via Surefire config)
 *   mvn test -Dgroups=integration  # integration only
 *
 * Successful responses are dumped to target/integration-test-outputs/ for manual inspection.
 */
@Tag("integration")
@DisplayName("WikipediaTool Integration Tests")
class WikipediaToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(WikipediaToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private WikipediaTool tool;

    @BeforeEach
    void setUp() {
        tool = new WikipediaTool();
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("summary: Albert Einstein returns a real, non-empty abstract")
    void summaryEinstein() {
        Object out = tool.execute(Map.of("query", "Albert Einstein"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Einstein"), "Should name the subject. Got: " + s);
        assertTrue(s.toLowerCase().contains("physicist"),
            "Expected 'physicist' in summary. Got: " + s);
        assertTrue(s.contains("Source:"), "Summary should include a source URL");
        writeSample("summary_einstein", s);
    }

    @Test
    @DisplayName("summary: German edition returns content in German")
    void summaryGermanEdition() {
        Object out = tool.execute(Map.of("query", "Albert Einstein", "language", "de"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Einstein"));
        assertTrue(s.contains("de.wikipedia.org"), "Source should be the German edition");
        writeSample("summary_einstein_de", s);
    }

    @Test
    @DisplayName("summary: unknown page title yields a clean Error response (not an exception)")
    void summaryNotFound() {
        Object out = tool.execute(Map.of("query", "ThisPageShouldNotExist_xzq_99821"));

        String s = out.toString();
        assertTrue(s.startsWith("Error"), "Expected error. Got: " + s);
    }

    @Test
    @DisplayName("search: 'multi-agent system' returns ranked results")
    void searchMultiAgent() {
        Object out = tool.execute(Map.of(
            "query", "multi-agent system",
            "operation", "search",
            "limit", 3
        ));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // Each numbered result starts with "1. **", "2. **", etc.
        assertTrue(s.contains("1. **"));
        assertTrue(s.toLowerCase().contains("agent"));
        writeSample("search_multi_agent", s);
    }

    @Test
    @DisplayName("page: returns plain-text body stripped of HTML and markup")
    void pageParis() {
        Object out = tool.execute(Map.of("query", "Paris", "operation", "page"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Paris"));
        assertFalse(s.contains("<p>"), "HTML should be stripped");
        assertFalse(s.contains("<html"), "HTML should be stripped");
        // Long articles should be truncated by the tool
        assertTrue(s.length() < 8500, "Response should stay under the MaxResponseLength headroom");
        writeSample("page_paris", s);
    }

    @Test
    @DisplayName("smokeTest against live API returns null (healthy)")
    void smokeTestIsHealthy() {
        String status = tool.smokeTest();
        assertNull(status, "Expected healthy (null). Got: " + status);
    }

    private void writeSample(String label, String content) {
        String filename = OUTPUT_DIR + "/wikipedia_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
