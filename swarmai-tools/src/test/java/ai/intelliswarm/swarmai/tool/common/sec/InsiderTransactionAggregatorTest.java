package ai.intelliswarm.swarmai.tool.common.sec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InsiderTransactionAggregator}. Uses synthetic Form 4 XBRL
 * fragments that mirror the structure SEC actually returns (inline XBRL elements
 * like {@code <transactionShares>}, {@code <transactionPricePerShare>}, etc.).
 */
@DisplayName("InsiderTransactionAggregator Tests")
class InsiderTransactionAggregatorTest {

    private final InsiderTransactionAggregator aggregator = new InsiderTransactionAggregator();

    @Test
    @DisplayName("Empty filings list → empty output")
    void emptyList() {
        assertThat(aggregator.extract(List.of())).isEmpty();
        assertThat(aggregator.render(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Non-Form-4 filings are skipped")
    void nonForm4Skipped() {
        Filing tenK = makeFiling("10-K", "2024-11-01", xbrlBody(100, 50.0, "P", "A", "2024-10-31"));
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(tenK));
        assertThat(txs).isEmpty();
    }

    @Test
    @DisplayName("Single Form 4 with one acquisition transaction parsed correctly")
    void singleAcquisition() {
        Filing form4 = makeFiling("4", "2026-04-03", xbrlBody(100, 50.0, "P", "A", "2026-04-02"));
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(form4));

        assertThat(txs).hasSize(1);
        InsiderTransactionAggregator.Transaction t = txs.get(0);
        assertThat(t.shares()).isEqualTo(100.0);
        assertThat(t.pricePerShare()).isEqualTo(50.0);
        assertThat(t.code()).isEqualTo("P");
        assertThat(t.direction()).isEqualTo("A");
        assertThat(t.grossDollarFlow()).isEqualTo(5000.0);  // 100 * 50, acquired
    }

    @Test
    @DisplayName("Disposition produces negative gross flow")
    void disposition() {
        Filing form4 = makeFiling("4", "2026-04-03", xbrlBody(200, 75.0, "S", "D", "2026-04-02"));
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(form4));

        assertThat(txs).hasSize(1);
        assertThat(txs.get(0).grossDollarFlow()).isEqualTo(-15000.0);  // disposed = negative
    }

    @Test
    @DisplayName("Rendering shows net flow + per-tx table")
    void renderNetFlow() {
        Filing f1 = makeFiling("4", "2026-04-03", xbrlBody(100, 50.0, "P", "A", "2026-04-02"));
        Filing f2 = makeFiling("4", "2026-04-01", xbrlBody(200, 45.0, "S", "D", "2026-03-31"));
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(f1, f2));
        String rendered = aggregator.render(txs);

        assertThat(rendered)
                .contains("## Insider Transaction Flow")
                .contains("Net insider")
                .contains("Acquired")
                .contains("Disposed")
                .contains("Form 4");  // citation format
        // Net = 5000 - 9000 = -4000 → net sellers
        assertThat(rendered).contains("net sellers");
    }

    @Test
    @DisplayName("Multiple transactions in one filing are parsed positionally")
    void multipleTransactionsOneFiling() {
        // Two transactions in the same Form 4 XBRL body
        String body = xbrlBody(100, 50.0, "P", "A", "2026-04-02")
                + "\n\n"
                + xbrlBody(200, 55.0, "S", "D", "2026-04-02");
        Filing form4 = makeFiling("4", "2026-04-03", body);

        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(form4));

        assertThat(txs).hasSize(2);
    }

    @Test
    @DisplayName("Malformed transaction values are skipped (not fatal)")
    void malformedSkipped() {
        String badBody = "<transactionShares>NotANumber</transactionShares>" +
                "<transactionPricePerShare>50.0</transactionPricePerShare>" +
                "<transactionCode>P</transactionCode>" +
                "<transactionAcquiredDisposedCode>A</transactionAcquiredDisposedCode>";
        Filing form4 = makeFiling("4", "2026-04-03", badBody);
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(form4));

        assertThat(txs).as("Malformed data should be tolerated, not crash the aggregator").isEmpty();
    }

    @Test
    @DisplayName("Transactions sorted by date descending")
    void sortedDescending() {
        Filing older = makeFiling("4", "2026-03-01", xbrlBody(10, 10.0, "P", "A", "2026-02-28"));
        Filing newer = makeFiling("4", "2026-04-05", xbrlBody(20, 20.0, "P", "A", "2026-04-04"));
        List<InsiderTransactionAggregator.Transaction> txs = aggregator.extract(List.of(older, newer));

        assertThat(txs).hasSize(2);
        assertThat(txs.get(0).transactionDate()).isEqualTo("2026-04-04");  // newest first
        assertThat(txs.get(1).transactionDate()).isEqualTo("2026-02-28");
    }

    // helpers

    private Filing makeFiling(String formType, String filingDate, String content) {
        Filing f = new Filing();
        f.setFormType(formType);
        f.setFilingDate(filingDate);
        f.setAccessionNumber("0000000000-00-000000");
        f.setPrimaryDocument("wk-form4.xml");
        f.setUrl("https://www.sec.gov/Archives/edgar/data/000/000000000000000000/wk-form4.xml");
        f.setContent(content);
        f.setContentFetched(true);
        return f;
    }

    /**
     * Builds a Form-4-style XBRL fragment with the four canonical fields. Mirrors
     * what SEC actually returns in primaryDocument wk-form4.xml files.
     *
     * <p>Uses {@link java.util.Locale#US} for number formatting — SEC XBRL always
     * emits {@code .} as the decimal separator, so tests must too. Without an
     * explicit locale, {@code %.2f} on a German-locale JVM produces {@code "50,00"}
     * which conflicts with the aggregator's comma-as-thousands-separator handling.
     */
    private String xbrlBody(int shares, double price, String code, String direction, String txDate) {
        return String.format(java.util.Locale.US,
                "<transactionShares>%d</transactionShares>" +
                "<transactionPricePerShare>%.2f</transactionPricePerShare>" +
                "<transactionCode>%s</transactionCode>" +
                "<transactionAcquiredDisposedCode>%s</transactionAcquiredDisposedCode>" +
                "<transactionDate>%s</transactionDate>",
                shares, price, code, direction, txDate);
    }
}
