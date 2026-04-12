package ai.intelliswarm.swarmai.tool.common.finance;

import ai.intelliswarm.swarmai.tool.common.finnhub.FinnhubClient;
import ai.intelliswarm.swarmai.tool.common.sec.CompanyFacts;
import ai.intelliswarm.swarmai.tool.common.sec.SECApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Builds an authoritative {@link FactCard} for a ticker by combining:
 * <ul>
 *   <li>Finnhub metrics API — pre-computed ratios (P/E, P/B, ROE, margins TTM,
 *       revenue growth TTM, 52-week range, market cap).</li>
 *   <li>SEC XBRL companyfacts API — multi-year annual series for revenue,
 *       net income, assets, liabilities, equity, operating cash flow.</li>
 * </ul>
 *
 * <p>The resulting fact card is the "last-mile defense" against multi-agent LLM
 * laziness — analyst prompts embed it verbatim with strict "quote this value"
 * instructions, so even models that tend to emit "DATA NOT AVAILABLE" can't skip
 * over values that are clearly present in the evidence.
 *
 * <p>Usage:
 * <pre>{@code
 * FactCardBuilder builder = new FactCardBuilder(finnhubApiKey);
 * FactCard card = builder.build("AAPL");
 * String markdown = card.toMarkdown();  // for LLM prompt
 * List<FactCard.Row> rows = card.rows();  // for post-processing
 * }</pre>
 *
 * <p>Stateless and thread-safe. API keys are held only as configuration — no mutable state.
 */
public class FactCardBuilder {

    private static final Logger logger = LoggerFactory.getLogger(FactCardBuilder.class);

    private final String finnhubApiKey;

    public FactCardBuilder(String finnhubApiKey) {
        this.finnhubApiKey = finnhubApiKey;
    }

    /**
     * Builds a complete fact card for the given ticker. Never throws — if a data
     * source is unreachable or returns nothing, the corresponding rows show
     * "— not reported —" so the card is always renderable.
     */
    public FactCard build(String ticker) {
        FactCard card = new FactCard(ticker);
        appendFinnhubSection(card, ticker);
        appendSecXbrlSection(card, ticker);
        return card;
    }

    // ---------- Finnhub (profile + metrics) ----------

    private void appendFinnhubSection(FactCard card, String ticker) {
        FinnhubClient finnhub = new FinnhubClient(finnhubApiKey);
        if (!finnhub.isConfigured()) {
            card.add("_Finnhub_", "FINNHUB_API_KEY not configured", "—");
            return;
        }
        try {
            JsonNode profile = finnhub.fetchProfile(ticker);
            JsonNode metrics = finnhub.fetchMetrics(ticker);

            appendProfileRow(card, profile, "Market Cap", "marketCapitalization", "M");
            card.add("Country / HQ", text(profile, "country"), "[Finnhub: profile2.country]");
            card.add("Industry", text(profile, "finnhubIndustry"), "[Finnhub: profile2.finnhubIndustry]");

            appendPct(card, metrics, "Gross Margin (TTM)", "grossMarginTTM");
            appendPct(card, metrics, "Operating Margin (TTM)", "operatingMarginTTM");
            appendPct(card, metrics, "Net Margin (TTM)", "netProfitMarginTTM");
            appendPct(card, metrics, "Revenue YoY (TTM)", "revenueGrowthTTMYoy");
            appendPct(card, metrics, "EPS YoY (TTM)", "epsGrowthTTMYoy");
            appendPct(card, metrics, "ROE (TTM)", "roeTTM");
            appendPct(card, metrics, "ROA (TTM)", "roaTTM");
            appendNum(card, metrics, "P/E (TTM)", "peBasicExclExtraTTM");
            appendNum(card, metrics, "P/B (annual)", "pbAnnual");
            appendNum(card, metrics, "Current Ratio", "currentRatioAnnual");
            appendNum(card, metrics, "Total Debt/Equity", "totalDebt/totalEquityAnnual");
            appendPct(card, metrics, "Dividend Yield", "dividendYieldIndicatedAnnual");
            appendDollar(card, metrics, "52-Week High", "52WeekHigh");
            appendDollar(card, metrics, "52-Week Low", "52WeekLow");
        } catch (Exception e) {
            logger.warn("FactCardBuilder: Finnhub fetch failed for {}: {}", ticker, e.getMessage());
            card.add("_Finnhub_", "fetch error: " + e.getMessage(), "—");
        }
    }

    // ---------- SEC XBRL companyfacts ----------

    private static final String[] REVENUE_CONCEPTS = {
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "Revenues", "SalesRevenueNet", "Revenue"
    };
    private static final String[] NET_INCOME_CONCEPTS = {"NetIncomeLoss", "ProfitLoss"};
    private static final String[] ASSETS_CONCEPTS = {"Assets"};
    private static final String[] LIABILITIES_CONCEPTS = {"Liabilities"};
    private static final String[] EQUITY_CONCEPTS = {
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"
    };
    private static final String[] OP_CASH_FLOW_CONCEPTS = {
            "NetCashProvidedByUsedInOperatingActivities"
    };

