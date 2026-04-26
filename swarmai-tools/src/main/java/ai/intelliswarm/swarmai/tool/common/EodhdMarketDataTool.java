package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.eodhd.EodhdClient;
import ai.intelliswarm.swarmai.tool.common.eodhd.EodhdReportFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches global market data from the EODHD API ({@code https://eodhd.com/}) — historical
 * end-of-day OHLCV, real-time quotes, fundamentals, dividends, splits, and news for any
 * symbol on 60+ exchanges (US, LSE, XETRA, SW, TSE, ASX, etc.).
 *
 * <p>EODHD is the global-coverage complement to {@link FinancialDataTool} (Finnhub).
 * Use this tool when you need:
 * <ul>
 *   <li>Long historical OHLCV windows (decades) for backtesting or trend analysis</li>
 *   <li>International tickers — anything outside US issuers</li>
 *   <li>Dividend / split history for total-return calculations</li>
 *   <li>Real-time quotes</li>
 * </ul>
 *
 * <p>Input formats supported:
 * <ul>
 *   <li>{@code "AAPL"} — defaults to {@code .US} suffix</li>
 *   <li>{@code "AAPL.US"}, {@code "BMW.XETRA"}, {@code "VOD.LSE"} — explicit exchange</li>
 *   <li>{@code "AAPL.US:eod"} — request a specific endpoint (eod, quote, fundamentals,
 *       dividends, splits, news, all). Default is {@code all}.</li>
 *   <li>{@code "AAPL.US:eod:2024-01-01:2026-04-26"} — endpoint plus date range</li>
 * </ul>
 *
 * <p>Configuration: requires {@code EODHD_API_KEY} environment variable (or Spring property
 * {@code eodhd.api-key}). Without a key the tool returns a graceful unavailability stub.
 */
@Component
public class EodhdMarketDataTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(EodhdMarketDataTool.class);

    @Value("${eodhd.api-key:${EODHD_API_KEY:}}")
    private String apiKey;

    private volatile EodhdClient client;
    private final EodhdReportFormatter formatter = new EodhdReportFormatter();

    private EodhdClient client() {
        if (client == null) {
            client = new EodhdClient(apiKey);
        }
        return client;
    }

    /** Override the client (used by tests). */
    public void setClientForTest(EodhdClient testClient) {
        this.client = testClient;
    }

    @Override
    public String getFunctionName() {
        return "eodhd_market_data";
    }

    @Override
    public String getDescription() {
        return "Fetches global market data from EODHD: historical end-of-day OHLCV, intraday " +
               "OHLCV (1m/5m/1h), real-time quotes, fundamentals, dividend and split history, " +
               "news, server-computed technical indicators (RSI, MACD, SMA, EMA, BBANDS, …), " +
               "and macro economic indicators by country. Covers 60+ exchanges. " +
               "Input grammar: '<SYMBOL>[.<EXCHANGE>][:<endpoint>[:<arg1>[:<arg2>[:<arg3>]]]]'. " +
               "Examples: 'AAPL', 'BMW.XETRA:eod', 'AAPL.US:intraday:5m', 'AAPL.US:technical:rsi:14', " +
               "'USA:macro:gdp_current_usd'. Returns markdown tagged [EODHD: <endpoint>, <period>].";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object rawInput = parameters.get("input");
        if (rawInput == null) {
            return "Error: input must be a ticker symbol (e.g. 'AAPL', 'AAPL.US', 'BMW.XETRA').";
        }
        String input = String.valueOf(rawInput).trim();
        if (input.isBlank() || "null".equalsIgnoreCase(input)) {
            return "Error: input must be a ticker symbol (e.g. 'AAPL', 'AAPL.US', 'BMW.XETRA').";
        }

        ParsedInput parsed = parseInput(input);
        logger.info("📈 EodhdMarketDataTool: fetching {} for {} ({} → {})",
                parsed.endpoint, parsed.symbol, parsed.fromIso, parsed.toIso);

        EodhdClient c = client();
        if (!c.isConfigured()) {
            logger.warn("EodhdMarketDataTool invoked without API key — returning stub");
            return String.format(
                    "## EODHD Market Data Unavailable%n%n" +
                    "EODHD_API_KEY is not configured. Set the env var or " +
                    "`eodhd.api-key` Spring property to enable global market data " +
                    "retrieval for %s.%n%n" +
                    "Get a key at https://eodhd.com/ — the free tier allows 20 requests/day.%n",
                    parsed.symbol);
        }

        try {
            String report;
            switch (parsed.endpoint) {
                case "quote":
                    report = formatter.formatFullReport(parsed.symbol,
                            c.fetchRealTime(parsed.symbol), null, null, null, null, null);
                    break;
                case "fundamentals":
                    report = formatter.formatFullReport(parsed.symbol,
                            null, c.fetchFundamentals(parsed.symbol), null, null, null, null);
                    break;
                case "eod":
                    report = formatter.formatFullReport(parsed.symbol,
                            null, null, c.fetchEod(parsed.symbol, parsed.fromIso, parsed.toIso, "d"),
                            null, null, null);
                    break;
                case "dividends":
                    report = formatter.formatFullReport(parsed.symbol,
                            null, null, null, c.fetchDividends(parsed.symbol, parsed.fromIso),
                            null, null);
                    break;
                case "splits":
                    report = formatter.formatFullReport(parsed.symbol,
                            null, null, null, null, c.fetchSplits(parsed.symbol, parsed.fromIso),
                            null);
                    break;
                case "news":
                    report = formatter.formatFullReport(parsed.symbol,
                            null, null, null, null, null, c.fetchNews(parsed.symbol, 20));
                    break;
                case "intraday":
                    report = formatter.formatIntraday(parsed.symbol, parsed.interval,
                            c.fetchIntraday(parsed.symbol, parsed.interval, null, null));
                    break;
                case "technical":
                    report = formatter.formatTechnical(parsed.symbol, parsed.function, parsed.period,
                            c.fetchTechnical(parsed.symbol, parsed.function, parsed.period,
                                    parsed.fromIso, parsed.toIso));
                    break;
                case "macro":
                    // For macro, parsed.symbol carries the country code (no .US auto-append)
                    report = formatter.formatMacro(parsed.country, parsed.indicator,
                            c.fetchMacroIndicator(parsed.country, parsed.indicator));
                    break;
                case "all":
                default:
                    report = formatter.formatFullReport(parsed.symbol,
                            c.fetchRealTime(parsed.symbol),
                            c.fetchFundamentals(parsed.symbol),
                            c.fetchEod(parsed.symbol, parsed.fromIso, parsed.toIso, "d"),
                            c.fetchDividends(parsed.symbol, null),
                            c.fetchSplits(parsed.symbol, null),
                            c.fetchNews(parsed.symbol, 10));
                    break;
            }

            int max = getMaxResponseLength();
            if (report.length() > max) {
                logger.info("Truncating EODHD report from {} to {} chars", report.length(), max);
                report = report.substring(0, max) + "\n\n[Report truncated from " + report.length() + " chars.]";
            }
            return report;

        } catch (Exception e) {
            logger.error("EodhdMarketDataTool failed for {}: {}", parsed.symbol, e.getMessage(), e);
            return "Error fetching EODHD data for " + parsed.symbol + ": " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("input", Map.of(
                "type", "string",
                "description", "Symbol with optional exchange and endpoint selector. " +
                        "Endpoints: all (default), quote, eod, fundamentals, dividends, splits, " +
                        "news, intraday, technical, macro. " +
                        "Examples: 'AAPL' (defaults to .US, all sections), 'BMW.XETRA:eod', " +
                        "'AAPL.US:eod:2024-01-01:2026-04-26', 'AAPL.US:intraday:5m', " +
                        "'AAPL.US:technical:rsi:14', 'USA:macro:gdp_current_usd' " +
                        "(macro uses ISO-3 country code in the symbol slot)."
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
        return "User needs historical end-of-day prices, real-time quote, dividend/split history, " +
               "global fundamentals, or news for any public ticker — especially international " +
               "issuers on LSE, XETRA, TSE, ASX, etc. Also when long historical windows " +
               "(years/decades of OHLCV) are required for backtesting or trend analysis.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs SEC regulatory filings (use sec_filings), insider transactions or " +
               "Finnhub-specific ratios (use financial_data), or general web/news search " +
               "across providers (use web_search).";
    }

    @Override
    public String getCategory() { return "finance"; }

    @Override
    public List<String> getTags() {
        return List.of("finance", "market-data", "ohlcv", "intraday", "quotes", "fundamentals",
                "dividends", "splits", "news", "technical-indicators", "macro",
                "global-markets", "eodhd");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
                "type", "markdown",
                "description", "Markdown report with sections for latest quote, company highlights, " +
                        "valuation, technicals, recent OHLCV table, dividends, splits, and news. " +
                        "Every figure is tagged [EODHD: <endpoint>, <period>] for downstream citation."
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 18000;
    }

    /** Healthy when an API key is configured. */
    public boolean isHealthy() {
        return client().isConfigured();
    }

    // ---------- input parsing ----------

    static class ParsedInput {
        String symbol;       // for symbol endpoints: normalized with exchange suffix (e.g. "AAPL.US")
        String endpoint;     // all|quote|eod|fundamentals|dividends|splits|news|intraday|technical|macro
        String fromIso;      // ISO date for eod/dividends/splits/technical
        String toIso;        // ISO date for eod/technical
        String interval;     // intraday: "1m" | "5m" | "1h"
        String function;     // technical: "rsi" | "macd" | "sma" | …
        Integer period;      // technical: indicator period (e.g. 14 for RSI)
        String country;      // macro: ISO-3 country code (e.g. "USA")
        String indicator;    // macro: indicator key (e.g. "gdp_current_usd")

        ParsedInput(String symbol, String endpoint) {
            this.symbol = symbol;
            this.endpoint = endpoint;
        }
    }

    /**
     * Parses the {@code input} string. Grammar varies by endpoint:
     * <ul>
     *   <li>Default / eod / dividends / splits / quote / fundamentals / news / all:
     *       {@code <SYMBOL>[.<EXCHANGE>][:<endpoint>[:<from>[:<to>]]]}</li>
     *   <li>Intraday: {@code <SYMBOL>.<EXCHANGE>:intraday[:<interval>]} where interval
     *       is one of 1m / 5m / 1h (default 5m).</li>
     *   <li>Technical: {@code <SYMBOL>.<EXCHANGE>:technical:<function>[:<period>[:<from>[:<to>]]]}
     *       — function defaults to {@code rsi}, period defaults to 14.</li>
     *   <li>Macro: {@code <ISO3-COUNTRY>:macro:<indicator>} — symbol slot is the
     *       country code (e.g. USA, GBR, DEU); no .US suffix is appended.</li>
     * </ul>
     */
    static ParsedInput parseInput(String input) {
        String[] parts = input.split(":");
        String first = parts[0].trim();
        String endpoint = parts.length > 1 ? parts[1].trim().toLowerCase() : "all";
        if (endpoint.isEmpty()) endpoint = "all";

        if ("macro".equals(endpoint)) {
            ParsedInput p = new ParsedInput(first.toUpperCase(), "macro");
            p.country = first.toUpperCase();
            p.indicator = parts.length > 2 ? parts[2].trim() : "gdp_current_usd";
            return p;
        }

        // Symbol-style endpoints: normalize with .US suffix when missing
        String symbol = first.toUpperCase();
        if (!symbol.contains(".")) {
            symbol = symbol + ".US";
        }
        ParsedInput p = new ParsedInput(symbol, endpoint);

        switch (endpoint) {
            case "intraday":
                p.interval = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : "5m";
                break;
            case "technical":
                p.function = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim().toLowerCase() : "rsi";
                if (parts.length > 3 && !parts[3].isBlank()) {
                    try { p.period = Integer.parseInt(parts[3].trim()); }
                    catch (NumberFormatException ignored) { p.period = 14; }
                } else {
                    p.period = 14;
                }
                p.fromIso = parts.length > 4 && !parts[4].isBlank() ? parts[4].trim() : null;
                p.toIso   = parts.length > 5 && !parts[5].isBlank() ? parts[5].trim() : null;
                break;
            default:
                p.fromIso = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : null;
                p.toIso   = parts.length > 3 && !parts[3].isBlank() ? parts[3].trim() : null;
                break;
        }
        return p;
    }
}
