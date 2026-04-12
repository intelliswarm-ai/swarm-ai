package ai.intelliswarm.swarmai.tool.common.finnhub;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders Finnhub JSON responses into dense, citation-carrying markdown sections
 * suitable for direct consumption by LLM agents.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Every numeric figure gets a {@code [Finnhub: metric, period, source]} citation
 *       so agents can quote it directly in their reports.</li>
 *   <li>Computed fields (YoY growth %, margins %) are calculated here so the LLM
 *       doesn't have to do arithmetic.</li>
 *   <li>Sections come out in priority order: key statistics → income statement →
 *       balance sheet → insider activity — same top-down structure SEC report uses.</li>
 * </ul>
 */
public class FinnhubReportFormatter {

    public String formatFullReport(String ticker,
                                    JsonNode profile,
                                    JsonNode metrics,
                                    JsonNode annualFinancials,
                                    JsonNode quarterlyFinancials,
                                    JsonNode insiderTransactions) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Finnhub Financial Data for ").append(ticker).append("\n\n");
        sb.append("_Source: Finnhub financial API. All figures are JSON-structured, not scraped from HTML. ")
                .append("Citations use [Finnhub: <metric>, <period>] format._\n\n");

        appendProfile(sb, ticker, profile);
        appendKeyMetrics(sb, ticker, metrics);
        appendAnnualIncomeStatement(sb, ticker, annualFinancials);
        appendAnnualBalanceSheet(sb, ticker, annualFinancials);
        appendAnnualCashFlow(sb, ticker, annualFinancials);
        appendQuarterlyRevenue(sb, ticker, quarterlyFinancials);
        appendInsiderTransactions(sb, ticker, insiderTransactions);

