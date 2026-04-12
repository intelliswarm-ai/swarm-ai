package ai.intelliswarm.swarmai.tool.common.finance;

import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubClient;
import ai.intelliswarm.swarmai.tool.common.sec.SECApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pre-flight ticker validation — catches typos and unknown tickers BEFORE the caller
 * spends time and money running a multi-agent analysis on nothing. Was added after a
 * real incident where "APPL" (typo for AAPL) ran a full workflow and produced a
 * misleading SELL recommendation.
 *
 * <p>Validation strategy, in order:
 * <ol>
 *   <li>SEC EDGAR ticker→CIK (authoritative for US-listed equities)</li>
 *   <li>Finnhub profile (catches some foreign ADRs SEC doesn't index)</li>
 *   <li>If both miss, 1-edit-distance match against ~55 popular tickers to suggest a fix</li>
 * </ol>
 *
 * <p>Typical usage:
 * <pre>{@code
 * TickerValidator v = new TickerValidator(finnhubApiKey);
 * TickerValidator.Result r = v.validate("APPL");
 * if (!r.valid()) {
 *   throw new IllegalArgumentException(
 *       "Unknown ticker: " + r.ticker() +
 *       (r.suggestion() != null ? " (did you mean " + r.suggestion() + "?)" : ""));
 * }
 * // r.companyName() is populated when valid
 * }</pre>
 */
public class TickerValidator {

    private static final Logger logger = LoggerFactory.getLogger(TickerValidator.class);

    /** Validation outcome. {@code suggestion} is non-null only when {@code !valid} AND
     *  a 1-edit-distance match was found. {@code companyName} is populated when valid. */
    public record Result(String ticker, boolean valid, String companyName, String suggestion) {}

    /** Curated list of popular tickers used for near-match typo suggestions. */
    private static final String[] COMMON_TICKERS = {
            "AAPL", "MSFT", "GOOGL", "GOOG", "AMZN", "NVDA", "META", "TSLA", "NFLX", "ORCL",
            "CRM", "ADBE", "INTC", "AMD", "QCOM", "CSCO", "IBM", "UBER", "PYPL", "SQ",
            "JPM", "BAC", "WFC", "GS", "MS", "C", "V", "MA", "AXP", "BRK.B",
            "XOM", "CVX", "COP", "IMPP", "TEN", "DHT", "INSW", "FRO", "STNG",
            "JNJ", "UNH", "PFE", "LLY", "ABBV", "MRK", "ABT", "TMO",
            "WMT", "HD", "NKE", "SBUX", "MCD", "KO", "PEP", "PG", "DIS"
    };

    private final String finnhubApiKey;

    public TickerValidator(String finnhubApiKey) {
        this.finnhubApiKey = finnhubApiKey;
    }

    /** Checks SEC + Finnhub. Never throws — returns a {@link Result} object. */
    public Result validate(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return new Result(ticker, false, null, null);
        }
        String normalized = ticker.trim().toUpperCase();

        // 1. SEC is authoritative for US listings
        try {
            SECApiClient sec = new SECApiClient();
            String cik = sec.getCIKFromTicker(normalized);
            if (cik != null) {
                // Enrich with Finnhub name if available; not a blocker if it fails
                String name = null;
                try {
                    FinnhubClient fn = new FinnhubClient(finnhubApiKey);
                    if (fn.isConfigured()) {
                        JsonNode profile = fn.fetchProfile(normalized);
                        if (profile != null) name = profile.path("name").asText(null);
                    }
                } catch (Exception ignored) { /* non-fatal */ }
                return new Result(normalized, true, name != null ? name : "CIK=" + cik, null);
            }
        } catch (Exception e) {
            logger.warn("SEC CIK lookup failed during pre-flight for {}: {}", normalized, e.getMessage());
        }

        // 2. Finnhub secondary — catches some foreign ADRs
        try {
            FinnhubClient fn = new FinnhubClient(finnhubApiKey);
            if (fn.isConfigured()) {
                JsonNode profile = fn.fetchProfile(normalized);
                if (profile != null) {
                    String name = profile.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        return new Result(normalized, true, name, null);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Finnhub profile lookup failed during pre-flight for {}: {}", normalized, e.getMessage());
        }

        // 3. Both missed — try near-match suggestion
        return new Result(normalized, false, null, suggest(normalized));
    }

    /**
     * Throws {@link IllegalArgumentException} with a helpful message if the ticker is
     * invalid. Convenience wrapper for fail-fast callers who don't want to unpack a
     * {@link Result}.
     */
    public Result validateOrFail(String ticker) {
        Result r = validate(ticker);
        if (!r.valid()) {
            throw new IllegalArgumentException("Unknown ticker: " + r.ticker() +
                    (r.suggestion() != null ? " (did you mean " + r.suggestion() + "?)" : ""));
        }
        return r;
    }

    /** 1-edit-distance near-match against the curated popular-ticker list. */
    public String suggest(String input) {
        if (input == null || input.length() < 2) return null;
        String upper = input.toUpperCase();
        for (String c : COMMON_TICKERS) {
            if (editDistance(upper, c) == 1) return c;
        }
        return null;
    }

    /** Tiny Levenshtein distance, bounded — we only care about distance == 1. */
    private int editDistance(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) return 99;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
