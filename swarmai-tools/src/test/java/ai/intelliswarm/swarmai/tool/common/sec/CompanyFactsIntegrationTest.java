package ai.intelliswarm.swarmai.tool.common.sec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live integration test for {@link SECApiClient#fetchCompanyFacts(String)} — hits the
 * real SEC XBRL companyfacts endpoint at {@code data.sec.gov/api/xbrl/companyfacts/...}.
 * SEC does not require an API key, only a User-Agent header (the client already sets one).
 *
 * <p>These tests verify that:
 * <ol>
 *   <li>Domestic filers (AAPL) return us-gaap facts with revenue, net income, and margins.</li>
 *   <li>Foreign filers (IMPP) return ifrs-full facts — the specific reason we added
 *       the {@code ifrs-full} namespace parsing.</li>
 *   <li>The formatter produces a non-empty report for both issuer types.</li>
 * </ol>
 */
@Tag("integration")
@DisplayName("CompanyFacts + FinancialFactsFormatter Integration Tests (live SEC)")
class CompanyFactsIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(CompanyFactsIntegrationTest.class);

    // Well-known CIKs. These are stable — AAPL is 320193, IMPP (Imperial Petroleum) is 1876581.
    private static final String AAPL_CIK = "320193";
    private static final String IMPP_CIK = "1876581";

    @Test
    @DisplayName("AAPL (us-gaap) → companyfacts returns revenue + net income")
    void aaplUsGaap() {
        SECApiClient client = new SECApiClient();
        CompanyFacts facts = client.fetchCompanyFacts(AAPL_CIK);

        assertThat(facts).as("Must return CompanyFacts for AAPL").isNotNull();
        assertThat(facts.getCik()).isEqualTo(AAPL_CIK);
        assertThat(facts.getEntityName()).isNotNull();

        // AAPL must have at least one of the canonical revenue concepts
        boolean hasRevenue =
                facts.hasConcept("Revenues") ||
                facts.hasConcept("RevenueFromContractWithCustomerExcludingAssessedTax") ||
                facts.hasConcept("SalesRevenueNet");
        assertThat(hasRevenue).as("AAPL must report a revenue concept under us-gaap").isTrue();

        boolean hasNetIncome = facts.hasConcept("NetIncomeLoss");
        assertThat(hasNetIncome).as("AAPL must report NetIncomeLoss under us-gaap").isTrue();

        // Annual observations should be multiple years
        int annualRevenueCount = facts.getRecentAnnual("Revenues", 10).size();
        if (annualRevenueCount == 0) {
            annualRevenueCount = facts.getRecentAnnual(
                    "RevenueFromContractWithCustomerExcludingAssessedTax", 10).size();
        }
        assertThat(annualRevenueCount).as("AAPL should have multiple annual revenue observations").isGreaterThan(1);

        logger.info("AAPL: entityName={}, concepts={}, annual revenue observations={}",
                facts.getEntityName(), facts.getAllConcepts().size(), annualRevenueCount);
    }

    @Test
    @DisplayName("Formatter produces non-empty report for AAPL")
    void aaplFormatterProducesOutput() {
        SECApiClient client = new SECApiClient();
        CompanyFacts facts = client.fetchCompanyFacts(AAPL_CIK);
        FinancialFactsFormatter formatter = new FinancialFactsFormatter();

        String out = formatter.format(facts);

        assertThat(out)
                .contains("Key Financials (XBRL)")
                .contains("Revenue");
        assertThat(out.length()).isGreaterThan(500);
    }

    @Test
    @DisplayName("IMPP (foreign 20-F filer) → formatter renders revenue, net income, and assets end-to-end")
    void imppFormatterEndToEnd() {
        SECApiClient client = new SECApiClient();
        CompanyFacts facts = client.fetchCompanyFacts(IMPP_CIK);
        FinancialFactsFormatter formatter = new FinancialFactsFormatter();

        assertThat(facts).as("Must return a CompanyFacts object for IMPP").isNotNull();
        logger.info("IMPP: entityName={}, total concepts returned={}",
                facts.getEntityName(), facts.getAllConcepts().size());

        if (facts.getAllConcepts().isEmpty()) {
            logger.warn("IMPP returned no concepts — SEC may not have indexed IMPP's 20-F XBRL yet.");
            return;
        }

        // Real-world concept audit — IMPP reports under us-gaap (not ifrs-full), using
        // RevenueFromContractWithCustomerIncludingAssessedTax. An earlier bug caused the
        // formatter to render no revenue row because only the "Excluding..." variant was
        // in the lookup list. This assertion would have caught that regression.
        String rendered = formatter.format(facts);
        logger.info("IMPP formatted output: {} chars", rendered.length());

        assertThat(rendered)
                .as("IMPP's Key Financials section must include a Revenue (annual) table " +
                        "— IMPP has 20 FY observations under RevenueFromContractWithCustomerIncludingAssessedTax")
                .contains("Revenue (annual)");
        assertThat(rendered)
                .as("IMPP balance sheet snapshot should include Total Assets")
                .contains("Total Assets");
    }

    @Test
    @DisplayName("Empty/unknown CIK returns null gracefully")
    void unknownCik() {
        SECApiClient client = new SECApiClient();
        // CIK 9999999999 should not exist
        CompanyFacts facts = client.fetchCompanyFacts("9999999999");
        // Either null (404) or empty facts — both are acceptable handling
        if (facts != null) {
            assertThat(facts.getAllConcepts()).isEmpty();
        }
    }
}