        return sb.toString();
    }

    // ---------- profile ----------

    private void appendProfile(StringBuilder sb, String ticker, JsonNode profile) {
        if (profile == null || profile.isMissingNode() || profile.isEmpty()) return;
        sb.append("## Company Profile\n\n");
        appendKV(sb, "Name", profile.path("name").asText(null));
        appendKV(sb, "Country", profile.path("country").asText(null));
        appendKV(sb, "Exchange", profile.path("exchange").asText(null));
        appendKV(sb, "Industry", profile.path("finnhubIndustry").asText(null));
        appendKV(sb, "IPO Date", profile.path("ipo").asText(null));
        if (profile.has("marketCapitalization")) {
            sb.append(String.format(Locale.US, "- **Market Cap:** $%.2fB  [Finnhub: marketCapitalization]%n",
                    profile.path("marketCapitalization").asDouble() / 1000.0));
        }
        if (profile.has("shareOutstanding")) {
            sb.append(String.format(Locale.US, "- **Shares Outstanding:** %.2fM  [Finnhub: shareOutstanding]%n",
                    profile.path("shareOutstanding").asDouble()));
        }
        sb.append("\n");
    }

    // ---------- key metrics (ratios) ----------

    private static final String[][] METRIC_ROWS = {
            {"peBasicExclExtraTTM", "P/E (TTM)"},
            {"peNormalizedAnnual", "P/E (annual)"},
            {"pbAnnual", "P/B (annual)"},
            {"psAnnual", "P/S (annual)"},
            {"roeTTM", "ROE (TTM)"},
            {"roaTTM", "ROA (TTM)"},
            {"currentRatioAnnual", "Current Ratio"},
            {"longTermDebt/equityAnnual", "LT Debt/Equity"},
            {"totalDebt/totalEquityAnnual", "Total Debt/Equity"},
            {"grossMarginTTM", "Gross Margin (TTM) %"},
            {"operatingMarginTTM", "Operating Margin (TTM) %"},
            {"netProfitMarginTTM", "Net Margin (TTM) %"},
            {"revenueGrowthTTMYoy", "Revenue YoY (TTM) %"},
            {"epsGrowthTTMYoy", "EPS YoY (TTM) %"},
            {"dividendYieldIndicatedAnnual", "Dividend Yield %"},
            {"52WeekHigh", "52-Week High $"},
            {"52WeekLow", "52-Week Low $"},
    };

    private void appendKeyMetrics(StringBuilder sb, String ticker, JsonNode metrics) {
        if (metrics == null || metrics.isMissingNode()) return;
        sb.append("## Key Metrics & Ratios\n\n");
        sb.append("| Metric | Value | Citation |\n|---|---|---|\n");
        int rowsEmitted = 0;
        for (String[] row : METRIC_ROWS) {
            String key = row[0];
            String label = row[1];
            JsonNode val = metrics.path(key);
            if (val.isMissingNode() || val.isNull()) continue;
            if (val.isNumber()) {
                sb.append(String.format(Locale.US, "| %s | %s | [Finnhub: metric.%s] |%n",
                        label, formatNumber(val.asDouble(), key), key));
                rowsEmitted++;
            }
        }
        if (rowsEmitted == 0) {
            sb.append("| _No pre-computed metrics returned for this ticker_ | | |\n");
        }
        sb.append("\n");
    }

    // ---------- income statement (annual) ----------

    private void appendAnnualIncomeStatement(StringBuilder sb, String ticker, JsonNode annualData) {
        if (annualData == null || !annualData.isArray() || annualData.isEmpty()) {
            // Explicit stub so downstream agents know Finnhub didn't cover this ticker rather
            // than silently omitting the section. Foreign ADRs (e.g. IMPP) often fall here —
            // the agent should then fall back to sec_filings XBRL data.
            sb.append("## Income Statement (annual)\n\n_Finnhub returned no reported " +
                    "financials for this ticker. This is common for foreign private issuers " +
                    "filing 20-F with SEC but not covered by Finnhub's free tier. ")
                    .append("Use the `sec_filings` tool's Key Financials (XBRL) section for ")
                    .append("revenue, net income, and margin data for ").append(ticker).append("._\n\n");
            return;
        }

        List<IncomeRow> rows = new ArrayList<>();
        for (JsonNode entry : annualData) {
            IncomeRow r = new IncomeRow();
            r.period = entry.path("year").asText(null) + " " + entry.path("form").asText("");
            r.endDate = entry.path("endDate").asText(null);
            r.revenue = findConcept(entry, "ic", new String[]{
                    "revenues", "Revenues", "TotalRevenues", "totalRevenue", "Sales",
                    "SalesRevenueNet", "RevenueFromContractWithCustomerExcludingAssessedTax"
            });
            r.netIncome = findConcept(entry, "ic", new String[]{
                    "NetIncomeLoss", "netIncome", "NetIncome", "ProfitLoss"
            });
            r.grossProfit = findConcept(entry, "ic", new String[]{
                    "GrossProfit", "grossProfit"
            });
            r.operatingIncome = findConcept(entry, "ic", new String[]{
                    "OperatingIncomeLoss", "operatingIncome"
            });
            r.costOfRevenue = findConcept(entry, "ic", new String[]{
                    "CostOfRevenue", "CostOfGoodsSold", "CostOfGoodsAndServicesSold"
            });
            if (r.revenue != null || r.netIncome != null) rows.add(r);
            if (rows.size() >= 5) break;
        }
        if (rows.isEmpty()) return;

        sb.append("## Income Statement (annual)\n\n");
        sb.append("| Period | Revenue | YoY % | Gross Margin % | Operating Margin % | Net Margin % | Net Income | EPS citation |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            IncomeRow r = rows.get(i);
            String yoy = "—";
            if (i + 1 < rows.size() && r.revenue != null && rows.get(i + 1).revenue != null) {
                double prev = rows.get(i + 1).revenue;
                if (prev != 0) yoy = pct((r.revenue - prev) / Math.abs(prev));
            }
            String gm = (r.grossProfit != null && r.revenue != null && r.revenue != 0)
                    ? pct(r.grossProfit / r.revenue) : "—";
            String om = (r.operatingIncome != null && r.revenue != null && r.revenue != 0)
                    ? pct(r.operatingIncome / r.revenue) : "—";
            String nm = (r.netIncome != null && r.revenue != null && r.revenue != 0)
                    ? pct(r.netIncome / r.revenue) : "—";
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s | [Finnhub: financials-reported, %s] |%n",
                    r.period, money(r.revenue), yoy, gm, om, nm, money(r.netIncome),
                    r.period));
        }
        sb.append("\n");
    }

    // ---------- balance sheet (annual) ----------

    private void appendAnnualBalanceSheet(StringBuilder sb, String ticker, JsonNode annualData) {
        if (annualData == null || !annualData.isArray() || annualData.isEmpty()) {
            // No stub here — the income-statement section already emitted a "fall back to
            // sec_filings" note; repeating it would be noise. Silent skip is OK because
            // the SEC tool's XBRL section covers the balance sheet.
            return;
        }
        sb.append("## Balance Sheet (most recent annual)\n\n");
        JsonNode latest = annualData.get(0);
        String period = latest.path("year").asText(null) + " " + latest.path("form").asText("");
        Double assets = findConcept(latest, "bs", new String[]{"Assets", "TotalAssets", "totalAssets"});
        Double liabilities = findConcept(latest, "bs", new String[]{"Liabilities", "TotalLiabilities", "totalLiabilities"});
        Double equity = findConcept(latest, "bs", new String[]{"StockholdersEquity", "totalStockholdersEquity"});
        Double cash = findConcept(latest, "bs", new String[]{"CashAndCashEquivalentsAtCarryingValue", "cashAndCashEquivalents"});
        Double longTermDebt = findConcept(latest, "bs", new String[]{"LongTermDebt", "longTermDebt"});

        appendBSLine(sb, "Total Assets", assets, period);
        appendBSLine(sb, "Total Liabilities", liabilities, period);
        appendBSLine(sb, "Stockholders' Equity", equity, period);
        appendBSLine(sb, "Cash & Equivalents", cash, period);
        appendBSLine(sb, "Long-Term Debt", longTermDebt, period);
        sb.append("\n");
    }

    // ---------- cash flow (annual) ----------

    private void appendAnnualCashFlow(StringBuilder sb, String ticker, JsonNode annualData) {
        if (annualData == null || !annualData.isArray() || annualData.isEmpty()) return;
        sb.append("## Cash Flow (most recent annual)\n\n");
        JsonNode latest = annualData.get(0);
        String period = latest.path("year").asText(null) + " " + latest.path("form").asText("");
        Double opCash = findConcept(latest, "cf", new String[]{
                "NetCashProvidedByUsedInOperatingActivities", "operatingCashFlow"
        });
        Double investCash = findConcept(latest, "cf", new String[]{
                "NetCashProvidedByUsedInInvestingActivities", "investingCashFlow"
        });
        Double financeCash = findConcept(latest, "cf", new String[]{
                "NetCashProvidedByUsedInFinancingActivities", "financingCashFlow"
        });
        appendBSLine(sb, "Operating Cash Flow", opCash, period);
        appendBSLine(sb, "Investing Cash Flow", investCash, period);
        appendBSLine(sb, "Financing Cash Flow", financeCash, period);
        sb.append("\n");
    }

    // ---------- quarterly revenue trend ----------

    private void appendQuarterlyRevenue(StringBuilder sb, String ticker, JsonNode quarterlyData) {
        if (quarterlyData == null || !quarterlyData.isArray() || quarterlyData.isEmpty()) return;
        List<IncomeRow> rows = new ArrayList<>();
        for (JsonNode entry : quarterlyData) {
            IncomeRow r = new IncomeRow();
            r.period = entry.path("quarter").asText("?") + " " + entry.path("year").asText(null);
            r.endDate = entry.path("endDate").asText(null);
            r.revenue = findConcept(entry, "ic", new String[]{
                    "Revenues", "TotalRevenues", "Sales", "SalesRevenueNet",
                    "RevenueFromContractWithCustomerExcludingAssessedTax"
            });
            if (r.revenue != null) rows.add(r);
            if (rows.size() >= 6) break;
        }
        if (rows.isEmpty()) return;
        sb.append("## Revenue (recent quarters)\n\n");
        sb.append("| Quarter | Revenue | QoQ | YoY | Citation |\n|---|---|---|---|---|\n");
        for (int i = 0; i < rows.size(); i++) {
            IncomeRow curr = rows.get(i);
            String qoq = "—", yoy = "—";
            if (i + 1 < rows.size() && rows.get(i + 1).revenue != null) {
                double prev = rows.get(i + 1).revenue;
                if (prev != 0) qoq = pct((curr.revenue - prev) / Math.abs(prev));
            }
            if (i + 4 < rows.size() && rows.get(i + 4).revenue != null) {
                double yago = rows.get(i + 4).revenue;
                if (yago != 0) yoy = pct((curr.revenue - yago) / Math.abs(yago));
            }
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | [Finnhub: financials-reported, %s] |%n",
                    curr.period, money(curr.revenue), qoq, yoy, curr.period));
        }
        sb.append("\n");
    }

    // ---------- insider transactions ----------

    private void appendInsiderTransactions(StringBuilder sb, String ticker, JsonNode insiderData) {
        if (insiderData == null || !insiderData.isArray() || insiderData.isEmpty()) {
            // For foreign private issuers this is often legitimate — they are exempt from
            // SEC Section 16 insider reporting (no Form 4). Make the "why" explicit so the
            // agent doesn't report it as a mysterious data gap.
            sb.append("## Insider Transactions (last 90 days)\n\n_No insider transactions " +
                    "reported for this ticker in the last 90 days. For foreign private issuers ")
                    .append("(filing 20-F / 6-K with SEC), this is often expected — they are " +
                            "generally exempt from Section 16 reporting and do not file Form 4._\n\n");
            return;
        }
        sb.append("## Insider Transactions (last 90 days)\n\n");

        double acquiredShares = 0, disposedShares = 0;
        double acquiredDollars = 0, disposedDollars = 0;
        int count = 0;
        StringBuilder rows = new StringBuilder();
        for (JsonNode tx : insiderData) {
            double shares = tx.path("change").asDouble(0);
            double price = tx.path("transactionPrice").asDouble(0);
            String name = tx.path("name").asText("");
            String txDate = tx.path("transactionDate").asText("");
            String code = tx.path("transactionCode").asText("");
            double txDollars = Math.abs(shares) * price;
            if (shares > 0) {
                acquiredShares += shares;
                acquiredDollars += txDollars;
            } else if (shares < 0) {
                disposedShares += Math.abs(shares);
                disposedDollars += txDollars;
            }
            rows.append(String.format(Locale.US, "| %s | %s | %s | %,.0f | $%.2f | $%.2fK |%n",
                    txDate, name, code, shares, price, txDollars / 1000.0));
            count++;
            if (count >= 25) break; // cap for prompt size
        }
        double netDollars = acquiredDollars - disposedDollars;

        sb.append(String.format(Locale.US,
                "- **Net insider $ flow:** %s  (acquired %s − disposed %s)  [Finnhub: insider-transactions]%n",
                signedDollars(netDollars), dollars(acquiredDollars), dollars(disposedDollars)));
        sb.append(String.format(Locale.US,
                "- **Transaction count:** %d  (acquired shares %s, disposed shares %s)%n%n",
                count, shares(acquiredShares), shares(disposedShares)));

        sb.append("| Tx Date | Insider | Code | Shares Δ | Price | $ Value |\n|---|---|---|---|---|---|\n");
        sb.append(rows);
        sb.append("\n_Cite rows as [Finnhub: insider-transactions, <tx-date>]._\n\n");
    }

    // ---------- helpers ----------

    private static class IncomeRow {
        String period;
        String endDate;
        Double revenue;
        Double netIncome;
        Double grossProfit;
        Double operatingIncome;
        Double costOfRevenue;
    }

    /**
     * Finnhub's {@code financials-reported} returns a nested structure:
     * {@code report.ic[]}, {@code report.bs[]}, {@code report.cf[]} where each item has
     * {@code concept} and {@code value}. This helper finds the first matching concept.
     */
    private Double findConcept(JsonNode entry, String statement, String[] conceptNames) {
        JsonNode report = entry.path("report");
        JsonNode statementNode = report.path(statement);
        if (!statementNode.isArray()) return null;
        for (String concept : conceptNames) {
            for (JsonNode item : statementNode) {
                String itemConcept = item.path("concept").asText("");
                String itemLabel = item.path("label").asText("");
                if (itemConcept.equalsIgnoreCase(concept) || itemLabel.equalsIgnoreCase(concept)
                        || itemConcept.toLowerCase(Locale.US).contains(concept.toLowerCase(Locale.US))) {
                    if (item.has("value") && item.get("value").isNumber()) {
                        return item.get("value").asDouble();
                    }
                }
            }
        }
        return null;
    }

    private void appendKV(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) return;
        sb.append("- **").append(key).append(":** ").append(value).append("\n");
    }

    private void appendBSLine(StringBuilder sb, String label, Double value, String period) {
        if (value == null) return;
        sb.append(String.format(Locale.US, "- **%s:** %s  [Finnhub: financials-reported.bs, %s]%n",
                label, money(value), period));
    }

    private String money(Double v) {
        if (v == null) return "—";
        double abs = Math.abs(v);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%s$%.2fB", v < 0 ? "−" : "", abs / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.US, "%s$%.2fM", v < 0 ? "−" : "", abs / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.US, "%s$%.2fK", v < 0 ? "−" : "", abs / 1_000.0);
        return String.format(Locale.US, "%s$%.2f", v < 0 ? "−" : "", abs);
    }

    private String dollars(double v) {
        return money(v);
    }

    private String signedDollars(double v) {
        String sign = v > 0 ? "+" : v < 0 ? "−" : "";
        return sign + money(Math.abs(v)) + (v > 0 ? " (net buying)" : v < 0 ? " (net selling)" : " (flat)");
    }

    private String shares(double s) {
        return String.format(Locale.US, "%,.0f", s);
    }

    private String pct(double ratio) {
        return String.format(Locale.US, "%+.1f%%", ratio * 100);
    }

    private String formatNumber(double v, String key) {
        // Metrics that are already percentages from Finnhub (margins, growth) come as raw
        // percentage values; dollar values in key stats are raw numbers in $.
        if (key.toLowerCase(Locale.US).contains("margin")
                || key.toLowerCase(Locale.US).contains("growth")
                || key.toLowerCase(Locale.US).contains("yield")
                || key.toLowerCase(Locale.US).contains("roe")
                || key.toLowerCase(Locale.US).contains("roa")) {
            return String.format(Locale.US, "%.2f%%", v);
        }
        if (key.toLowerCase(Locale.US).contains("52week")) {
            return String.format(Locale.US, "$%.2f", v);
        }
        return String.format(Locale.US, "%.2f", v);
    }
}