    private void appendSecXbrlSection(FactCard card, String ticker) {
        try {
            SECApiClient client = new SECApiClient();
            String cik = client.getCIKFromTicker(ticker);
            if (cik == null) {
                card.add("_SEC XBRL_", "no CIK found for ticker", "—");
                return;
            }
            CompanyFacts facts = client.fetchCompanyFacts(cik);
            if (facts == null) {
                card.add("_SEC XBRL_", "companyfacts unavailable", "—");
                return;
            }

            String revConcept = firstPresentAnnual(facts, REVENUE_CONCEPTS);
            appendLatestAnnual(card, facts, "Revenue (latest annual)", revConcept);
            appendPriorAnnual(card, facts, "Revenue (prior annual)", revConcept);

            String niConcept = firstPresentAnnual(facts, NET_INCOME_CONCEPTS);
            appendLatestAnnual(card, facts, "Net Income (latest annual)", niConcept);
            appendPriorAnnual(card, facts, "Net Income (prior annual)", niConcept);

            appendLatestAnnual(card, facts, "Total Assets (latest annual)",
                    firstPresentAnnual(facts, ASSETS_CONCEPTS));
            appendLatestAnnual(card, facts, "Total Liabilities (latest annual)",
                    firstPresentAnnual(facts, LIABILITIES_CONCEPTS));
            appendLatestAnnual(card, facts, "Stockholders Equity (latest annual)",
                    firstPresentAnnual(facts, EQUITY_CONCEPTS));
            appendLatestAnnual(card, facts, "Operating Cash Flow (latest annual)",
                    firstPresentAnnual(facts, OP_CASH_FLOW_CONCEPTS));
        } catch (Exception e) {
            logger.warn("FactCardBuilder: SEC fetch failed for {}: {}", ticker, e.getMessage());
            card.add("_SEC XBRL_", "fetch error: " + e.getMessage(), "—");
        }
    }

    private String firstPresentAnnual(CompanyFacts facts, String[] concepts) {
        for (String c : concepts) {
            if (!facts.getRecentAnnual(c, 1).isEmpty()) return c;
        }
        return null;
    }

    private void appendLatestAnnual(FactCard card, CompanyFacts facts, String label, String concept) {
        if (concept == null) {
            card.add(label, null, "[XBRL: —]");
            return;
        }
        List<CompanyFacts.Fact> recent = facts.getRecentAnnual(concept, 1);
        if (recent.isEmpty()) {
            card.add(label, null, "[XBRL: " + concept + "]");
            return;
        }
        CompanyFacts.Fact f = recent.get(0);
        card.add(label, formatMoney(f.value()),
                "[XBRL: " + concept + ", " + f.label() + ", " + nullSafe(f.form()) + "]");
    }

    private void appendPriorAnnual(FactCard card, CompanyFacts facts, String label, String concept) {
        if (concept == null) {
            card.add(label, null, "[XBRL: —]");
            return;
        }
        List<CompanyFacts.Fact> recent = facts.getRecentAnnual(concept, 2);
        if (recent.size() < 2) {
            card.add(label, null, "[XBRL: " + concept + "]");
            return;
        }
        CompanyFacts.Fact f = recent.get(1);
        card.add(label, formatMoney(f.value()),
                "[XBRL: " + concept + ", " + f.label() + ", " + nullSafe(f.form()) + "]");
    }

    // ---------- Finnhub helpers ----------

    private void appendProfileRow(FactCard card, JsonNode profile, String label, String key, String suffix) {
        if (profile == null || !profile.has(key) || !profile.get(key).isNumber()) {
            card.add(label, null, "[Finnhub: profile2." + key + "]");
            return;
        }
        card.add(label, String.format(Locale.US, "%.2f%s", profile.get(key).asDouble(), suffix),
                "[Finnhub: profile2." + key + "]");
    }

    private void appendPct(FactCard card, JsonNode metrics, String label, String key) {
        Double v = num(metrics, key);
        if (v == null) {
            card.add(label, null, "[Finnhub: metric." + key + "]");
            return;
        }
        card.add(label, String.format(Locale.US, "%.2f%%", v), "[Finnhub: metric." + key + "]");
    }

    private void appendNum(FactCard card, JsonNode metrics, String label, String key) {
        Double v = num(metrics, key);
        if (v == null) {
            card.add(label, null, "[Finnhub: metric." + key + "]");
            return;
        }
        card.add(label, String.format(Locale.US, "%.2f", v), "[Finnhub: metric." + key + "]");
    }

    private void appendDollar(FactCard card, JsonNode metrics, String label, String key) {
        Double v = num(metrics, key);
        if (v == null) {
            card.add(label, null, "[Finnhub: metric." + key + "]");
            return;
        }
        card.add(label, String.format(Locale.US, "$%.2f", v), "[Finnhub: metric." + key + "]");
    }

    private Double num(JsonNode metrics, String key) {
        if (metrics == null || metrics.isMissingNode()) return null;
        JsonNode v = metrics.path(key);
        return v.isNumber() ? v.asDouble() : null;
    }

    private String text(JsonNode profile, String key) {
        if (profile == null || profile.isMissingNode()) return null;
        String v = profile.path(key).asText(null);
        return (v == null || v.isEmpty()) ? null : v;
    }

    private String formatMoney(double v) {
        double abs = Math.abs(v);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "$%.2fB", v / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.US, "$%.2fM", v / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.US, "$%.2fK", v / 1_000.0);
        return String.format(Locale.US, "$%.2f", v);
    }

    private String nullSafe(String s) { return s != null ? s : "—"; }
}
