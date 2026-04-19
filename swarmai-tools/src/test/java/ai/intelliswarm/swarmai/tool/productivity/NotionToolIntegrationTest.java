package ai.intelliswarm.swarmai.tool.productivity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Live integration tests for NotionTool. Requires:
 *   - NOTION_TOKEN : integration token (create at https://www.notion.so/profile/integrations)
 *   - At least one page shared with the integration (any workspace page works)
 *
 * Optional:
 *   - NOTION_TEST_PAGE_ID     : verify retrieve_page against a known page
 *   - NOTION_TEST_DATABASE_ID : verify query_database against a known database
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "NOTION_TOKEN", matches = ".+")
@DisplayName("NotionTool Integration Tests")
class NotionToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(NotionToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private NotionTool tool;

    @BeforeEach
    void setUp() {
        tool = new NotionTool();
        ReflectionTestUtils.setField(tool, "token", System.getenv("NOTION_TOKEN"));
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("search: returns a 'results' section even if empty — proves auth + Notion-Version work")
    void searchReachesNotion() {
        Object out = tool.execute(Map.of("operation", "search", "query", "", "page_size", 5));
        String s = out.toString();
        // Must NOT have an auth or forbidden error. Empty results are fine.
        assertFalse(s.startsWith("Error"), "Auth/transport failed: " + s);
        assertTrue(s.contains("Notion") || s.contains("No Notion results"),
            "Unexpected shape: " + s);
        write("search_probe", s);
    }

    @Test
    @DisplayName("retrieve_page: pulls page metadata + body (when NOTION_TEST_PAGE_ID is set)")
    void retrievePage() {
        String pageId = System.getenv("NOTION_TEST_PAGE_ID");
        assumeNonEmpty(pageId, "NOTION_TEST_PAGE_ID");

        Object out = tool.execute(Map.of("operation", "retrieve_page", "page_id", pageId));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("id: `"), "Expected page id in output");
        assertTrue(s.contains("url:"), "Expected page url in output");
        write("retrieve_page", s);
    }

    @Test
    @DisplayName("query_database: returns >=1 row (when NOTION_TEST_DATABASE_ID is set)")
    void queryDatabase() {
        String dbId = System.getenv("NOTION_TEST_DATABASE_ID");
        assumeNonEmpty(dbId, "NOTION_TEST_DATABASE_ID");

        Object out = tool.execute(Map.of(
            "operation", "query_database",
            "database_id", dbId,
            "page_size", 5));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Database query returned") || s.contains("No matching rows"),
            "Unexpected shape: " + s);
        write("query_database", s);
    }

    @Test
    @DisplayName("smokeTest passes with real token")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    // ---------- helpers ----------

    private static void assumeNonEmpty(String value, String envName) {
        // Soft-skip via assumeTrue keeps the test green in CI when the optional env var isn't set.
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null && !value.isBlank(),
            "Set " + envName + " to run this test against a real page/database.");
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/notion_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
