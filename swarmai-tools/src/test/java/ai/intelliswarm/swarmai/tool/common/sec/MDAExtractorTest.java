package ai.intelliswarm.swarmai.tool.common.sec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MDAExtractor}. Uses synthetic filing objects so tests are
 * deterministic and don't require network access.
 */
@DisplayName("MDAExtractor Tests")
class MDAExtractorTest {

    private final MDAExtractor extractor = new MDAExtractor();

    @Test
    @DisplayName("Empty filing list → no bullets, empty render")
    void emptyInput() {
        assertThat(extractor.extract(List.of(), 20)).isEmpty();
        assertThat(extractor.render(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Non-MD&A-friendly form types are skipped")
    void skipsNonFriendlyForms() {
        Filing form4 = makeFiling("4", "2026-04-03",
                "Management's discussion and analysis reveals risk factors adversely affecting revenue.");
        Filing schedule13G = makeFiling("SCHEDULE 13G", "2026-03-26",
                "Management's discussion and analysis reveals risk factors adversely affecting revenue.");

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(form4, schedule13G), 20);

        assertThat(bullets)
                .as("Form 4 and Schedule 13G are not MD&A-friendly forms")
                .isEmpty();
    }

    @Test
    @DisplayName("10-K with clear risk + liquidity sentences → themed bullets")
    void tenKRiskAndLiquidity() {
        String text = "Item 7. Management's Discussion and Analysis of Financial Condition and Results of Operations. " +
                "Our revenue declined materially during fiscal 2024 as foreign exchange volatility adversely impacted " +
                "our international operations. Competition in the enterprise software market continues to present risk factors. " +
                "Liquidity remained strong with working capital of 2.1 billion dollars and an undrawn credit facility of 500 million dollars. " +
                "We expect to refinance our 2026 debt maturity within the next twelve months. " +
                "Looking forward we anticipate continued growth in cloud services and we project operating margin expansion in 2025.";
        Filing tenK = makeFiling("10-K", "2024-11-01", text);

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(tenK), 20);

        assertThat(bullets).isNotEmpty();
        assertThat(bullets.stream().map(MDAExtractor.Bullet::theme).distinct().toList())
                .as("Should cover multiple themes from the text")
                .containsAnyOf("Risks", "Liquidity & Capital", "Guidance & Outlook");

        // Every bullet must carry the 10-K citation
        for (MDAExtractor.Bullet b : bullets) {
            assertThat(b.citation()).contains("10-K").contains("2024-11-01");
        }
    }

    @Test
    @DisplayName("Foreign issuer 20-F is MD&A-friendly")
    void twentyFIsExtracted() {
        String text = "Operating and Financial Review. Our revenue grew substantially as vessel day rates increased. " +
                "We expect fleet expansion to drive growth in 2025 and 2026.";
        Filing twentyF = makeFiling("20-F", "2025-04-15", text);

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(twentyF), 20);

        assertThat(bullets).isNotEmpty();
        assertThat(bullets.stream().anyMatch(b -> b.citation().contains("20-F"))).isTrue();
    }

    @Test
    @DisplayName("Render groups bullets by theme in fixed order")
    void renderGrouping() {
        String text = "Management's discussion and analysis section below provides context. " +
                "Revenue declined materially and adversely impacted annual results for the fiscal year. " +
                "Liquidity remained strong supported by our cash position and undrawn revolving credit facility. " +
                "We expect margin expansion driven by cloud services growth and product mix shift. " +
                "We launched several new products during the year and market share grew across geographies.";
        Filing tenK = makeFiling("10-K", "2024-11-01", text);

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(tenK), 20);
        String rendered = extractor.render(bullets);

        assertThat(rendered).contains("## MD&A Highlights");
        // At least two of the four theme headings should appear if classification worked
        int themesPresent = 0;
        for (String theme : new String[]{"### Risks", "### Liquidity & Capital", "### Guidance & Outlook", "### Opportunities & Growth"}) {
            if (rendered.contains(theme)) themesPresent++;
        }
        assertThat(themesPresent).as("At least 2 themes should be represented").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("maxBulletsTotal caps output across filings")
    void maxBulletsCap() {
        // Create 3 filings each with many themed sentences
        String text = "Management's discussion and analysis. " +
                "Revenue declined adversely. " +
                "Liquidity remained. " +
                "We expect growth. " +
                "We launched products.";
        List<Filing> filings = List.of(
                makeFiling("10-K", "2024-11-01", text),
                makeFiling("10-Q", "2024-08-01", text),
                makeFiling("10-Q", "2024-05-01", text));

        List<MDAExtractor.Bullet> bullets = extractor.extract(filings, 5);

        assertThat(bullets).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Too-short MD&A blocks produce no bullets")
    void tooShortBlock() {
        String text = "MD&A: risk.";  // under minimum block length
        Filing tenK = makeFiling("10-K", "2024-11-01", text);

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(tenK), 20);
        assertThat(bullets).isEmpty();
    }

    @Test
    @DisplayName("Filings without fetched content are skipped")
    void unfetchedContentSkipped() {
        Filing f = new Filing();
        f.setFormType("10-K");
        f.setFilingDate("2024-11-01");
        f.setContentFetched(false);  // no content yet

        List<MDAExtractor.Bullet> bullets = extractor.extract(List.of(f), 20);
        assertThat(bullets).isEmpty();
    }

    // helper
    private Filing makeFiling(String formType, String filingDate, String extractedText) {
        Filing f = new Filing();
        f.setFormType(formType);
        f.setFilingDate(filingDate);
        f.setAccessionNumber("0000000000-00-000000");
        f.setPrimaryDocument("primary.htm");
        f.setUrl("https://example.com");
        f.setExtractedText(extractedText);
        f.setContentFetched(true);
        return f;
    }
}
