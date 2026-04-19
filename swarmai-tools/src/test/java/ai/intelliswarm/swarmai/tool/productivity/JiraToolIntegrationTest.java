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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration tests for JiraTool.
 *
 * Requires: JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN.
 * Optional: JIRA_TEST_PROJECT (defaults to "TEST") for create_issue/add_comment round-trip.
 *
 * If JIRA_TEST_PROJECT is set, the test creates a throw-away issue and immediately comments
 * on it — you end up with one issue in that project per run. Use a sandbox project.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "JIRA_BASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_EMAIL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_API_TOKEN", matches = ".+")
@DisplayName("JiraTool Integration Tests")
class JiraToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(JiraToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private JiraTool tool;

    @BeforeEach
    void setUp() {
        tool = new JiraTool();
        ReflectionTestUtils.setField(tool, "baseUrl", System.getenv("JIRA_BASE_URL"));
        ReflectionTestUtils.setField(tool, "email", System.getenv("JIRA_EMAIL"));
        ReflectionTestUtils.setField(tool, "apiToken", System.getenv("JIRA_API_TOKEN"));
        try {
            Path p = Paths.get(OUTPUT_DIR);
            if (!Files.exists(p)) Files.createDirectories(p);
        } catch (IOException e) {
            logger.warn("Could not create output dir: {}", e.getMessage());
        }
    }

    @Test
    @DisplayName("smokeTest passes — credentials work against /myself")
    void smokeOk() {
        assertNull(tool.smokeTest(), "Expected healthy (null)");
    }

    @Test
    @DisplayName("search_issues: JQL 'order by created DESC' returns a formatted list")
    void searchRecent() {
        Object out = tool.execute(Map.of(
            "operation", "search_issues",
            "jql", "order by created DESC",
            "max_results", 5));
        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.contains("Jira issues for JQL"), "Expected header line. Got:\n" + s);
        assertTrue(s.contains("/browse/") || s.contains("No Jira issues"),
            "Expected either results with URLs or a clean no-match message");
        write("search_recent", s);
    }

    @Test
    @DisplayName("create_issue + add_comment round-trip (requires JIRA_TEST_PROJECT)")
    void createAndComment() {
        String project = System.getenv().getOrDefault("JIRA_TEST_PROJECT", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!project.isBlank(),
            "Set JIRA_TEST_PROJECT to a sandbox project key to run this test.");

        String summary = "SwarmAI integration test — " + LocalDateTime.now();
        Object createOut = tool.execute(Map.of(
            "operation", "create_issue",
            "project", project,
            "summary", summary,
            "description", "Created by SwarmAI's JiraTool integration test suite. Safe to delete.",
            "issue_type", "Task"));
        String createStr = createOut.toString();
        assertFalse(createStr.startsWith("Error"), "Create failed: " + createStr);
        assertTrue(createStr.contains("Created issue **"), createStr);

        // Extract key from "Created issue **PROJ-123**"
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Created issue \\*\\*([A-Z]+-\\d+)\\*\\*").matcher(createStr);
        assertTrue(m.find(), "Couldn't parse new issue key from: " + createStr);
        String newKey = m.group(1);
        logger.info("Created test issue: {}", newKey);

        // Comment on it
        Object commentOut = tool.execute(Map.of(
            "operation", "add_comment",
            "issue_key", newKey,
            "comment", "Follow-up comment from SwarmAI integration test."));
        assertFalse(commentOut.toString().startsWith("Error"), commentOut.toString());
        assertTrue(commentOut.toString().contains("Comment added to " + newKey), commentOut.toString());

        // Fetch it back and confirm the comment is visible
        Object getOut = tool.execute(Map.of("operation", "get_issue", "issue_key", newKey));
        String getStr = getOut.toString();
        assertFalse(getStr.startsWith("Error"), getStr);
        assertTrue(getStr.contains(summary), "Retrieved summary should match. Got:\n" + getStr);
        assertTrue(getStr.contains("Follow-up comment from SwarmAI"),
            "Comment should round-trip. Got:\n" + getStr);

        write("round_trip_" + newKey, createStr + "\n\n" + commentOut + "\n\n" + getStr);
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/jira_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
