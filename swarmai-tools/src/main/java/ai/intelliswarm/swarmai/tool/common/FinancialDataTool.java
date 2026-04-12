package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubClient;
import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubReportFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches structured financial data for a public company — income statement, balance
 * sheet, cash flow, key metrics (P/E, P/B, ROE, margins), and insider transactions —
 * via the Finnhub API. Works for both domestic (AAPL) and foreign issuers (IMPP).
 *
 * <p>This tool was added after SEC-XBRL-only extraction proved brittle: the SEC
 * companyfacts API returned empty us-gaap facts for many foreign private issuers,
 * and HTML parsing of 10-K / 20-F bodies produced inconsistent results. Finnhub
 * returns clean JSON with the same information, plus computed ratios and a unified
 * insider-transaction feed — the structured data that the LLM agents can quote
 * directly without parsing.
 *
 * <p>Usage: {@code {"input": "AAPL"}} — returns a comprehensive markdown report with
 * every figure tagged {@code [Finnhub: <metric>, <period>]} so downstream agents and
 * the verifier can cite each number back to source.
 *
 * <p>Configuration: requires {@code FINNHUB_API_KEY} environment variable (or
 * Spring property {@code finnhub.api-key}). Without a key the tool reports its
 * health as DEGRADED but still returns a minimal stub response that agents can
 * gracefully handle.
 */
@Component
public class FinancialDataTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(FinancialDataTool.class);

    @Value("${finnhub.api-key:${FINNHUB_API_KEY:}}")
    private String apiKey;

    private volatile FinnhubClient client;
    private final FinnhubReportFormatter formatter = new FinnhubReportFormatter();

    private FinnhubClient client() {
        if (client == null) {
            client = new FinnhubClient(apiKey);
        }
        return client;
    }

    /** Package-private constructor override for tests. */
    public void setClientForTest(FinnhubClient testClient) {
        this.client = testClient;
    }

    @Override
    public String getFunctionName() {
        return "financial_data";
    }

    @Override
    public String getDescription() {
        return "Fetches structured financial data for a public company (income statement, " +
               "balance sheet, cash flow, key ratios, insider transactions). Works for " +
               "both US and foreign issuers. Input: ticker symbol (e.g. 'AAPL' or 'IMPP'). " +
               "Returns markdown with every figure citation-tagged [Finnhub: <metric>, <period>].";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String input = String.valueOf(parameters.get("input"));
        if (input == null || input.isBlank()) {
            return "Error: input must be a ticker symbol (e.g. 'AAPL').";
        }
        String ticker = input.trim().toUpperCase();
        // Accept "AAPL" or "AAPL:query-ignored" for compatibility with SEC tool's input format
        if (ticker.contains(":")) ticker = ticker.substring(0, ticker.indexOf(":"));

        logger.info("💰 FinancialDataTool: Fetching financial data for {}", ticker);

        FinnhubClient c = client();
        if (!c.isConfigured()) {
            logger.warn("FinancialDataTool invoked without Finnhub API key — returning configuration-error stub");
            return String.format(
                    "## Financial Data Unavailable\n\n" +
                    "FINNHUB_API_KEY is not configured. Configure the key in .env or environment " +
                    "to enable structured financial data retrieval for %s.\n\n" +
                    "Fallback: use the sec_filings tool for regulatory filings and XBRL data.\n",
                    ticker);
        }

        try {
            JsonNode profile = c.fetchProfile(ticker);
            JsonNode metrics = c.fetchMetrics(ticker);
            JsonNode annual = c.fetchAnnualFinancials(ticker);
            JsonNode quarterly = c.fetchQuarterlyFinancials(ticker);
            JsonNode insider = c.fetchInsiderTransactions(ticker, null, null);

            String report = formatter.formatFullReport(ticker, profile, metrics,
                    annual, quarterly, insider);

            // Truncate to max response length
            int max = getMaxResponseLength();
            if (report.length() > max) {
                logger.info("Truncating Finnhub report from {} to {} chars", report.length(), max);
                report = report.substring(0, max) + "\n\n[Report truncated from " + report.length() + " chars.]";
            }
            logger.info("FinancialDataTool produced {} chars for {} (profile={}, metrics={}, annual={}, quarterly={}, insider={})",
                    report.length(), ticker,
                    profile != null && !profile.isMissingNode() && !profile.isEmpty(),
                    metrics != null && !metrics.isMissingNode(),
                    annual != null && annual.isArray() ? annual.size() : 0,
                    quarterly != null && quarterly.isArray() ? quarterly.size() : 0,
                    insider != null && insider.isArray() ? insider.size() : 0);
            return report;

        } catch (Exception e) {
            logger.error("FinancialDataTool failed for {}: {}", ticker, e.getMessage(), e);
            return "Error fetching financial data for " + ticker + ": " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("input", Map.of(
                "type", "string",
                "description", "Ticker symbol (e.g. 'AAPL', 'MSFT', 'IMPP')."
        ));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("input")
        );
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public String getTriggerWhen() {
        return "User needs income statement, balance sheet, cash flow, key ratios (P/E, P/B, ROE), " +
               "margin trends, revenue growth, or insider transaction summary for a public company.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs regulatory filing text / MD&A narrative / 10-K / 8-K details (use sec_filings instead), " +
               "or needs general news / web search (use web_search instead).";
    }

    @Override
    public String getCategory() { return "finance"; }

    @Override
    public List<String> getTags() {
        return List.of("finance", "financial-data", "income-statement", "balance-sheet",
                "cash-flow", "ratios", "insider", "finnhub");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
                "type", "markdown",
                "description", "Markdown financial data report with company profile, key metrics, " +
                        "income statement, balance sheet, cash flow, quarterly revenue trend, and insider transactions."
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 18000;
    }

    /** Public for health check and tests. */
    public boolean isHealthy() {
        return client().isConfigured();
    }
}
