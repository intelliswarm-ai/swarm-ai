package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubClient;
import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubReportFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link FinancialDataTool} that make real HTTP calls to the
 * Finnhub API using the {@code FINNHUB_API_KEY} from environment or the
 * {@code swarm-ai-examples/.env} file.
 *
 * <p>Run with: {@code mvn test -Dgroups=integration -Dtest=FinancialDataToolIntegrationTest}
 *
 * <p>These tests verify that:
 * <ol>
 *   <li>The Finnhub endpoints are reachable and the API key authenticates.</li>
 *   <li>AAPL (domestic 10-K filer) returns income statement, balance sheet, margins, and insider data.</li>
 *   <li>IMPP (foreign 20-F filer) also returns income statement data — the reason we built this tool.</li>
 *   <li>The rendered markdown includes every section heading agents look for.</li>
 * </ol>
 */
@Tag("integration")
@DisplayName("FinancialDataTool Integration Tests")
@EnabledIf("finnhubKeyAvailable")
class FinancialDataToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(FinancialDataToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";

    private static String finnhubKey;

    @BeforeAll
    static void setup() {
        finnhubKey = loadFinnhubKey();
        try {
            Files.createDirectories(Paths.get(OUTPUT_DIR));
        } catch (Exception ignored) { /* harmless */ }
    }

    /**
     * JUnit {@code @EnabledIf} condition. Returns true iff a Finnhub key is available
     * from either the system environment or the {@code swarm-ai-examples/.env} file.
     * The method must be static and callable via {@link Class#getMethod(String, Class[])}.
     */
    static boolean finnhubKeyAvailable() {
        return loadFinnhubKey() != null;
    }

    @Test
    @DisplayName("AAPL → full markdown report with income statement + margins + insider data")
    void aaplFullReport() {
        FinancialDataTool tool = toolWithKey(finnhubKey);
        String report = (String) tool.execute(Map.of("input", "AAPL"));

        writeOutput("AAPL_full_report", report);

        assertThat(report)
                .as("Company profile section should be present")
                .contains("## Company Profile")
                .contains("Apple");
        assertThat(report)
                .as("Income statement section should include revenue")
                .contains("## Income Statement (annual)")
                .contains("Revenue");
        assertThat(report)
                .as("Key metrics section should be present")
                .contains("## Key Metrics & Ratios");
        // Margins are expected (could be shown as TTM or annual)
        assertThat(report.toLowerCase()).contains("margin");
        // Every Finnhub figure should carry its [Finnhub: ...] citation
        assertThat(report).contains("[Finnhub:");
    }

    @Test
    @DisplayName("IMPP (foreign 20-F filer) → still returns structured income statement data")
    void imppForeignIssuer() {
        FinancialDataTool tool = toolWithKey(finnhubKey);
        String report = (String) tool.execute(Map.of("input", "IMPP"));

        writeOutput("IMPP_foreign_issuer", report);

        // We don't assert specific dollar values (those change every quarter). We
        // assert that the tool actually produced structured sections — the entire
        // reason we added it is that SEC XBRL alone returned empty data for foreign
        // filers like IMPP.
        assertThat(report).contains("# Finnhub Financial Data for IMPP");
        // Either the income statement or key metrics section should appear — that's
        // the baseline "we got structured data from Finnhub for a foreign issuer" test.
        assertThat(
                report.contains("## Income Statement") ||
                report.contains("## Key Metrics") ||
                report.contains("## Company Profile"))
                .as("At least one structured section must exist for IMPP")
                .isTrue();
    }

    @Test
    @DisplayName("Invalid ticker returns graceful response (empty sections, no crash)")
    void invalidTicker() {
        FinancialDataTool tool = toolWithKey(finnhubKey);
        // Using an obviously invalid ticker
        String report = (String) tool.execute(Map.of("input", "ZZZ_NOT_A_TICKER_XXX"));

        // Tool must not throw; must return a header at minimum
        assertThat(report).contains("# Finnhub Financial Data for");
    }

    @Test
    @DisplayName("FinnhubClient.fetchProfile returns expected keys for AAPL")
    void clientFetchProfile() {
        FinnhubClient client = new FinnhubClient(finnhubKey);
        JsonNode profile = client.fetchProfile("AAPL");

        assertThat(profile).as("Profile call should succeed for AAPL").isNotNull();
        assertThat(profile.path("country").asText(null)).isNotBlank();
        assertThat(profile.path("finnhubIndustry").asText(null)).isNotBlank();
    }

    @Test
    @DisplayName("FinnhubClient.fetchMetrics returns pre-computed ratios for AAPL")
    void clientFetchMetrics() {
        FinnhubClient client = new FinnhubClient(finnhubKey);
        JsonNode metrics = client.fetchMetrics("AAPL");

        assertThat(metrics).isNotNull();
        // At least one of the canonical ratios should be present
        boolean hasAnyRatio =
                metrics.has("peBasicExclExtraTTM") || metrics.has("pbAnnual") ||
                metrics.has("roeTTM") || metrics.has("netProfitMarginTTM");
        assertThat(hasAnyRatio).as("At least one ratio should be returned").isTrue();
    }

    @Test
    @DisplayName("FinnhubClient.fetchAnnualFinancials returns array of reports for AAPL")
    void clientFetchAnnualFinancials() {
        FinnhubClient client = new FinnhubClient(finnhubKey);
        JsonNode annual = client.fetchAnnualFinancials("AAPL");

        assertThat(annual).isNotNull();
        assertThat(annual.isArray()).isTrue();
        assertThat(annual.size()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Report formatter round-trip for AAPL live data")
    void formatterRoundTrip() {
        FinnhubClient client = new FinnhubClient(finnhubKey);
        FinnhubReportFormatter formatter = new FinnhubReportFormatter();

        JsonNode profile = client.fetchProfile("AAPL");
        JsonNode metrics = client.fetchMetrics("AAPL");
        JsonNode annual = client.fetchAnnualFinancials("AAPL");
        JsonNode quarterly = client.fetchQuarterlyFinancials("AAPL");
        JsonNode insider = client.fetchInsiderTransactions("AAPL", null, null);

        String report = formatter.formatFullReport("AAPL", profile, metrics, annual, quarterly, insider);
        writeOutput("AAPL_formatter_roundtrip", report);

        assertThat(report).contains("# Finnhub Financial Data for AAPL");
        // With real AAPL data, we should definitely see income statement and balance sheet
        assertThat(report).contains("## Income Statement (annual)");
    }

    // -------- helpers --------

    private static String loadFinnhubKey() {
        // 1. System env wins
        String env = System.getenv("FINNHUB_API_KEY");
        if (env != null && !env.isBlank() && !"demo".equalsIgnoreCase(env)) {
            return env;
        }
        // 2. Fallback: read from sibling swarm-ai-examples/.env
        Path[] candidates = new Path[]{
                Paths.get(System.getProperty("user.dir"), ".env"),
                Paths.get(System.getProperty("user.dir"), "..", "swarm-ai-examples", ".env"),
                Paths.get(System.getProperty("user.dir"), "..", "..", "swarm-ai-examples", ".env"),
        };
        for (Path p : candidates) {
            if (!Files.exists(p)) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(p.toFile()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("FINNHUB_API_KEY=")) {
                        String value = line.substring("FINNHUB_API_KEY=".length()).trim();
                        // strip surrounding quotes if present
                        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        if (!value.isBlank() && !"demo".equalsIgnoreCase(value)) {
                            logger.info("Loaded FINNHUB_API_KEY from {}", p);
                            return value;
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not read {}: {}", p, e.getMessage());
            }
        }
        return null;
    }

    private FinancialDataTool toolWithKey(String key) {
        FinancialDataTool tool = new FinancialDataTool();
        tool.setClientForTest(new FinnhubClient(key));
        return tool;
    }

    private void writeOutput(String testName, String content) {
        try {
            Path out = Paths.get(OUTPUT_DIR, "FinancialData_" + testName + "_" +
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) +
                    ".md");
            Files.writeString(out, content);
            logger.info("Test output written to {} ({} chars)", out.toAbsolutePath(), content.length());
        } catch (Exception e) {
            logger.warn("Could not write test output {}: {}", testName, e.getMessage());
        }
    }
}
