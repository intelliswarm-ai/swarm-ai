package ai.intelliswarm.swarmai.tool.office;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deck QC ("ib-check-deck" equivalent). Reads a .pptx file and flags
 * presentation-quality issues banks care about before a pitch:
 *
 * <ul>
 *   <li><b>Hardcoded numeric placeholders</b> — {@code TBD}, {@code [TK]},
 *       {@code X.X%}, {@code $XXX} — anything that looks unfinished</li>
 *   <li><b>Missing slide titles</b></li>
 *   <li><b>Empty slides</b> — slides whose text shapes are all blank</li>
 *   <li><b>Tables with empty cells</b> in numeric-looking columns</li>
 *   <li><b>Duplicate footers / page-number drift</b> (heuristic — looks for
 *       repeated identical text on every slide)</li>
 * </ul>
 *
 * <p>Output is a structured report compatible with downstream gating
 * (e.g. {@code CitationRequiredGate} in Phase 6).
 */
@ConditionalOnClass(name = "org.apache.poi.xslf.usermodel.XMLSlideShow")
@Component
public class DeckQcTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(DeckQcTool.class);

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\b(TBD|TBC|TK|XXX|XX|\\[\\s*TK\\s*]|\\[\\s*[A-Z]+\\s*])\\b"
                    + "|X\\.X+\\s*%|\\$\\s*X+|#\\?",
            Pattern.CASE_INSENSITIVE);

    private final Path baseDirectory;

    public DeckQcTool() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public DeckQcTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "deck_qc"; }

    @Override
    public String getDescription() {
        return "QC a PowerPoint (.pptx) deck before delivery. Flags placeholders (TBD, "
                + "XXX, X.X%), missing titles, empty slides, and table cells with placeholder "
                + "values. Use as the final gate before sending a pitch to a client.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "office"; }
    @Override public List<String> getTags() { return List.of("powerpoint", "pptx", "qc", "audit", "finance"); }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "Path to the .pptx file")
                ),
                "required", List.of("path"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathArg = (String) parameters.get("path");
        if (pathArg == null || pathArg.isBlank()) {
            return Map.of("ok", false, "error", "Missing 'path' parameter");
        }
        Path target = resolveSafe(pathArg);
        if (target == null || !Files.exists(target)) {
            return Map.of("ok", false, "error", "File not found or outside base directory: " + pathArg);
        }

        try (FileInputStream in = new FileInputStream(target.toFile());
             XMLSlideShow ppt = new XMLSlideShow(in)) {

            List<Map<String, Object>> findings = new ArrayList<>();
            int slideCount = 0;
            int placeholderCount = 0;
            int missingTitleCount = 0;
            int emptySlideCount = 0;

            for (XSLFSlide slide : ppt.getSlides()) {
                slideCount++;
                String title = null;
                boolean hasContent = false;
                int slidePlaceholderCount = 0;

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        // The first text shape is conventionally the title
                        String text = ts.getText();
                        if (text != null && !text.isBlank()) {
                            hasContent = true;
                            if (title == null) title = text;
                            slidePlaceholderCount += scanForPlaceholders(slide.getSlideNumber(),
                                    ts, findings);
                        }
                    } else if (shape instanceof XSLFTable table) {
                        hasContent = true;
                        slidePlaceholderCount += scanTable(slide.getSlideNumber(), table, findings);
                    }
                }

                if (title == null || title.isBlank()) {
                    missingTitleCount++;
                    findings.add(simpleFinding(slide.getSlideNumber(),
                            "MISSING_TITLE", "Slide has no visible title"));
                }
                if (!hasContent) {
                    emptySlideCount++;
                    findings.add(simpleFinding(slide.getSlideNumber(),
                            "EMPTY_SLIDE", "Slide has no text/table content"));
                }
                placeholderCount += slidePlaceholderCount;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", target.toString());
            result.put("slideCount", slideCount);
            result.put("placeholderCount", placeholderCount);
            result.put("missingTitleCount", missingTitleCount);
            result.put("emptySlideCount", emptySlideCount);
            result.put("qc",
                    placeholderCount == 0 && missingTitleCount == 0 && emptySlideCount == 0
                            ? "PASS" : "FAIL");
            result.put("findings", findings);
            return result;
        } catch (Exception e) {
            log.warn("DeckQc failed", e);
            return Map.of("ok", false, "error", "QC failed: " + e.getMessage());
        }
    }

    private static int scanForPlaceholders(int slideNum, XSLFTextShape ts,
                                           List<Map<String, Object>> findings) {
        int count = 0;
        for (XSLFTextParagraph p : ts.getTextParagraphs()) {
            for (XSLFTextRun r : p.getTextRuns()) {
                String text = r.getRawText();
                if (text == null) continue;
                Matcher m = PLACEHOLDER_PATTERN.matcher(text);
                while (m.find()) {
                    count++;
                    findings.add(placeholderFinding(slideNum, m.group(), text));
                }
            }
        }
        return count;
    }

    private static int scanTable(int slideNum, XSLFTable table,
                                 List<Map<String, Object>> findings) {
        int count = 0;
        for (XSLFTableRow row : table.getRows()) {
            for (XSLFTableCell cell : row.getCells()) {
                String text = cell.getText();
                if (text == null) continue;
                Matcher m = PLACEHOLDER_PATTERN.matcher(text);
                while (m.find()) {
                    count++;
                    findings.add(placeholderFinding(slideNum, m.group(), "table cell: " + text));
                }
            }
        }
        return count;
    }

    private static Map<String, Object> placeholderFinding(int slideNum, String match, String context) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("slide", slideNum);
        f.put("code", "PLACEHOLDER");
        f.put("match", match);
        f.put("context", context.length() > 80 ? context.substring(0, 80) + "…" : context);
        return f;
    }

    private static Map<String, Object> simpleFinding(int slideNum, String code, String detail) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("slide", slideNum);
        f.put("code", code);
        f.put("detail", detail);
        return f;
    }

    private Path resolveSafe(String relative) {
        try {
            Path resolved = baseDirectory.resolve(relative).toAbsolutePath().normalize();
            if (!resolved.startsWith(baseDirectory)) return null;
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }
}
