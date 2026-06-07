package ai.intelliswarm.swarmai.tool.office;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.awt.Rectangle;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author PowerPoint (.pptx) decks from a structured spec. Supports title +
 * content slides, bullet lists, tables, and uses the master's title/content
 * layouts where available so branded templates flow through.
 *
 * <p>Spec shape:
 * <pre>{@code
 * {
 *   "path": "ACME_pitch.pptx",
 *   "slides": [
 *     {"layout": "TITLE", "title": "ACME Co — Investment Pitch", "subtitle": "Q4 2026"},
 *     {"layout": "TITLE_AND_CONTENT", "title": "Investment Highlights",
 *      "bullets": ["#1 market position", "30% YoY growth", "Margin expansion"]},
 *     {"layout": "TITLE_AND_CONTENT", "title": "Comps",
 *      "table": {
 *        "headers": ["Company", "EV/Revenue", "EV/EBITDA"],
 *        "rows": [["ACME", "8.2x", "22.1x"], ["Peer A", "7.5x", "19.0x"]]
 *      }}
 *   ]
 * }
 * }</pre>
 *
 * <p>Layouts accepted: {@code TITLE}, {@code TITLE_AND_CONTENT}, {@code BLANK}.
 */
@ConditionalOnClass(name = "org.apache.poi.xslf.usermodel.XMLSlideShow")
@Component
public class PptxAuthorTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(PptxAuthorTool.class);

    private final Path baseDirectory;

    public PptxAuthorTool() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public PptxAuthorTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "pptx_author"; }

    @Override
    public String getDescription() {
        return "Write a PowerPoint (.pptx) deck from a structured spec. "
                + "Supports title + bullets + tables across slides. Use for pitch decks, "
                + "IC memos rendered as decks, morning notes, board updates.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "office"; }
    @Override public List<String> getTags() { return List.of("powerpoint", "pptx", "deck", "authoring", "finance"); }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "spec", Map.of("type", "object",
                                "description", "Deck spec — see PptxAuthorTool javadoc"),
                        "path", Map.of("type", "string",
                                "description", "Output file path (optional if in spec)")
                ),
                "required", List.of("spec"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        Object specObj = parameters.get("spec");
        if (!(specObj instanceof Map<?, ?> specMap)) {
            return Map.of("ok", false, "error", "Missing or invalid 'spec' parameter");
        }
        Map<String, Object> spec = (Map<String, Object>) specMap;

        String pathArg = (String) parameters.getOrDefault("path", spec.get("path"));
        if (pathArg == null || pathArg.isBlank()) {
            return Map.of("ok", false, "error", "Missing 'path' (in parameters or spec)");
        }
        Path target = resolveSafe(pathArg);
        if (target == null) {
            return Map.of("ok", false, "error", "Refusing to write outside base directory");
        }

        List<Map<String, Object>> slideSpecs = (List<Map<String, Object>>) spec.get("slides");
        if (slideSpecs == null || slideSpecs.isEmpty()) {
            return Map.of("ok", false, "error", "Spec must declare at least one slide under 'slides'");
        }

        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            int tableCount = 0;
            int bulletCount = 0;

            for (Map<String, Object> slideSpec : slideSpecs) {
                String layoutName = (String) slideSpec.getOrDefault("layout", "TITLE_AND_CONTENT");
                XSLFSlideLayout layout = pickLayout(master, layoutName);
                XSLFSlide slide = ppt.createSlide(layout);

                String title = (String) slideSpec.get("title");
                if (title != null) {
                    XSLFTextShape t = slide.getPlaceholder(0);
                    if (t != null) {
                        t.clearText();
                        t.setText(title);
                    } else {
                        XSLFTextBox box = slide.createTextBox();
                        box.setAnchor(new Rectangle(36, 36, 700, 60));
                        XSLFTextParagraph p = box.addNewTextParagraph();
                        XSLFTextRun r = p.addNewTextRun();
                        r.setText(title);
                        r.setBold(true);
                        r.setFontSize(28.0);
                    }
                }

                String subtitle = (String) slideSpec.get("subtitle");
                if (subtitle != null) {
                    XSLFTextShape s = slide.getPlaceholder(1);
                    if (s != null) {
                        s.clearText();
                        s.setText(subtitle);
                    }
                }

                List<String> bullets = (List<String>) slideSpec.get("bullets");
                if (bullets != null && !bullets.isEmpty()) {
                    XSLFTextShape body = slide.getPlaceholder(1);
                    if (body == null) {
                        body = slide.createTextBox();
                        body.setAnchor(new Rectangle(36, 110, 700, 380));
                    } else {
                        body.clearText();
                    }
                    for (String bullet : bullets) {
                        XSLFTextParagraph p = body.addNewTextParagraph();
                        p.setBullet(true);
                        XSLFTextRun r = p.addNewTextRun();
                        r.setText(bullet);
                        r.setFontSize(18.0);
                    }
                    bulletCount += bullets.size();
                }

                Map<String, Object> table = (Map<String, Object>) slideSpec.get("table");
                if (table != null) {
                    addTable(slide, table);
                    tableCount++;
                }
            }

            Files.createDirectories(target.getParent());
            try (FileOutputStream out = new FileOutputStream(target.toFile())) {
                ppt.write(out);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", target.toString());
            result.put("slideCount", slideSpecs.size());
            result.put("bulletCount", bulletCount);
            result.put("tableCount", tableCount);
            return result;
        } catch (Exception e) {
            log.warn("PptxAuthor failed", e);
            return Map.of("ok", false, "error", "Authoring failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void addTable(XSLFSlide slide, Map<String, Object> tableSpec) {
        List<String> headers = (List<String>) tableSpec.get("headers");
        List<List<Object>> rows = (List<List<Object>>) tableSpec.get("rows");
        if (headers == null || rows == null || headers.isEmpty()) return;

        XSLFTable table = slide.createTable();
        table.setAnchor(new Rectangle(36, 110, 700, 320));

        XSLFTableRow headerRow = table.addRow();
        headerRow.setHeight(28.0);
        for (String h : headers) {
            XSLFTableCell c = headerRow.addCell();
            XSLFTextParagraph p = c.addNewTextParagraph();
            XSLFTextRun r = p.addNewTextRun();
            r.setText(h);
            r.setBold(true);
            r.setFontSize(13.0);
        }

        for (List<Object> rowData : rows) {
            XSLFTableRow tr = table.addRow();
            tr.setHeight(22.0);
            for (Object v : rowData) {
                XSLFTableCell c = tr.addCell();
                XSLFTextParagraph p = c.addNewTextParagraph();
                XSLFTextRun r = p.addNewTextRun();
                r.setText(String.valueOf(v));
                r.setFontSize(12.0);
            }
        }
    }

    private static XSLFSlideLayout pickLayout(XSLFSlideMaster master, String layoutName) {
        SlideLayout wanted = switch (layoutName.toUpperCase().replace('-', '_')) {
            case "TITLE" -> SlideLayout.TITLE;
            case "BLANK" -> SlideLayout.BLANK;
            default -> SlideLayout.TITLE_AND_CONTENT;
        };
        XSLFSlideLayout layout = master.getLayout(wanted);
        // Fallback to first layout if the template doesn't carry this one
        return layout != null ? layout : master.getSlideLayouts()[0];
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
