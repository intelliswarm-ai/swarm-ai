package ai.intelliswarm.swarmai.tool.office;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Author Excel (.xlsx) files. Builds a workbook from a structured spec the
 * LLM can emit as JSON — sheets, headers, rows of typed cells, formulas,
 * named ranges, basic formatting.
 *
 * <p>Designed for financial modelling output: every numeric cell distinguishes
 * <em>hardcode</em> (a literal) from <em>formula</em> (a calculation), and the
 * companion {@code XlsxAuditTool} flags hardcodes where formulas were expected.
 * Together they form the {@code audit-xls} pattern from Anthropic's
 * financial-services repo.
 *
 * <p>Spec shape (passed via {@code parameters.get("spec")} — typically a
 * {@code Map<String,Object>} parsed from the model's JSON output):
 *
 * <pre>{@code
 * {
 *   "path": "Q4_Model.xlsx",
 *   "sheets": [
 *     {
 *       "name": "Income Statement",
 *       "headers": ["Line item", "FY23", "FY24", "FY25E"],
 *       "rows": [
 *         {"cells": [
 *           {"text": "Revenue"},
 *           {"number": 1000},
 *           {"number": 1200},
 *           {"formula": "C2*1.1"}
 *         ]}
 *       ],
 *       "freezeHeader": true
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Cell shapes: one of {@code text} (string), {@code number} (numeric
 * literal — flagged as hardcode), {@code formula} (calculation,
 * {@code "C2*1.1"}; no leading {@code =}), {@code percent} (numeric formatted
 * as %), or {@code bold: true} on any of the above.
 */
@ConditionalOnClass(name = "org.apache.poi.xssf.usermodel.XSSFWorkbook")
@Component
public class XlsxAuthorTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(XlsxAuthorTool.class);

    private final Path baseDirectory;

    public XlsxAuthorTool() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public XlsxAuthorTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "xlsx_author"; }

    @Override
    public String getDescription() {
        return "Write an Excel (.xlsx) file from a structured spec. "
                + "Each numeric cell declares whether it is a hardcode (literal) or a formula. "
                + "Use for financial models, audit-ready spreadsheets, valuation outputs.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "office"; }
    @Override public List<String> getTags() { return List.of("excel", "xlsx", "finance", "authoring", "spreadsheet"); }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "spec", Map.of("type", "object",
                                "description", "Workbook spec — see XlsxAuthorTool javadoc for shape"),
                        "path", Map.of("type", "string",
                                "description", "Output file path relative to base dir (optional if in spec)")
                ),
                "required", List.of("spec"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        Object specObj = parameters.get("spec");
        if (!(specObj instanceof Map<?, ?> specMap)) {
            return error("Missing or invalid 'spec' parameter (expected object)");
        }
        Map<String, Object> spec = (Map<String, Object>) specMap;

        String pathArg = (String) parameters.getOrDefault("path", spec.get("path"));
        if (pathArg == null || pathArg.isBlank()) {
            return error("Missing 'path' (in parameters or spec)");
        }

        Path target = resolveSafe(pathArg);
        if (target == null) {
            return error("Refusing to write outside base directory");
        }

        List<Map<String, Object>> sheets = (List<Map<String, Object>>) spec.get("sheets");
        if (sheets == null || sheets.isEmpty()) {
            return error("Spec must declare at least one sheet under 'sheets'");
        }

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            StyleCache styles = new StyleCache(wb);
            int cellsWritten = 0;
            int formulaCount = 0;
            int hardcodeCount = 0;

            for (Map<String, Object> sheetSpec : sheets) {
                XSSFSheet sheet = wb.createSheet(safeSheetName((String) sheetSpec.get("name")));
                int rowIdx = 0;

                List<String> headers = (List<String>) sheetSpec.get("headers");
                if (headers != null && !headers.isEmpty()) {
                    Row headerRow = sheet.createRow(rowIdx++);
                    for (int c = 0; c < headers.size(); c++) {
                        Cell cell = headerRow.createCell(c);
                        cell.setCellValue(headers.get(c));
                        cell.setCellStyle(styles.header());
                        cellsWritten++;
                    }
                    if (Boolean.TRUE.equals(sheetSpec.get("freezeHeader"))) {
                        sheet.createFreezePane(0, 1);
                    }
                }

                List<Map<String, Object>> rows = (List<Map<String, Object>>) sheetSpec.get("rows");
                if (rows != null) {
                    for (Map<String, Object> rowSpec : rows) {
                        Row row = sheet.createRow(rowIdx++);
                        List<Map<String, Object>> cellSpecs = (List<Map<String, Object>>) rowSpec.get("cells");
                        if (cellSpecs == null) continue;
                        for (int c = 0; c < cellSpecs.size(); c++) {
                            Map<String, Object> cellSpec = cellSpecs.get(c);
                            Counter counter = writeCell(row.createCell(c), cellSpec, styles);
                            cellsWritten++;
                            formulaCount += counter.formulas;
                            hardcodeCount += counter.hardcodes;
                        }
                    }
                }

                // Reasonable default column width — POI's autoSizeColumn is slow on big sheets.
                int cols = sheet.getRow(0) == null ? 0 : sheet.getRow(0).getLastCellNum();
                for (int c = 0; c < cols; c++) {
                    sheet.setColumnWidth(c, 18 * 256);
                }
            }

            Files.createDirectories(target.getParent());
            try (FileOutputStream out = new FileOutputStream(target.toFile())) {
                wb.write(out);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", target.toString());
            result.put("sheetCount", sheets.size());
            result.put("cellsWritten", cellsWritten);
            result.put("formulaCount", formulaCount);
            result.put("hardcodeCount", hardcodeCount);
            result.put("hardcodeRatio", cellsWritten == 0 ? 0.0
                    : (double) hardcodeCount / (double) (formulaCount + hardcodeCount));
            return result;
        } catch (Exception e) {
            log.warn("XlsxAuthor failed", e);
            return error("Authoring failed: " + e.getMessage());
        }
    }

    private Counter writeCell(Cell cell, Map<String, Object> spec, StyleCache styles) {
        Counter counter = new Counter();
        Object value;
        if ((value = spec.get("formula")) instanceof String f && !f.isBlank()) {
            cell.setCellFormula(f.startsWith("=") ? f.substring(1) : f);
            counter.formulas = 1;
        } else if ((value = spec.get("number")) instanceof Number n) {
            cell.setCellValue(n.doubleValue());
            counter.hardcodes = 1;
        } else if ((value = spec.get("percent")) instanceof Number p) {
            cell.setCellValue(p.doubleValue());
            cell.setCellStyle(styles.percent());
            counter.hardcodes = 1;
        } else if ((value = spec.get("text")) instanceof String s) {
            cell.setCellValue(s);
        } else if ((value = spec.get("bool")) instanceof Boolean b) {
            cell.setCellValue(b);
        }

        if (Boolean.TRUE.equals(spec.get("bold"))) {
            CellStyle base = cell.getCellStyle();
            CellStyle bold = styles.bold(base);
            cell.setCellStyle(bold);
        }
        return counter;
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

    private static String safeSheetName(String name) {
        if (name == null || name.isBlank()) return "Sheet";
        String cleaned = name.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }

    private static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    /** Tracks per-cell hardcode/formula counts. */
    private static final class Counter {
        int formulas;
        int hardcodes;
    }

    /** Lazy-built named cell styles to avoid POI's 64K-style ceiling on big spreadsheets. */
    private static final class StyleCache {
        private final XSSFWorkbook wb;
        private CellStyle header;
        private CellStyle percent;
        private final Map<String, CellStyle> boldVariants = new HashMap<>();

        StyleCache(XSSFWorkbook wb) {
            this.wb = wb;
        }

        CellStyle header() {
            if (header == null) {
                header = wb.createCellStyle();
                Font f = wb.createFont();
                f.setBold(true);
                header.setFont(f);
            }
            return header;
        }

        CellStyle percent() {
            if (percent == null) {
                DataFormat fmt = wb.createDataFormat();
                percent = wb.createCellStyle();
                percent.setDataFormat(fmt.getFormat("0.0%"));
            }
            return percent;
        }

        CellStyle bold(CellStyle base) {
            String key = base == null ? "_default_" : String.valueOf(base.getIndex());
            return boldVariants.computeIfAbsent(key, k -> {
                CellStyle s = wb.createCellStyle();
                if (base != null) s.cloneStyleFrom(base);
                Font f = wb.createFont();
                f.setBold(true);
                s.setFont(f);
                return s;
            });
        }
    }

}
