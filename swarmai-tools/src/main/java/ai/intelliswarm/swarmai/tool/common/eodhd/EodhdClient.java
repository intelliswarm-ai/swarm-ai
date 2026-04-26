package ai.intelliswarm.swarmai.tool.common.eodhd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Thin HTTP client for the EODHD financial data API ({@code https://eodhd.com/}).
 *
 * <p>EODHD complements Finnhub by adding global coverage — 60+ exchanges, deep historical
 * end-of-day OHLCV, dividends/splits going back decades, macro indicators by country, and
 * technical indicators computed server-side. Where Finnhub focuses on US-issuer ratios and
 * insider activity, EODHD is the path for international tickers (LSE, XETRA, SW, TSE, etc.)
 * and long-window time series.
 *
 * <p>Endpoints wrapped:
 * <ul>
 *   <li>{@code /api/eod/{symbol}} — historical end-of-day OHLCV (JSON)</li>
 *   <li>{@code /api/real-time/{symbol}} — latest quote</li>
 *   <li>{@code /api/fundamentals/{symbol}} — company fundamentals</li>
 *   <li>{@code /api/div/{symbol}} — dividend history</li>
 *   <li>{@code /api/splits/{symbol}} — split history</li>
 *   <li>{@code /api/news} — financial news</li>
 *   <li>{@code /api/exchanges-list/} — supported exchanges</li>
 *   <li>{@code /api/exchange-symbol-list/{exchange}} — symbols on an exchange</li>
 * </ul>
 *
 * <p>Auth pattern: every request appends {@code api_token=<key>} as a query parameter (no
 * custom header). All responses are requested as JSON via {@code fmt=json}.
 *
 * <p>This client returns {@code null} on any failure — callers handle absence gracefully
 * instead of dealing with exceptions. Rate limiting is left to the caller; EODHD's free
 * tier permits 20 requests/day, paid tiers 100k+/day.
 */
public class EodhdClient {

    private static final Logger logger = LoggerFactory.getLogger(EodhdClient.class);
    private static final String BASE_URL = "https://eodhd.com/api";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public EodhdClient(String apiKey) {
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"demo".equalsIgnoreCase(apiKey);
    }

    /**
     * Fetch historical end-of-day OHLCV for a symbol.
     *
     * @param symbol e.g. "AAPL.US", "BMW.XETRA", "VOD.LSE"
     * @param fromIso ISO date e.g. "2024-01-01"; null for earliest available
     * @param toIso ISO date e.g. "2026-04-26"; null for today
     * @param period "d" (daily), "w" (weekly), or "m" (monthly); null = daily
     */
    public JsonNode fetchEod(String symbol, String fromIso, String toIso, String period) {
        StringBuilder path = new StringBuilder("/eod/").append(symbol).append("?fmt=json");
        if (fromIso != null) path.append("&from=").append(fromIso);
        if (toIso != null) path.append("&to=").append(toIso);
        if (period != null) path.append("&period=").append(period);
        return get(path.toString());
    }

    /** Fetch the latest quote (real-time / delayed). */
    public JsonNode fetchRealTime(String symbol) {
        return get("/real-time/" + symbol + "?fmt=json");
    }

    /** Fetch full fundamentals (profile, highlights, financials block, valuation). */
    public JsonNode fetchFundamentals(String symbol) {
        return get("/fundamentals/" + symbol + "?fmt=json");
    }

    /** Fetch dividend history (defaults to all available history if {@code fromIso} is null). */
    public JsonNode fetchDividends(String symbol, String fromIso) {
        StringBuilder path = new StringBuilder("/div/").append(symbol).append("?fmt=json");
        if (fromIso != null) path.append("&from=").append(fromIso);
        return get(path.toString());
    }

    /** Fetch split history. */
    public JsonNode fetchSplits(String symbol, String fromIso) {
        StringBuilder path = new StringBuilder("/splits/").append(symbol).append("?fmt=json");
        if (fromIso != null) path.append("&from=").append(fromIso);
        return get(path.toString());
    }

    /**
     * Fetch news for a symbol (or general financial news when {@code symbol} is null).
     *
     * @param symbol optional ticker filter (e.g. "AAPL.US")
     * @param limit max number of articles (EODHD caps at 1000; tool defaults to 20)
     */
    public JsonNode fetchNews(String symbol, int limit) {
        StringBuilder path = new StringBuilder("/news?fmt=json&limit=").append(limit);
        if (symbol != null && !symbol.isBlank()) path.append("&s=").append(symbol);
        return get(path.toString());
    }

    /** List all exchanges EODHD covers. */
    public JsonNode fetchExchangesList() {
        return get("/exchanges-list/?fmt=json");
    }

    /** List all symbols traded on a given exchange code (e.g. "US", "LSE", "XETRA"). */
    public JsonNode fetchExchangeSymbolList(String exchangeCode) {
        return get("/exchange-symbol-list/" + exchangeCode + "?fmt=json");
    }

    // ---------- intraday / technical / macro ----------

    /**
     * Fetch intraday OHLCV bars.
     *
     * @param symbol  e.g. "AAPL.US"
     * @param interval one of "1m", "5m", "1h" (EODHD's supported intervals)
     * @param fromUnix optional Unix epoch seconds; null lets EODHD pick the default window
     * @param toUnix   optional Unix epoch seconds; null lets EODHD pick the default window
     */
    public JsonNode fetchIntraday(String symbol, String interval, Long fromUnix, Long toUnix) {
        StringBuilder path = new StringBuilder("/intraday/").append(symbol).append("?fmt=json");
        if (interval != null) path.append("&interval=").append(interval);
        if (fromUnix != null) path.append("&from=").append(fromUnix);
        if (toUnix != null) path.append("&to=").append(toUnix);
        return get(path.toString());
    }

    /**
     * Fetch a server-computed technical indicator series.
     *
     * @param symbol   e.g. "AAPL.US"
     * @param function indicator name: rsi, macd, sma, ema, wma, bbands, stochastic, stddev,
     *                 slope, volatility, atr, adx, cci, dmi, sar, splitadjusted, avgvol, avgvolccy
     * @param period   indicator period (e.g. 14 for RSI); pass null for EODHD default
     * @param fromIso  optional ISO date
     * @param toIso    optional ISO date
     */
    public JsonNode fetchTechnical(String symbol, String function, Integer period,
                                    String fromIso, String toIso) {
        StringBuilder path = new StringBuilder("/technical/").append(symbol).append("?fmt=json");
        if (function != null) path.append("&function=").append(function);
        if (period != null) path.append("&period=").append(period);
        if (fromIso != null) path.append("&from=").append(fromIso);
        if (toIso != null) path.append("&to=").append(toIso);
        return get(path.toString());
    }

    /**
     * Fetch a macro economic indicator series for a country.
     *
     * @param country   ISO-3 country code (e.g. "USA", "GBR", "DEU")
     * @param indicator EODHD indicator key (e.g. "gdp_current_usd",
     *                  "inflation_consumer_prices_annual", "real_interest_rate",
     *                  "unemployment_total_percent", "population_total")
     */
    public JsonNode fetchMacroIndicator(String country, String indicator) {
        StringBuilder path = new StringBuilder("/macro-indicator/").append(country).append("?fmt=json");
        if (indicator != null) path.append("&indicator=").append(indicator);
        return get(path.toString());
    }

    // ---------- discovery ----------

    /** Symbol search across all exchanges. Limit caps the result count (EODHD max ~50). */
    public JsonNode fetchSearch(String query, int limit) {
        return get("/search/" + urlEncode(query) + "?limit=" + limit + "&fmt=json");
    }

    /**
     * Stock screener.
     *
     * @param filters JSON-array string per EODHD's grammar, e.g.
     *                {@code [["market_capitalization",">",1000000000],["sector","=","Technology"]]}
     *                Pass null to skip the filter.
     * @param sort    sort key (e.g. "market_capitalization.desc"); null for default
     * @param limit   results per page (1-100); 50 is a reasonable default
     * @param offset  pagination offset
     */
    public JsonNode fetchScreener(String filters, String sort, int limit, int offset) {
        StringBuilder path = new StringBuilder("/screener?fmt=json&limit=").append(limit)
                .append("&offset=").append(offset);
        if (filters != null && !filters.isBlank()) {
            path.append("&filters=").append(urlEncode(filters));
        }
        if (sort != null && !sort.isBlank()) {
            path.append("&sort=").append(sort);
        }
        return get(path.toString());
    }

    /** Earnings calendar between two ISO dates. {@code symbols} optional comma-separated filter. */
    public JsonNode fetchCalendarEarnings(String fromIso, String toIso, String symbols) {
        StringBuilder path = new StringBuilder("/calendar/earnings?fmt=json");
        if (fromIso != null) path.append("&from=").append(fromIso);
        if (toIso != null) path.append("&to=").append(toIso);
        if (symbols != null && !symbols.isBlank()) path.append("&symbols=").append(symbols);
        return get(path.toString());
    }

    /** Upcoming IPOs between two ISO dates. */
    public JsonNode fetchCalendarIpos(String fromIso, String toIso) {
        StringBuilder path = new StringBuilder("/calendar/ipos?fmt=json");
        if (fromIso != null) path.append("&from=").append(fromIso);
        if (toIso != null) path.append("&to=").append(toIso);
        return get(path.toString());
    }

    /** Analyst trends history for a symbol (rating distribution by month). */
    public JsonNode fetchCalendarTrends(String symbols) {
        StringBuilder path = new StringBuilder("/calendar/trends?fmt=json");
        if (symbols != null && !symbols.isBlank()) path.append("&symbols=").append(symbols);
        return get(path.toString());
    }

    /**
     * Economic events calendar.
     *
     * @param country ISO-2 country code (e.g. "US", "GB", "DE"); null for all countries
     * @param fromIso optional ISO date
     * @param toIso   optional ISO date
     * @param limit   results to return (default 50)
     */
    public JsonNode fetchEconomicEvents(String country, String fromIso, String toIso, int limit) {
        StringBuilder path = new StringBuilder("/economic-events?fmt=json&limit=").append(limit);
        if (country != null && !country.isBlank()) path.append("&country=").append(country);
        if (fromIso != null) path.append("&from=").append(fromIso);
        if (toIso != null) path.append("&to=").append(toIso);
        return get(path.toString());
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private JsonNode get(String pathAndQuery) {
        if (!isConfigured()) {
            logger.warn("EODHD API key not configured — skipping call to {}", pathAndQuery);
            return null;
        }
        try {
            String url = BASE_URL + pathAndQuery
                    + (pathAndQuery.contains("?") ? "&" : "?") + "api_token=" + apiKey;
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "SwarmAI/1.0 (contact@intelliswarm.ai)");
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.warn("EODHD {} returned HTTP {}", pathAndQuery, response.getStatusCode());
                return null;
            }
            return objectMapper.readTree(response.getBody());
        } catch (HttpClientErrorException.Unauthorized e) {
            logger.warn("EODHD 401 — API key rejected for {}", pathAndQuery);
            return null;
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("EODHD 429 rate limited on {} — retry later", pathAndQuery);
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            logger.info("EODHD 404 — no data for {}", pathAndQuery);
            return null;
        } catch (Exception e) {
            logger.warn("EODHD error on {}: {}", pathAndQuery, e.getMessage());
            return null;
        }
    }
}
