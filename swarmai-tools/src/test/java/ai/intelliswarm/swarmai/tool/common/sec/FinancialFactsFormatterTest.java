package ai.intelliswarm.swarmai.tool.common.sec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FinancialFactsFormatter}. The formatter is tested in isolation
 * by constructing synthetic {@link CompanyFacts} objects — the integration test exercises
 * the real SEC companyfacts endpoint.
 */
@DisplayName("FinancialFactsFormatter Tests")
class FinancialFactsFormatterTest {

    private final FinancialFactsFormatter formatter = new FinancialFactsFormatter();

    @Test
    @DisplayName("Empty facts → empty string")
    void emptyFactsReturnsEmpty() {
        CompanyFacts empty = new CompanyFacts();
        assertThat(formatter.format(empty)).isEmpty();
        assertThat(formatter.format(null)).isEmpty();
    }

    @Test
    @DisplayName("Annual revenue with YoY growth rendered correctly")
    void annualRevenueWithYoYGrowth() {
        CompanyFacts facts = new CompanyFacts();
        facts.setEntityName("ACME CORP");
        facts.setCik("1234567");
        // Most recent first (newest → oldest)
        facts.addFact("Revenues", annualFact(394_328_000_000.0, "USD", "2024-09-28", "2024", "10-K"));
        facts.addFact("Revenues", annualFact(383_285_000_000.0, "USD", "2023-09-30", "2023", "10-K"));
        facts.addFact("Revenues", annualFact(394_328_000_000.0, "USD", "2022-09-24", "2022", "10-K"));

        String out = formatter.format(facts);

        assertThat(out)
                .contains("Key Financials (XBRL)")
                .contains("Revenue (annual)")
                .contains("$394.33B")   // 2024 revenue
                .contains("$383.29B")   // 2023 revenue
                .contains("+2.9%");      // YoY growth (394.33B from 383.29B)
    }

    @Test
    @DisplayName("Margins computed from revenue + income concepts")
    void marginsComputedFromMultipleConcepts() {
        CompanyFacts facts = new CompanyFacts();
        facts.addFact("Revenues", annualFact(100_000_000.0, "USD", "2024-12-31", "2024", "10-K"));
        facts.addFact("NetIncomeLoss", annualFact(20_000_000.0, "USD", "2024-12-31", "2024", "10-K"));
        facts.addFact("OperatingIncomeLoss", annualFact(30_000_000.0, "USD", "2024-12-31", "2024", "10-K"));
        facts.addFact("GrossProfit", annualFact(45_000_000.0, "USD", "2024-12-31", "2024", "10-K"));

        String out = formatter.format(facts);

        assertThat(out)
                .contains("Profitability & Margins")
                .contains("+45.0%")   // gross margin 45M/100M
                .contains("+30.0%")   // operating margin 30M/100M
                .contains("+20.0%");  // net margin 20M/100M
    }

    @Test
    @DisplayName("Quarterly revenue series shows QoQ and YoY")
    void quarterlyRevenueQoQAndYoY() {
        CompanyFacts facts = new CompanyFacts();
        // 6 quarters of revenue, newest → oldest. YoY compares Q[i] to Q[i+4].
        facts.addFact("Revenues", quarterlyFact(120.0, "USD", "2025-06-30", "2025", "Q2", "10-Q"));
        facts.addFact("Revenues", quarterlyFact(115.0, "USD", "2025-03-31", "2025", "Q1", "10-Q"));
        facts.addFact("Revenues", quarterlyFact(118.0, "USD", "2024-12-31", "2024", "Q4", "10-K"));
        facts.addFact("Revenues", quarterlyFact(112.0, "USD", "2024-09-30", "2024", "Q3", "10-Q"));
        facts.addFact("Revenues", quarterlyFact(108.0, "USD", "2024-06-30", "2024", "Q2", "10-Q"));
        facts.addFact("Revenues", quarterlyFact(105.0, "USD", "2024-03-31", "2024", "Q1", "10-Q"));

        String out = formatter.format(facts);

        assertThat(out)
                .contains("Revenue (recent quarters)")
                .contains("Q2 2025")
                .contains("+11.1%");  // YoY for 2025 Q2 vs 2024 Q2 = (120-108)/108 ≈ 11.11%
    }

