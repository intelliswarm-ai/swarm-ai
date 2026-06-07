package ai.intelliswarm.swarmai.tool.office;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PptxAuthorAndDeckQcTest {

    @Test
    void authorBuildsDeckWithTitleBulletsAndTable(@TempDir Path tempDir) throws Exception {
        PptxAuthorTool author = new PptxAuthorTool(tempDir);
        Map<String, Object> spec = Map.of(
                "path", "pitch.pptx",
                "slides", List.of(
                        Map.of("layout", "TITLE",
                                "title", "ACME Co — Investment Pitch",
                                "subtitle", "Q4 2026"),
                        Map.of("layout", "TITLE_AND_CONTENT",
                                "title", "Investment Highlights",
                                "bullets", List.of(
                                        "#1 market position",
                                        "30% YoY growth",
                                        "Margin expansion")),
                        Map.of("layout", "TITLE_AND_CONTENT",
                                "title", "Comparable Companies",
                                "table", Map.of(
                                        "headers", List.of("Company", "EV/Revenue", "EV/EBITDA"),
                                        "rows", List.of(
                                                List.of("ACME", "8.2x", "22.1x"),
                                                List.of("Peer A", "7.5x", "19.0x"))))));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) author.execute(Map.of("spec", spec));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("slideCount")).isEqualTo(3);
        assertThat(result.get("bulletCount")).isEqualTo(3);
        assertThat(result.get("tableCount")).isEqualTo(1);

        Path outPath = tempDir.resolve("pitch.pptx");
        assertThat(outPath).exists();

        try (var in = new FileInputStream(outPath.toFile());
             var ppt = new XMLSlideShow(in)) {
            assertThat(ppt.getSlides()).hasSize(3);

            // Title slide carries the title text somewhere
            XSLFSlide titleSlide = ppt.getSlides().get(0);
            String titleText = textOf(titleSlide);
            assertThat(titleText).contains("ACME Co");

            // Bullet slide has all three bullets
            String bulletText = textOf(ppt.getSlides().get(1));
            assertThat(bulletText).contains("market position")
                    .contains("YoY growth")
                    .contains("Margin expansion");

            // Table slide has the headers
            String tableText = textOf(ppt.getSlides().get(2));
            assertThat(tableText).contains("EV/Revenue").contains("ACME");
        }
    }

    @Test
    void deckQcPassesCleanDeck(@TempDir Path tempDir) throws Exception {
        PptxAuthorTool author = new PptxAuthorTool(tempDir);
        author.execute(Map.of("spec", Map.of(
                "path", "clean.pptx",
                "slides", List.of(
                        Map.of("title", "Quarterly Update",
                                "bullets", List.of("Revenue up 30%", "Margins expanded 200bps"))))));

        DeckQcTool qc = new DeckQcTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) qc.execute(Map.of("path", "clean.pptx"));
        assertThat(report.get("qc")).isEqualTo("PASS");
        assertThat(report.get("placeholderCount")).isEqualTo(0);
    }

    @Test
    void deckQcFlagsPlaceholders(@TempDir Path tempDir) throws Exception {
        PptxAuthorTool author = new PptxAuthorTool(tempDir);
        author.execute(Map.of("spec", Map.of(
                "path", "draft.pptx",
                "slides", List.of(
                        Map.of("title", "Revenue Outlook",
                                "bullets", List.of(
                                        "FY26E revenue: $XXX",
                                        "Gross margin: X.X%",
                                        "Operating leverage TBD"))))));

        DeckQcTool qc = new DeckQcTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) qc.execute(Map.of("path", "draft.pptx"));
        assertThat(report.get("qc")).isEqualTo("FAIL");
        assertThat((Integer) report.get("placeholderCount")).isGreaterThanOrEqualTo(3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.get("findings");
        assertThat(findings).anySatisfy(f ->
                assertThat(f.get("code")).isEqualTo("PLACEHOLDER"));
    }

    @Test
    void deckQcRejectsMissingFile(@TempDir Path tempDir) {
        DeckQcTool qc = new DeckQcTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) qc.execute(Map.of("path", "nope.pptx"));
        assertThat(report.get("ok")).isEqualTo(false);
    }

    private static String textOf(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        slide.getShapes().forEach(shape -> {
            if (shape instanceof XSLFTextShape ts) {
                sb.append(ts.getText()).append("\n");
            } else if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTable table) {
                table.getRows().forEach(row ->
                        row.getCells().forEach(cell -> sb.append(cell.getText()).append("\n")));
            }
        });
        return sb.toString();
    }
}
