package ai.intelliswarm.swarmai.tool.common.sec;

import java.util.List;
import java.util.Locale;

/**
 * Formats {@link CompanyFacts} into a dense, LLM-friendly markdown section.
 *
 * <p>Every line is cited with "[XBRL: Concept, Period, FormType, accession-free]" so
 * downstream agents can trust and quote the numbers. Computes YoY growth and common
 * margins from the raw XBRL facts so agents don't have to do arithmetic.
 */
public class FinancialFactsFormatter {

    // Concepts we try in order; the first one that has data wins. SEC filers use
    // different concept names depending on accounting standard (us-gaap for domestic,
    // ifrs-full for foreign private issuers like IMPP filing 20-F). SECApiClient ingests
    // both namespaces: us-gaap concepts are stored under their bare name, ifrs-full
    // concepts under "ifrs-full:<Concept>" if there's a collision, otherwise bare.
    private static final String[] REVENUE_CONCEPTS = {
            // US GAAP — most common first, then fall-backs.
            // Both "Including" and "Excluding AssessedTax" variants exist in real filings;
            // omitting "Including" previously caused IMPP (Imperial Petroleum) to render
            // no revenue despite having 20 FY observations under that exact concept.
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "RevenueFromContractWithCustomerIncludingAssessedTax",
            "Revenues",
            "SalesRevenueNet",
            "SalesRevenueGoodsNet",
            "SalesRevenueServicesNet",
            "Revenue",
            // IFRS
            "ifrs-full:Revenue",
            "ifrs-full:RevenueFromContractsWithCustomers"
    };
    private static final String[] NET_INCOME_CONCEPTS = {
            // US GAAP
            "NetIncomeLoss",
            "NetIncomeLossAvailableToCommonStockholdersBasic",
            "ProfitLoss",
            // IFRS
            "ifrs-full:ProfitLoss",
            "ifrs-full:ProfitLossAttributableToOwnersOfParent",
            "ifrs-full:ProfitLossAttributableToOrdinaryEquityHoldersOfParent"
    };
    private static final String[] OP_INCOME_CONCEPTS = {
            "OperatingIncomeLoss",
            "IncomeLossFromContinuingOperations",
            "ifrs-full:ProfitLossFromOperatingActivities"
    };
    private static final String[] GROSS_PROFIT_CONCEPTS = {
            "GrossProfit",
            "ifrs-full:GrossProfit"
    };
    private static final String[] COGS_CONCEPTS = {
            "CostOfRevenue",
            "CostOfGoodsSold",                        // very common bare concept
            "CostOfGoodsAndServicesSold",
            "CostOfServices",
            "ifrs-full:CostOfSales"
    };
    private static final String[] EPS_BASIC_CONCEPTS = {
            "EarningsPerShareBasic",
            "IncomeLossFromContinuingOperationsPerBasicShare",
            "ifrs-full:BasicEarningsLossPerShare"
    };
    private static final String[] EPS_DILUTED_CONCEPTS = {
            "EarningsPerShareDiluted",
            "IncomeLossFromContinuingOperationsPerDilutedShare",
            "ifrs-full:DilutedEarningsLossPerShare"
    };
    private static final String[] CASH_FLOW_OPS_CONCEPTS = {
            "NetCashProvidedByUsedInOperatingActivities",
            "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
            "CashFlowsFromUsedInOperations",
            "ifrs-full:CashFlowsFromUsedInOperatingActivities"
    };
    private static final String[] ASSETS_CONCEPTS = {
            "Assets",
            "ifrs-full:Assets"
    };
    private static final String[] LIABILITIES_CONCEPTS = {
            "Liabilities",
            "LiabilitiesAndStockholdersEquity",        // some 20-F filers only report combined
            "ifrs-full:Liabilities"
    };
    private static final String[] EQUITY_CONCEPTS = {
            "StockholdersEquity",
            "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest",
            "ifrs-full:Equity",
            "ifrs-full:EquityAttributableToOwnersOfParent",
            "ifrs-full:IssuedCapital"
    };
    private static final String[] CASH_CONCEPTS = {
            "CashAndCashEquivalentsAtCarryingValue",
            "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents",  // post-2018 ASU
            "Cash",
            "ifrs-full:CashAndCashEquivalents"
    };

