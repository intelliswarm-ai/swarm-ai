package ai.intelliswarm.swarmai.tool.vision;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * OcrTool — extract text from images (PNG/JPEG/TIFF/BMP/GIF) using Tesseract via Tess4J.
 *
 * <p>Accepts:
 * <ul>
 *   <li>{@code path}     — local file path (absolute preferred)</li>
 *   <li>{@code url}      — http(s):// URL (downloaded to a temp file)</li>
 *   <li>{@code base64}   — inline base64 image bytes</li>
 * </ul>
 *
 * <p>Languages use Tesseract codes (e.g. {@code "eng"}, {@code "deu"}, {@code "eng+deu"}).
 * Tesseract must be installed on the host (e.g. {@code apt install tesseract-ocr} or
 * {@code brew install tesseract}) plus language-data files. The tool auto-detects tessdata at:
 * <ol>
 *   <li>{@code swarmai.tools.ocr.tessdata-path} property</li>
 *   <li>{@code TESSDATA_PREFIX} env var</li>
 *   <li>Common install locations on Linux / macOS</li>
 * </ol>
 *
 * <p>Permission level is {@code READ_ONLY} — pure data extraction.
 */
@Component
public class OcrTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(OcrTool.class);
    private static final int MAX_URL_BYTES = 20 * 1024 * 1024; // 20 MiB
    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private static final List<String> TESSDATA_FALLBACKS = List.of(
        "/usr/share/tesseract-ocr/5/tessdata",
        "/usr/share/tesseract-ocr/4/tessdata",
        "/usr/share/tessdata",
        "/usr/local/share/tessdata",
        "/opt/homebrew/share/tessdata",
        "C:/Program Files/Tesseract-OCR/tessdata"
    );

    @Value("${swarmai.tools.ocr.tessdata-path:}")
    private String tessdataPath = "";

    private final Supplier<ITesseract> engineFactory;

    public OcrTool() {
        this(Tesseract::new);
    }

    /** Test-friendly: lets unit tests inject a fake engine that never touches the JNI layer. */
    OcrTool(Supplier<ITesseract> engineFactory) {
        this.engineFactory = engineFactory;
    }

    @Override public String getFunctionName() { return "ocr"; }

    @Override
    public String getDescription() {
        return "Extract text from images (PNG/JPEG/TIFF/BMP/GIF) via Tesseract OCR. " +
               "Accepts a local file 'path', a remote 'url', or inline 'base64' bytes. " +
               "Supports multi-language output (e.g. language='eng+deu'). Requires Tesseract " +
               "binaries + language data on the host.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        SourceRef src;
        try {
            src = resolveSource(parameters);
            if (src == null) {
                return "Error: provide 'path', 'url', or 'base64'.";
            }
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error: could not load image — " + e.getMessage();
        }
        File source = src.file();
        boolean tempFile = src.isTemp();

        String language = asString(parameters.getOrDefault("language", "eng"));
        if (!language.matches("[a-zA-Z0-9+_-]{2,40}")) {
            return "Error: invalid 'language' code. Use Tesseract codes like 'eng', 'deu', 'eng+deu'.";
        }
        Integer pageSegMode = parseOptionalInt(parameters.get("page_seg_mode"));

        try {
            ITesseract engine = engineFactory.get();
            engine.setLanguage(language);

            String resolvedTess = resolveTessdata();
            if (resolvedTess != null) engine.setDatapath(resolvedTess);

            if (pageSegMode != null) engine.setPageSegMode(pageSegMode);

            // Force TIFF/BMP/PNG/JPG decoding via ImageIO first — gives us a clearer failure
            // than Tess4J's internal format probing if the file is malformed.
            BufferedImage img = ImageIO.read(source);
            if (img == null) {
                return "Error: could not decode image (unsupported format or corrupt file). " +
                       "Supported: PNG, JPEG, TIFF, BMP, GIF.";
            }

            long t0 = System.currentTimeMillis();
            String text = engine.doOCR(img);
            long elapsed = System.currentTimeMillis() - t0;

            if (text == null) text = "";
            text = text.trim();
            String trimmed = text.isBlank()
                ? "(no text detected — try a different page_seg_mode, enhance contrast, or add the right language pack)"
                : text;

            StringBuilder out = new StringBuilder();
            out.append("**OCR** — ").append(img.getWidth()).append("×").append(img.getHeight())
               .append(" px · ").append(language).append(" · ").append(elapsed).append(" ms\n\n");
            out.append(trimmed);
            return out.toString();

        } catch (UnsatisfiedLinkError e) {
            return "Error: Tesseract native library not found. Install Tesseract OCR on this host " +
                   "(apt-get install tesseract-ocr / brew install tesseract) and ensure the JVM can load it.";
        } catch (TesseractException e) {
            return "Error: Tesseract engine failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("OcrTool unexpected error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        } finally {
            if (tempFile && source != null && source.exists()) {
                try { Files.deleteIfExists(source.toPath()); }
                catch (IOException ignored) { /* best-effort cleanup */ }
            }
        }
    }

    // ---------- source resolution ----------

    /** Where the image bytes came from, and whether we created a throw-away temp file for them. */
    private record SourceRef(File file, boolean isTemp) {}

    private SourceRef resolveSource(Map<String, Object> parameters) throws IOException {
        String path = asString(parameters.get("path"));
        String url = asString(parameters.get("url"));
        String base64 = asString(parameters.get("base64"));

        int provided = (isNonBlank(path) ? 1 : 0) + (isNonBlank(url) ? 1 : 0) + (isNonBlank(base64) ? 1 : 0);
        if (provided == 0) return null;
        if (provided > 1) throw new IllegalArgumentException("provide exactly one of 'path', 'url', or 'base64'.");

        if (isNonBlank(path)) {
            File f = Paths.get(path).toFile();
            if (!f.exists()) throw new IllegalArgumentException("file not found: " + path);
            if (!f.isFile())  throw new IllegalArgumentException("not a file: " + path);
            return new SourceRef(f, false);
        }
        if (isNonBlank(url)) {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("only http(s) URLs are supported; got: " + url);
            }
            File tmp = Files.createTempFile("ocr-", ".img").toFile();
            try (var in = new URL(url).openStream()) {
                long copied = Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (copied > MAX_URL_BYTES) {
                    Files.deleteIfExists(tmp.toPath());
                    throw new IllegalArgumentException("downloaded image exceeds " + MAX_URL_BYTES + " bytes.");
                }
            }
            return new SourceRef(tmp, true);
        }
        // base64
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("'base64' is not valid base64: " + e.getMessage()); }
        if (bytes.length == 0) throw new IllegalArgumentException("'base64' decoded to zero bytes.");
        File tmp = Files.createTempFile("ocr-", ".img").toFile();
        try (var in = new ByteArrayInputStream(bytes)) {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new SourceRef(tmp, true);
    }

    private String resolveTessdata() {
        if (isNonBlank(tessdataPath) && Files.isDirectory(Path.of(tessdataPath))) return tessdataPath;
        String env = System.getenv("TESSDATA_PREFIX");
        if (isNonBlank(env) && Files.isDirectory(Path.of(env))) return env;
        for (String candidate : TESSDATA_FALLBACKS) {
            if (Files.isDirectory(Path.of(candidate))) return candidate;
        }
        return null; // let Tess4J fall back to its own default
    }

    private static boolean isNonBlank(String s) { return s != null && !s.isBlank(); }

    private static Integer parseOptionalInt(Object raw) {
        if (raw == null) return null;
        try {
            return raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException e) { return null; }
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        addStringProp(props, "path",     "Local file path to the image.");
        addStringProp(props, "url",      "Remote http(s) URL of the image (≤ 20 MiB).");
        addStringProp(props, "base64",   "Inline base64-encoded image bytes.");
        addStringProp(props, "language", "Tesseract language code(s), e.g. 'eng', 'eng+deu'. Default 'eng'.");

        Map<String, Object> psm = new HashMap<>();
        psm.put("type", "integer");
        psm.put("description", "Page segmentation mode 0..13 (see Tesseract docs). Default: auto.");
        props.put("page_seg_mode", psm);

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return true; }
    @Override public String getCategory() { return "vision"; }
    @Override public List<String> getTags() { return List.of("ocr", "tesseract", "vision", "documents"); }

    @Override
    public String getTriggerWhen() {
        return "User has an image or scanned document (invoice, receipt, contract, screenshot) " +
               "and needs its text content as plain text for downstream analysis.";
    }

    @Override
    public String getAvoidWhen() {
        return "Input is already a text-based PDF (use pdf_read) or a structured file (file_read). " +
               "Don't use OCR when the text is embedded.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().bins("tesseract").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Header line with image dimensions / lang / latency, followed by extracted text.");
    }

    @Override
    public String smokeTest() {
        try {
            engineFactory.get();
            return null;
        } catch (UnsatisfiedLinkError e) {
            return "Tesseract native library not loadable: " + e.getMessage();
        } catch (Exception e) {
            return "OcrTool engine creation failed: " + e.getMessage();
        }
    }

    public record Request(String path, String url, String base64, String language, Integer page_seg_mode) {}
}
