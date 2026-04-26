package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.common.eodhd.EodhdClient;
import ai.intelliswarm.swarmai.tool.common.eodhd.EodhdDiscoveryFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovery and calendar lookups against the EODHD API — the natural complement to
 * {@link EodhdMarketDataTool}, which is per-symbol. This tool finds symbols and events
 * rather than pulling data for a known ticker.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code search:<query>} — fuzzy symbol search across all exchanges</li>
 *   <li>{@code screener:<filters>[:<sort>]} — stock screener; filters use EODHD's
 *       JSON-array grammar (e.g. {@code [["market_capitalization",">",1000000000]]})</li>
 *   <li>{@code earnings[:<from>:<to>[:<symbols>]]} — earnings calendar</li>
 *   <li>{@code ipos[:<from>:<to>]} — IPO calendar</li>
 *   <li>{@code trends:<symbols>} — analyst rating trends history</li>
 *   <li>{@code economic[:<country2>[:<from>:<to>]]} — economic event calendar</li>
 * </ul>
 *
 * <p>When dates are omitted the tool defaults to "today → 30 days from today" for forward
 * calendars (earnings, IPOs, economic events).
 */
@Component
public class EodhdDiscoveryTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(EodhdDiscoveryTool.class);

    @Value("${eodhd.api-key:${EODHD_API_KEY:}}")
    private String apiKey;

    private volatile EodhdClient client;
    private final EodhdDiscoveryFormatter formatter = new EodhdDiscoveryFormatter();

    private EodhdClient client() {
        if (client == null) {
            client = new EodhdClient(apiKey);
        }
        return client;
    }

    public void setClientForTest(EodhdClient testClient) {
        this.client = testClient;
    }

    @Override
    public String getFunctionName() {
        return "eodhd_discovery";
    }

    @Override
    public String getDescription() {
        return "Symbol and event discovery via EODHD: fuzzy search across global tickers, " +
               "stock screener, earnings calendar, IPO calendar, analyst trends, and " +
               "economic-event calendar. Input: '<operation>[:<arg1>[:<arg2>[:<arg3>]]]'. " +
               "Examples: 'search:apple', 'earnings:2026-05-01:2026-05-31', " +
               "'ipos:2026-05-01:2026-05-31', 'trends:AAPL.US', 'economic:US', " +
               "'screener:[[\"market_capitalization\",\">\",1000000000]]:market_capitalization.desc'.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        Object rawInput = parameters.get("input");
        if (rawInput == null) {
            return "Error: input must be '<operation>[:<args>]' — e.g. 'search:apple', " +
                   "'earnings:2026-05-01:2026-05-31', 'trends:AAPL.US'.";
        }
        String input = String.valueOf(rawInput).trim();
        if (input.isBlank() || "null".equalsIgnoreCase(input)) {
            return "Error: input must not be blank.";
        }

        ParsedInput parsed = parseInput(input);
        logger.info("🔎 EodhdDiscoveryTool: operation={} args={}", parsed.operation, parsed.args);

        EodhdClient c = client();
        if (!c.isConfigured()) {
            return "## EODHD Discovery Unavailable\n\n" +
                   "EODHD_API_KEY is not configured. Set the env var or `eodhd.api-key` " +
                   "Spring property. Get a key at https://eodhd.com/.\n";
        }

        try {
            switch (parsed.operation) {
                case "search": {
                    String query = arg(parsed, 0, "");
                    if (query.isBlank()) return "Error: search requires a query — 'search:<query>'.";
                    return formatter.formatSearch(query, c.fetchSearch(query, 25));
                }
                case "screener": {
                    String filters = arg(parsed, 0, null);
                    String sort = arg(parsed, 1, null);
                    return formatter.formatScreener(filters, sort,
                            c.fetchScreener(filters, sort, 30, 0));
                }
                case "earnings": {
                    String from = arg(parsed, 0, LocalDate.now().toString());
                    String to = arg(parsed, 1, LocalDate.now().plusDays(30).toString());
                    String symbols = arg(parsed, 2, null);
                    return formatter.formatEarnings(from, to,
                            c.fetchCalendarEarnings(from, to, symbols));
                }
                case "ipos": {
                    String from = arg(parsed, 0, LocalDate.now().toString());
                    String to = arg(parsed, 1, LocalDate.now().plusDays(30).toString());
                    return formatter.formatIpos(from, to,
                            c.fetchCalendarIpos(from, to));
                }
                case "trends": {
                    String symbols = arg(parsed, 0, "");
                    if (symbols.isBlank()) return "Error: trends requires a symbol — 'trends:AAPL.US'.";
                    return formatter.formatTrends(symbols, c.fetchCalendarTrends(symbols));
                }
                case "economic": {
                    String country = arg(parsed, 0, null);
                    String from = arg(parsed, 1, LocalDate.now().toString());
                    String to = arg(parsed, 2, LocalDate.now().plusDays(14).toString());
                    return formatter.formatEconomicEvents(country, from, to,
                            c.fetchEconomicEvents(country, from, to, 50));
                }
                default:
                    return "Error: unknown operation '" + parsed.operation + "'. " +
                            "Use one of: search, screener, earnings, ipos, trends, economic.";
            }
        } catch (Exception e) {
            logger.error("EodhdDiscoveryTool failed for {}: {}", input, e.getMessage(), e);
            return "Error executing EODHD discovery '" + input + "': " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("input", Map.of(
                "type", "string",
                "description", "'<operation>[:<arg1>[:<arg2>[:<arg3>]]]'. Operations: " +
                        "search, screener, earnings, ipos, trends, economic. " +
                        "Examples: 'search:apple', 'earnings:2026-05-01:2026-05-31', " +
                        "'ipos', 'trends:AAPL.US', 'economic:US'."
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
        return "User wants to find tickers (search), filter the universe by criteria " +
               "(screener), or look up upcoming events — earnings releases, IPOs, " +
               "analyst rating shifts, or economic data prints.";
    }

    @Override
    public String getAvoidWhen() {
        return "User already has a specific ticker and needs price/fundamentals/dividends data " +
               "(use eodhd_market_data instead).";
    }

    @Override
    public String getCategory() { return "finance"; }

    @Override
    public List<String> getTags() {
        return List.of("finance", "discovery", "screener", "search", "calendar", "earnings",
                "ipos", "analyst-trends", "economic-events", "global-markets", "eodhd");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
                "type", "markdown",
                "description", "Markdown table of search results, screener hits, calendar entries, " +
                        "or event prints, depending on operation. Each block is tagged " +
                        "[EODHD: <endpoint>, <key>] for downstream citation."
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 16000;
    }

    public boolean isHealthy() {
        return client().isConfigured();
    }

    // ---------- input parsing ----------

    static class ParsedInput {
        String operation;
        List<String> args;

        ParsedInput(String operation, List<String> args) {
            this.operation = operation;
            this.args = args;
        }
    }

    static ParsedInput parseInput(String input) {
        // Split on the first ':' only for the operation, then keep the rest as a list of arg
        // segments split on ':' — but be mindful that screener filters legitimately contain ':' inside
        // brackets. For that case we keep filters as the first arg and only split on top-level ':'.
        int firstColon = input.indexOf(':');
        if (firstColon < 0) {
            return new ParsedInput(input.trim().toLowerCase(), List.of());
        }
        String op = input.substring(0, firstColon).trim().toLowerCase();
        String rest = input.substring(firstColon + 1);

        List<String> parts = splitTopLevel(rest);
        return new ParsedInput(op, parts);
    }

    /**
     * Split on ':' but respect bracket nesting so JSON-array filter strings stay intact.
     */
    private static List<String> splitTopLevel(String s) {
        List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            if (c == ':' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String arg(ParsedInput p, int idx, String def) {
        if (p.args == null || idx >= p.args.size()) return def;
        String v = p.args.get(idx);
        if (v == null) return def;
        v = v.trim();
        return v.isEmpty() ? def : v;
    }
}
