package ai.intelliswarm.swarmai.tool.common.finnhub;

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
 * Thin HTTP client for the Finnhub financial data API (https://finnhub.io/docs/api).
 *
 * <p>Finnhub's free tier is generous for this use case:
 * <ul>
 *   <li>{@code /stock/financials-reported} — income statement, balance sheet, cash flow per reporting period</li>
 *   <li>{@code /stock/insider-transactions} — per-insider share transactions with $ values</li>
 *   <li>{@code /stock/metric} — pre-computed ratios (P/E, P/B, ROE, margins)</li>
 *   <li>{@code /stock/profile2} — company profile (industry, country, IPO date)</li>
 * </ul>
 *
 * <p>Covers both domestic (AAPL) and foreign issuers (IMPP) with the same interface,
 * which is the key reason this tool is the financial-data path for the stock-analysis
 * workflow now that SEC-XBRL-only proved too brittle for foreign private issuers.
 *
 * <p>Rate limiting: free tier is 60 calls/minute. We don't throttle here — the tool
 * makes 4-5 calls per invocation which is well under the limit for one analysis at a
 * time. If the tool starts being invoked in a tight loop, add throttling.
 */
public class FinnhubClient {

    private static final Logger logger = LoggerFactory.getLogger(FinnhubClient.class);
    private static final String BASE_URL = "https://finnhub.io/api/v1";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public FinnhubClient(String apiKey) {
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"demo".equalsIgnoreCase(apiKey);
    }

    /**
     * Fetch company profile (industry, country, market cap, share outstanding, IPO date).
     * Returns null on failure; callers should handle absence gracefully.
     */
    public JsonNode fetchProfile(String ticker) {
        return get("/stock/profile2?symbol=" + ticker);
    }

    /**
     * Fetch pre-computed financial metrics (P/E, P/B, ROE, revenue growth, margins).
     * Returns the {@code metric} subtree, or null on failure.
     */
    public JsonNode fetchMetrics(String ticker) {
        JsonNode root = get("/stock/metric?symbol=" + ticker + "&metric=all");
        if (root == null || root.isMissingNode()) return null;
        return root.path("metric");
    }

    /**
     * Fetch reported annual income statement, balance sheet, and cash flow.
     * Returns the {@code data} array (newest first) or null on failure.
     */
    public JsonNode fetchAnnualFinancials(String ticker) {
        JsonNode root = get("/stock/financials-reported?symbol=" + ticker + "&freq=annual");
        if (root == null) return null;
        return root.path("data");
    }

    /**
     * Fetch reported quarterly income statement, balance sheet, and cash flow.
     */
    public JsonNode fetchQuarterlyFinancials(String ticker) {
        JsonNode root = get("/stock/financials-reported?symbol=" + ticker + "&freq=quarterly");
        if (root == null) return null;
        return root.path("data");
    }

    /**
     * Fetch insider transactions within a date range. Returns the {@code data} array.
     *
     * @param ticker  stock ticker
     * @param fromIso ISO-8601 date (e.g. "2025-01-01"); pass null for 90 days ago
     * @param toIso   ISO-8601 date (e.g. "2026-04-12"); pass null for today
     */
    public JsonNode fetchInsiderTransactions(String ticker, String fromIso, String toIso) {
        if (fromIso == null) {
            fromIso = java.time.LocalDate.now().minusDays(90).toString();
        }
        if (toIso == null) {
            toIso = java.time.LocalDate.now().toString();
        }
        JsonNode root = get(String.format("/stock/insider-transactions?symbol=%s&from=%s&to=%s",
                ticker, fromIso, toIso));
        if (root == null) return null;
        return root.path("data");
    }

    private JsonNode get(String pathAndQuery) {
        if (!isConfigured()) {
            logger.warn("Finnhub API key not configured — skipping call to {}", pathAndQuery);
            return null;
        }
        try {
            String url = BASE_URL + pathAndQuery + (pathAndQuery.contains("?") ? "&" : "?") + "token=" + apiKey;
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "SwarmAI-StockAnalysis/1.0 (contact@intelliswarm.ai)");
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.warn("Finnhub {} returned HTTP {}", pathAndQuery, response.getStatusCode());
                return null;
            }
            return objectMapper.readTree(response.getBody());
        } catch (HttpClientErrorException.Unauthorized e) {
            logger.warn("Finnhub 401 — API key rejected or scope issue for {}", pathAndQuery);
            return null;
        } catch (HttpClientErrorException.TooManyRequests e) {
            logger.warn("Finnhub 429 rate limited on {} — retry later", pathAndQuery);
            return null;
        } catch (HttpClientErrorException.NotFound e) {
            logger.info("Finnhub 404 — no data for {}", pathAndQuery);
            return null;
        } catch (Exception e) {
            logger.warn("Finnhub error on {}: {}", pathAndQuery, e.getMessage());
            return null;
        }
    }
}
