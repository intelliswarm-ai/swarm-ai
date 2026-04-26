package ai.intelliswarm.swarmai.tool.common.eodhd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the EODHD formatters against real API responses captured as fixtures under
 * {@code src/test/resources/eodhd/}. Each test loads a JSON file the live API returned
 * (for endpoints the free tier covers) or a {@code demo}-token response (for paid
 * endpoints), then asserts the rendered markdown carries the expected real values.
 *
 * <p>Why this style: pure schema/parser tests miss the case where EODHD changes a field
 * name (e.g. {@code recordDate} → {@code record_date}) and the formatter silently emits
 * "—" in production. Fixture-driven assertions catch that on the next test run because
 * the substring assertion fails immediately.
 *
 * <p>Capturing more fixtures: re-run the curls in
 * {@code swarm-ai-examples/eodhd-global-markets/README.md} and drop the JSON into
 * {@code src/test/resources/eodhd/}. Bump the assertions to match the new sample values.
 */
@DisplayName("EODHD Formatter Fixture Tests")
class EodhdFormatterFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EodhdReportFormatter mkt = new EodhdReportFormatter();
    private final EodhdDiscoveryFormatter disc = new EodhdDiscoveryFormatter();

    @Test
    @DisplayName("EOD OHLCV fixture renders newest-first table with real OHLCV values")
    void eod() throws Exception {
        JsonNode eod = load("eod_AAPL_US.json");
        String md = mkt.formatFullReport("AAPL.US", null, null, eod, null, null, null);

        assertThat(md).contains("# EODHD Market Data for AAPL.US");
        assertThat(md).contains("## End-of-Day OHLCV");
        // Real values from the captured fixture (window 2026-04-01..2026-04-25):
        assertThat(md).contains("2026-04-01");           // earliest captured date
        assertThat(md).contains("254.0800");             // open of the earliest bar
        // Citation tag includes the captured date range
        assertThat(md).contains("[EODHD: eod, 2026-04-01");
    }

    @Test
    @DisplayName("Dividends fixture renders the captured payment with cited columns")
    void dividends() throws Exception {
        JsonNode div = load("div_AAPL_US.json");
        String md = mkt.formatFullReport("AAPL.US", null, null, null, div, null, null);

        assertThat(md).contains("## Dividends");
        // Real distributions in the fixture:
        assertThat(md).contains("2025-02-10");
        assertThat(md).contains("0.2500");        // value
        assertThat(md).contains("USD");
        assertThat(md).contains("Quarterly");     // period column (added in formatter)
        assertThat(md).contains("2025-01-30");    // declarationDate
        assertThat(md).contains("[EODHD: div");
    }

    @Test
    @DisplayName("Splits fixture renders historical AAPL splits including the 7:1")
    void splits() throws Exception {
        JsonNode splits = load("splits_AAPL_US.json");
        String md = mkt.formatFullReport("AAPL.US", null, null, null, null, splits, null);

        assertThat(md).contains("## Stock Splits");
        // The 1987 2-for-1 — the very first AAPL split in the fixture:
        assertThat(md).contains("1987-06-16");
        assertThat(md).contains("2.000000/1.000000");
        assertThat(md).contains("[EODHD: splits]");
    }

    @Test
    @DisplayName("News fixture renders the captured headline and date with citation")
    void news() throws Exception {
        JsonNode news = load("news_AAPL_US.json");
        String md = mkt.formatFullReport("AAPL.US", null, null, null, null, null, news);

        assertThat(md).contains("## Recent News");
        // First headline in the captured fixture
        assertThat(md).contains("AEye");
        // Per-article citation tag uses the article date
        assertThat(md).contains("[EODHD: news, 2026-04-26");
    }

    @Test
    @DisplayName("Technical RSI fixture renders the indicator series with column header")
    void technical() throws Exception {
        JsonNode rsi = load("demo_technical_AAPL_US_rsi.json");
        String md = mkt.formatTechnical("AAPL.US", "rsi", 14, rsi);

        assertThat(md).contains("# EODHD Technical: RSI");
        assertThat(md).contains("(period 14)");
        // Real RSI values from the demo-token capture
        assertThat(md).contains("2026-04-22");
        assertThat(md).contains("67.0623");
        assertThat(md).contains("[EODHD: technical/rsi");
    }

    @Test
    @DisplayName("Intraday fixture renders the 5m bars table newest-first")
    void intraday() throws Exception {
        JsonNode intraday = load("demo_intraday_AAPL_US_5m.json");
        String md = mkt.formatIntraday("AAPL.US", "5m", intraday);

        assertThat(md).contains("# EODHD Intraday (5m) for AAPL.US");
        assertThat(md).contains("| Datetime | Open | High | Low | Close | Volume |");
        assertThat(md).contains("[EODHD: intraday/5m");
    }

    @Test
    @DisplayName("Fundamentals fixture renders General + Highlights blocks with real cap")
    void fundamentals() throws Exception {
        JsonNode f = load("demo_fundamentals_AAPL_US.json");
        String md = mkt.formatFullReport("AAPL.US", null, f, null, null, null, null);

        assertThat(md).contains("## Company Profile & Highlights");
        assertThat(md).contains("Apple Inc");
        assertThat(md).contains("NASDAQ");
        assertThat(md).contains("USD");
        // From Highlights: PERatio 34.3549
        assertThat(md).contains("34.3549");
        // Citation tags exposed for downstream agents
        assertThat(md).contains("[EODHD: Highlights.PERatio]");
    }

    // ---------- Discovery formatter ----------

    @Test
    @DisplayName("Search fixture renders the AAPL row with ISIN, exchange, country")
    void search() throws Exception {
        JsonNode results = load("search_AAPL.json");
        String md = disc.formatSearch("AAPL", results);

        assertThat(md).contains("# EODHD Symbol Search: \"AAPL\"");
        assertThat(md).contains("Apple Inc");
        assertThat(md).contains("US0378331005");      // ISIN
        assertThat(md).contains("USA");
        assertThat(md).contains("Common Stock");
        assertThat(md).contains("[EODHD: search, query=\"AAPL\"]");
    }

    // ---------- helpers ----------

    private JsonNode load(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("eodhd/" + name)) {
            assertThat(in).as("missing fixture eodhd/" + name).isNotNull();
            return MAPPER.readTree(in);
        }
    }
}