    /**
     * Build the markdown "Key Financials" section. Returns empty string if the
     * companyfacts API returned nothing usable (e.g. foreign issuer without us-gaap).
     */
    public String format(CompanyFacts facts) {
        if (facts == null || facts.getAllConcepts().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Key Financials (XBRL)\n\n");
        sb.append("_Source: SEC XBRL companyfacts API — structured data extracted directly from filings. ")
                .append("All values cited with [XBRL: Concept, Period, Form]._\n\n");

        // === Annual revenue trend with YoY growth ===
        List<CompanyFacts.Fact> annualRevenue = pickAnnualSeries(facts, REVENUE_CONCEPTS, 5);
        String revenueConcept = conceptWithData(facts, REVENUE_CONCEPTS, "FY");
        if (!annualRevenue.isEmpty() && revenueConcept != null) {
            sb.append("### Revenue (annual)\n\n");
            sb.append(tableHeader("Period", "Revenue", "YoY Growth"));
            for (int i = 0; i < annualRevenue.size(); i++) {
                CompanyFacts.Fact curr = annualRevenue.get(i);
                String yoy = "—";
                if (i + 1 < annualRevenue.size()) {
                    double prev = annualRevenue.get(i + 1).value();
                    if (prev != 0.0) {
                        yoy = pct((curr.value() - prev) / Math.abs(prev));
                    }
                }
                sb.append(String.format("| %s | %s | %s |%n",
                        curr.label(), money(curr.value(), curr.unit()), yoy));
                sb.append(String.format("%n    [XBRL: %s, %s, %s]%n",
                        revenueConcept, curr.label(), nullSafe(curr.form())));
            }
            sb.append("\n");
        }

        // === Annual margins (derived) ===
        List<CompanyFacts.Fact> annualNetIncome = pickAnnualSeries(facts, NET_INCOME_CONCEPTS, 5);
        List<CompanyFacts.Fact> annualOpIncome = pickAnnualSeries(facts, OP_INCOME_CONCEPTS, 5);
        List<CompanyFacts.Fact> annualGrossProfit = pickAnnualSeries(facts, GROSS_PROFIT_CONCEPTS, 5);
        if (!annualRevenue.isEmpty() && (!annualNetIncome.isEmpty() || !annualOpIncome.isEmpty() || !annualGrossProfit.isEmpty())) {
            sb.append("### Profitability & Margins (annual)\n\n");
            sb.append(tableHeader("Period", "Gross Margin", "Operating Margin", "Net Margin", "Net Income"));
            for (CompanyFacts.Fact rev : annualRevenue) {
                double revenueVal = rev.value();
                if (revenueVal == 0.0) continue;
                String gm = findMatching(annualGrossProfit, rev.endDate())
                        .map(f -> pct(f.value() / revenueVal)).orElse("—");
                String om = findMatching(annualOpIncome, rev.endDate())
                        .map(f -> pct(f.value() / revenueVal)).orElse("—");
                String nm = findMatching(annualNetIncome, rev.endDate())
                        .map(f -> pct(f.value() / revenueVal)).orElse("—");
                String ni = findMatching(annualNetIncome, rev.endDate())
                        .map(f -> money(f.value(), f.unit())).orElse("—");
                sb.append(String.format("| %s | %s | %s | %s | %s |%n",
                        rev.label(), gm, om, nm, ni));
            }
            if (!annualNetIncome.isEmpty()) {
                String niConcept = conceptWithData(facts, NET_INCOME_CONCEPTS, "FY");
                sb.append(String.format("%n    [XBRL: %s + %s, annual, form 10-K]%n",
                        conceptWithData(facts, REVENUE_CONCEPTS, "FY"), niConcept));
            }
            sb.append("\n");
        }

        // === Recent quarterly revenue (shows current-period trajectory) ===
        List<CompanyFacts.Fact> qRevenue = pickQuarterlySeries(facts, REVENUE_CONCEPTS, 6);
        if (!qRevenue.isEmpty()) {
            sb.append("### Revenue (recent quarters)\n\n");
            sb.append(tableHeader("Quarter", "Revenue", "QoQ", "YoY"));
            for (int i = 0; i < qRevenue.size(); i++) {
                CompanyFacts.Fact curr = qRevenue.get(i);
                String qoq = "—", yoy = "—";
                if (i + 1 < qRevenue.size()) {
                    double prev = qRevenue.get(i + 1).value();
                    if (prev != 0.0) qoq = pct((curr.value() - prev) / Math.abs(prev));
                }
                if (i + 4 < qRevenue.size()) {
                    double yearAgo = qRevenue.get(i + 4).value();
                    if (yearAgo != 0.0) yoy = pct((curr.value() - yearAgo) / Math.abs(yearAgo));
                }
                sb.append(String.format("| %s | %s | %s | %s |%n",
                        curr.label(), money(curr.value(), curr.unit()), qoq, yoy));
            }
            sb.append("\n");
        }

        // === EPS (diluted preferred, fall back to basic) ===
        List<CompanyFacts.Fact> eps = pickAnnualSeries(facts, EPS_DILUTED_CONCEPTS, 5);
        String epsConceptUsed = conceptWithData(facts, EPS_DILUTED_CONCEPTS, "FY");
        if (eps.isEmpty()) {
            eps = pickAnnualSeries(facts, EPS_BASIC_CONCEPTS, 5);
            epsConceptUsed = conceptWithData(facts, EPS_BASIC_CONCEPTS, "FY");
        }
        if (!eps.isEmpty() && epsConceptUsed != null) {
            sb.append("### Earnings per Share (annual)\n\n");
            sb.append(tableHeader("Period", "EPS (" + (epsConceptUsed.contains("Diluted") ? "Diluted" : "Basic") + ")"));
            for (CompanyFacts.Fact f : eps) {
                sb.append(String.format(Locale.US, "| %s | $%.2f |%n", f.label(), f.value()));
            }
            sb.append(String.format(Locale.US, "%n    [XBRL: %s, annual, form 10-K]%n%n", epsConceptUsed));
        }

        // === Balance sheet snapshot (most recent annual) ===
        appendLatestFact(sb, facts, "Total Assets", ASSETS_CONCEPTS);
        appendLatestFact(sb, facts, "Total Liabilities", LIABILITIES_CONCEPTS);
        appendLatestFact(sb, facts, "Stockholders' Equity", EQUITY_CONCEPTS);
        appendLatestFact(sb, facts, "Cash & Equivalents", CASH_CONCEPTS);

        // === Operating cash flow (most recent annual) ===
        appendLatestFact(sb, facts, "Operating Cash Flow (annual)", CASH_FLOW_OPS_CONCEPTS);

        sb.append("\n_Note: agents MUST cite these figures with the bracketed [XBRL: …] provenance ")
                .append("when producing reports. If a required figure is missing from this section, it means SEC's ")
                .append("companyfacts API did not report it for this entity — surface this explicitly rather than ")
                .append("fabricating a number._\n\n");

        return sb.toString();
    }

    private void appendLatestFact(StringBuilder sb, CompanyFacts facts, String label, String[] concepts) {
        for (String c : concepts) {
            List<CompanyFacts.Fact> annual = facts.getRecentAnnual(c, 1);
            if (!annual.isEmpty()) {
                CompanyFacts.Fact f = annual.get(0);
                sb.append(String.format("- **%s (%s):** %s  \n    [XBRL: %s, %s, %s]%n",
                        label, f.label(), money(f.value(), f.unit()), c, f.label(), nullSafe(f.form())));
                return;
            }
        }
    }

    private java.util.Optional<CompanyFacts.Fact> findMatching(
            List<CompanyFacts.Fact> series, String endDate) {
        return series.stream().filter(f -> endDate.equals(f.endDate())).findFirst();
    }

    private List<CompanyFacts.Fact> pickAnnualSeries(CompanyFacts facts, String[] concepts, int limit) {
        for (String c : concepts) {
            List<CompanyFacts.Fact> recent = facts.getRecentAnnual(c, limit);
            if (!recent.isEmpty()) return recent;
        }
        return List.of();
    }

    private List<CompanyFacts.Fact> pickQuarterlySeries(CompanyFacts facts, String[] concepts, int limit) {
        for (String c : concepts) {
            List<CompanyFacts.Fact> recent = facts.getRecentQuarterly(c, limit);
            if (!recent.isEmpty()) return recent;
        }
        return List.of();
    }

    private String conceptWithData(CompanyFacts facts, String[] concepts, String period) {
        for (String c : concepts) {
            if ("FY".equals(period)) {
                if (!facts.getRecentAnnual(c, 1).isEmpty()) return c;
            } else {
                if (facts.hasConcept(c)) return c;
            }
        }
        return null;
    }

    private String tableHeader(String... cols) {
        StringBuilder s = new StringBuilder("| ");
        for (String c : cols) s.append(c).append(" | ");
        s.append("\n|");
        for (String ignored : cols) s.append("---|");
        s.append("\n");
        return s.toString();
    }

    private String money(double val, String unit) {
        if (unit != null && unit.startsWith("USD")) {
            double abs = Math.abs(val);
            if (abs >= 1_000_000_000) {
                return String.format(Locale.US, "$%.2fB", val / 1_000_000_000.0);
            } else if (abs >= 1_000_000) {
                return String.format(Locale.US, "$%.2fM", val / 1_000_000.0);
            } else if (abs >= 1_000) {
                return String.format(Locale.US, "$%.2fK", val / 1_000.0);
            }
            return String.format(Locale.US, "$%.2f", val);
        }
        return String.format(Locale.US, "%.2f %s", val, unit != null ? unit : "");
    }

    private String pct(double ratio) {
        return String.format(Locale.US, "%+.1f%%", ratio * 100);
    }

    private String nullSafe(String s) {
        return s != null ? s : "—";
    }
}
