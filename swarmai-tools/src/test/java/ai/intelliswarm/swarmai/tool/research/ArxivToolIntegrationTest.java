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
 * Integration tests for ArxivTool — hits live export.arxiv.org. No API key required.
 * Tagged integration so it's gated behind `mvn verify` / failsafe.
 *
 * Assertions are intentionally specific: we check for content that must appear
 * in the real feed (known paper IDs, author names, PDF URL shape), not just
 * "didn't error". A silent breakage in the parser would fail these.
 */
@Tag("integration")
@DisplayName("ArxivTool Integration Tests")
class ArxivToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(ArxivToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private ArxivTool tool;

    @BeforeEach
    void setUp() {
        tool = new ArxivTool();
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("search: 'transformer architecture' returns >=3 numbered papers with authors + PDFs")
    void searchTransformer() {
        Object out = tool.execute(Map.of(
            "operation", "search",
            "query", "transformer architecture",
            "limit", 5
        ));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // At least 3 numbered results
        assertTrue(s.contains("1. **") && s.contains("2. **") && s.contains("3. **"),
            "Expected at least 3 numbered results. Got:\n" + s);
        // Each result must have an Authors line and a PDF link
        assertTrue(s.contains("Authors:"), "Missing 'Authors:' line");
        assertTrue(s.contains("arxiv.org/pdf/"), "Missing PDF link (arxiv.org/pdf/...)");
        assertTrue(s.toLowerCase().contains("attention") || s.toLowerCase().contains("transformer"),
            "Expected 'attention' or 'transformer' in content");
        write("search_transformer", s);
    }

    @Test
    @DisplayName("get: the 'Attention Is All You Need' paper (1706.03762) is retrievable by id")
    void getAttentionPaper() {
        Object out = tool.execute(Map.of("operation", "get", "id", "1706.03762"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // Canonical identifiers — if any break, the parser or API changed
        assertTrue(s.contains("Attention Is All You Need"),
            "Expected canonical title. Got:\n" + s);
        assertTrue(s.contains("Vaswani"), "Expected author 'Vaswani' in the paper");
        assertTrue(s.contains("1706.03762"), "Expected arXiv ID in output");
        assertTrue(s.contains("arxiv.org/pdf/1706.03762"), "Expected PDF URL pointing to the paper");
        write("get_1706_03762", s);
    }

    @Test
    @DisplayName("search with sort_by=submittedDate returns recent papers (date within last 5 years)")
    void searchSortedByDate() {
        Object out = tool.execute(Map.of(
            "operation", "search",
            "query", "large language model",
            "limit", 3,
            "sort_by", "submittedDate"
        ));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // The top result's published date should be from the past 5 years
        // Format is "Published: YYYY-MM-DD"
        int year = extractFirstYear(s);
        int currentYear = java.time.Year.now().getValue();
        assertTrue(year >= currentYear - 5 && year <= currentYear,
            "Expected a recent paper year (last 5y). Got year=" + year + " from:\n" + s);
        write("search_sorted_by_date", s);
    }

    @Test
    @DisplayName("invalid arXiv id yields 'no papers found', not an exception")
    void getInvalidId() {
        Object out = tool.execute(Map.of("operation", "get", "id", "9999.99999"));
        String s = out.toString();
        // arXiv returns an empty feed for unknown IDs — our tool formats that as "no papers found"
        assertTrue(s.contains("no papers found"), "Expected clean empty-feed message. Got: " + s);
    }

    @Test
    @DisplayName("smokeTest against live API returns null (healthy)")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    // ---------- helpers ----------

    private static int extractFirstYear(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Published: (\\d{4})").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/arxiv_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