    @Test
    @DisplayName("Balance sheet snapshot uses most recent annual fact")
    void balanceSheetPickLatestAnnual() {
        CompanyFacts facts = new CompanyFacts();
        // Provide two annual assets observations — formatter must pick the most recent.
        facts.addFact("Assets", annualFact(500_000_000.0, "USD", "2024-12-31", "2024", "10-K"));
        facts.addFact("Assets", annualFact(400_000_000.0, "USD", "2023-12-31", "2023", "10-K"));

        String out = formatter.format(facts);

        assertThat(out).contains("Total Assets").contains("$500.00M");
        // The older observation should not appear as the "Total Assets" line, only
        // in any other section that iterates history.
        int assetsLine = out.indexOf("**Total Assets");
        int fourHundredM = out.indexOf("$400.00M");
        if (fourHundredM >= 0) {
            // If $400M appears at all, it must not be next to the Total Assets bullet
            assertThat(Math.abs(fourHundredM - assetsLine)).isGreaterThan(50);
        }
    }

    @Test
    @DisplayName("IFRS-full concepts (foreign issuer) are also rendered")
    void ifrsFullConceptsRender() {
        CompanyFacts facts = new CompanyFacts();
        // IMPP-style IFRS facts (no us-gaap). Formatter should still pick these up via
        // the ifrs-full:* fallback names.
        facts.addFact("ifrs-full:Revenue", annualFact(250_000_000.0, "USD", "2024-12-31", "2024", "20-F"));
        facts.addFact("ifrs-full:ProfitLoss", annualFact(40_000_000.0, "USD", "2024-12-31", "2024", "20-F"));

        String out = formatter.format(facts);

        assertThat(out)
                .as("IFRS revenue must render")
                .contains("$250.00M");
    }

    @Test
    @DisplayName("EPS diluted preferred over basic when both present")
    void epsDilutedPreferred() {
        CompanyFacts facts = new CompanyFacts();
        facts.addFact("EarningsPerShareBasic", annualFact(6.20, "USD/shares", "2024-09-28", "2024", "10-K"));
        facts.addFact("EarningsPerShareDiluted", annualFact(6.15, "USD/shares", "2024-09-28", "2024", "10-K"));

        String out = formatter.format(facts);

        // Must quote diluted, not basic
        assertThat(out).contains("EPS (Diluted)").contains("$6.15");
    }

    @Test
    @DisplayName("EPS falls back to basic when diluted absent")
    void epsFallsBackToBasic() {
        CompanyFacts facts = new CompanyFacts();
        facts.addFact("EarningsPerShareBasic", annualFact(3.10, "USD/shares", "2024-09-28", "2024", "10-K"));

        String out = formatter.format(facts);
        assertThat(out).contains("EPS (Basic)").contains("$3.10");
    }

    @Test
    @DisplayName("Every rendered figure carries an XBRL provenance tag")
    void everyFigureHasCitation() {
        CompanyFacts facts = new CompanyFacts();
        facts.addFact("Revenues", annualFact(100.0, "USD", "2024-12-31", "2024", "10-K"));
        facts.addFact("NetIncomeLoss", annualFact(10.0, "USD", "2024-12-31", "2024", "10-K"));

        String out = formatter.format(facts);

        // Look for provenance tag markers
        assertThat(out).contains("[XBRL:");
    }

    // ---------- helpers ----------

    private CompanyFacts.Fact annualFact(double val, String unit, String endDate,
                                          String fy, String form) {
        return new CompanyFacts.Fact(val, unit, "FY-" + fy, endDate, form, fy, "FY");
    }

    private CompanyFacts.Fact quarterlyFact(double val, String unit, String endDate,
                                             String fy, String qp, String form) {
        return new CompanyFacts.Fact(val, unit, qp + "-" + fy, endDate, form, fy, qp);
    }
}
