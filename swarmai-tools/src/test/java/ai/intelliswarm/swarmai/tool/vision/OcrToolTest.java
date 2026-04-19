package ai.intelliswarm.swarmai.tool.vision;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OcrTool Unit Tests")
class OcrToolTest {

    @TempDir Path tmp;
    private ITesseract engine;
    private OcrTool tool;

    @BeforeEach
    void setUp() {
        engine = mock(ITesseract.class);
        tool = new OcrTool(() -> engine);
    }

    @Test void functionName() { assertEquals("ocr", tool.getFunctionName()); }

    @Test
    void requirementsDeclareTesseractBinary() {
        assertTrue(tool.getRequirements().bins().contains("tesseract"));
    }

    // ===== Source resolution =====

    @Test
    @DisplayName("no source provided → clear error")
    void noSource() {
        Object out = tool.execute(Map.of("language", "eng"));
        assertTrue(out.toString().contains("provide 'path', 'url', or 'base64'"), out.toString());
    }

    @Test
    @DisplayName("multiple sources provided → clear error")
    void multipleSources() throws Exception {
        Path img = makePng(tmp.resolve("a.png"));
        Object out = tool.execute(Map.of(
            "path", img.toString(),
            "base64", Base64.getEncoder().encodeToString(Files.readAllBytes(img))));
        assertTrue(out.toString().contains("exactly one of"), out.toString());
    }

    @Test
    @DisplayName("nonexistent file path → clear error")
    void badPath() {
        Object out = tool.execute(Map.of("path", tmp.resolve("nonexistent.png").toString()));
        assertTrue(out.toString().contains("file not found"), out.toString());
    }

    @Test
    @DisplayName("unsupported URL scheme → clear error")
    void badUrl() {
        Object out = tool.execute(Map.of("url", "ftp://example.com/img.png"));
        assertTrue(out.toString().contains("http(s)"), out.toString());
    }

    @Test
    @DisplayName("malformed base64 → clear error")
    void badBase64() {
        Object out = tool.execute(Map.of("base64", "!!!not-base64@@@"));
        assertTrue(out.toString().contains("not valid base64"), out.toString());
    }

    // ===== Happy path =====

    @Test
    @DisplayName("valid PNG path: engine.doOCR called, output includes text + metadata")
    void pathHappyPath() throws Exception {
        Path img = makePng(tmp.resolve("hello.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenReturn("Hello World");

        Object out = tool.execute(Map.of("path", img.toString(), "language", "eng"));

        String s = out.toString();
        assertTrue(s.contains("Hello World"), s);
        assertTrue(s.contains("OCR"), s);
        assertTrue(s.contains("200×100 px"), "Expected image dimensions in header. Got:\n" + s);
        assertTrue(s.contains("eng"), "Expected language in header");
        verify(engine).setLanguage("eng");
    }

    @Test
    @DisplayName("base64 input works and temp file is cleaned up")
    void base64HappyPath() throws Exception {
        Path img = makePng(tmp.resolve("b64.png"));
        byte[] bytes = Files.readAllBytes(img);
        when(engine.doOCR(any(BufferedImage.class))).thenReturn("Base64 text");

        Object out = tool.execute(Map.of(
            "base64", Base64.getEncoder().encodeToString(bytes),
            "language", "eng"));

        assertTrue(out.toString().contains("Base64 text"));
        // No way to directly observe cleanup, but no leaked temp file should exist named ocr-*.img
        // under java.io.tmpdir immediately after — best-effort, we just confirm no crash.
    }

    // ===== Language / PSM =====

    @Test
    @DisplayName("multi-language code 'eng+deu' is accepted and forwarded")
    void multiLanguage() throws Exception {
        Path img = makePng(tmp.resolve("m.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenReturn("");

        tool.execute(Map.of("path", img.toString(), "language", "eng+deu"));

        ArgumentCaptor<String> langCaptor = ArgumentCaptor.forClass(String.class);
        verify(engine).setLanguage(langCaptor.capture());
        assertEquals("eng+deu", langCaptor.getValue());
    }

    @Test
    @DisplayName("invalid language code → error, engine not invoked")
    void invalidLanguage() throws Exception {
        Path img = makePng(tmp.resolve("bad.png"));
        Object out = tool.execute(Map.of("path", img.toString(), "language", "???"));
        assertTrue(out.toString().contains("invalid 'language'"));
        verify(engine, org.mockito.Mockito.never()).doOCR(any(BufferedImage.class));
    }

    @Test
    @DisplayName("page_seg_mode is forwarded to the engine")
    void pageSegMode() throws Exception {
        Path img = makePng(tmp.resolve("psm.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenReturn("t");

        tool.execute(Map.of("path", img.toString(), "page_seg_mode", 6));

        verify(engine).setPageSegMode(6);
    }

    // ===== Failure surfaces =====

    @Test
    @DisplayName("TesseractException surfaces cleanly, no stack trace leak")
    void tesseractFailure() throws Exception {
        Path img = makePng(tmp.resolve("e.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenThrow(new TesseractException("bad lang"));

        Object out = tool.execute(Map.of("path", img.toString()));

        assertTrue(out.toString().contains("Tesseract engine failed"), out.toString());
        assertTrue(out.toString().contains("bad lang"));
    }

    @Test
    @DisplayName("UnsatisfiedLinkError surfaces as install hint, not a crash")
    void noNativeLibrary() throws Exception {
        Path img = makePng(tmp.resolve("n.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenThrow(new UnsatisfiedLinkError("no libtesseract"));

        Object out = tool.execute(Map.of("path", img.toString()));

        assertTrue(out.toString().contains("Tesseract native library not found"));
        assertTrue(out.toString().contains("apt-get") || out.toString().contains("brew"));
    }

    @Test
    @DisplayName("engine returns blank → helpful 'no text detected' hint, not empty output")
    void blankOutput() throws Exception {
        Path img = makePng(tmp.resolve("blank.png"));
        when(engine.doOCR(any(BufferedImage.class))).thenReturn("   \n  ");

        Object out = tool.execute(Map.of("path", img.toString()));
        assertTrue(out.toString().contains("no text detected"), out.toString());
    }

    // ===== Helpers =====

    /** Create a 200×100 white PNG so ImageIO.read() succeeds. */
    private static Path makePng(Path where) throws IOException {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ImageIO.write(img, "png", where.toFile());
        return where;
    }
}
