package ai.intelliswarm.swarmai.tool.vision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test for OcrTool — runs real Tesseract against a synthesized image.
 *
 * Requires Tesseract installed on the host (+ the 'eng' language pack):
 *   Debian / Ubuntu:  apt-get install tesseract-ocr
 *   macOS (Homebrew): brew install tesseract
 *   Windows:          https://github.com/UB-Mannheim/tesseract/wiki
 *
 * Opt in with OCR_TEST_ENABLED=true so CI without Tesseract stays green. The test also
 * does a preflight check (assumeTrue on smokeTest) so a missing native lib skips
 * rather than failing.
 */
@Tag("integration")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "OCR_TEST_ENABLED", matches = "true")
@DisplayName("OcrTool Integration Tests")
class OcrToolIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(OcrToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @TempDir Path tmp;
    private OcrTool tool;

    @BeforeEach
    void setUp() throws IOException {
        tool = new OcrTool();
        assumeTrue(tool.smokeTest() == null,
            "Tesseract native library not loadable on this host — install tesseract-ocr first.");
        Path out = Paths.get(OUTPUT_DIR);
        if (!Files.exists(out)) Files.createDirectories(out);
    }

    @Test
    @DisplayName("OCR a synthesized image of known text returns that text")
    void extractKnownText() throws Exception {
        String expected = "SwarmAI invoice number 42";
        Path image = renderText(tmp.resolve("invoice.png"), expected);

        Object out = tool.execute(Map.of(
            "path", image.toString(),
            "language", "eng"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        // Tesseract isn't byte-perfect, but all three uncommon tokens should round-trip.
        assertTrue(s.contains("SwarmAI"),  "Expected 'SwarmAI'. Got:\n" + s);
        assertTrue(s.contains("invoice") || s.contains("Invoice"), "Expected 'invoice'. Got:\n" + s);
        assertTrue(s.contains("42"),       "Expected '42'. Got:\n" + s);
        write("known_text", s);
    }

    @Test
    @DisplayName("base64 input round-trips through OCR")
    void extractFromBase64() throws Exception {
        Path image = renderText(tmp.resolve("b64.png"), "Hello world");
        byte[] bytes = Files.readAllBytes(image);
        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);

        Object out = tool.execute(Map.of("base64", b64, "language", "eng"));

        String s = out.toString();
        assertFalse(s.startsWith("Error"), "Unexpected error: " + s);
        assertTrue(s.toLowerCase().contains("hello"), s);
        assertTrue(s.toLowerCase().contains("world"), s);
    }

    @Test
    @DisplayName("blank image yields the 'no text detected' hint, not a crash")
    void blankImage() throws Exception {
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 200);
        g.dispose();
        Path p = tmp.resolve("blank.png");
        ImageIO.write(img, "png", p.toFile());

        Object out = tool.execute(Map.of("path", p.toString()));
        assertTrue(out.toString().contains("no text detected") || out.toString().contains("OCR"),
            "Expected clean output. Got: " + out);
    }

    // ---------- helpers ----------

    /** Render the given text onto a white 600×200 PNG with crisp anti-aliased sans-serif. */
    private static Path renderText(Path where, String text) throws IOException {
        BufferedImage img = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 200);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
        g.drawString(text, 20, 110);
        g.dispose();
        ImageIO.write(img, "png", where.toFile());
        return where;
    }

    private void write(String label, String content) {
        String filename = OUTPUT_DIR + "/ocr_" + label + "_" + LocalDateTime.now().format(TS) + ".md";
        try {
            Files.writeString(Paths.get(filename), content);
            logger.info("Wrote sample to {}", filename);
        } catch (IOException e) {
            logger.warn("Failed to write sample {}: {}", filename, e.getMessage());
        }
    }
}
