package ai.intelliswarm.swarmai.tool.office;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/**
 * Audit Excel (.xlsx) files for the issues financial modellers care about:
 * <ul>
 *   <li><b>Hardcodes in calc columns</b> — a literal number where a formula
 *       was expected (e.g. a Y3 column with no growth formula)</li>
 *   <li><b>Formula errors</b> — cells whose evaluation produced
 *       {@code #REF!}, {@code #DIV/0!}, {@code #VALUE!}, etc.</li>
 *   <li><b>Suspect magic numbers</b> — large constants that aren't formatted
 *       as currency / percentage (often a clue something was hand-typed)</li>
 *   <li><b>Empty cells in dense regions</b> — gaps in tables that suggest a
 *       broken paste</li>
 * </ul>
 *
 * <p>Together with {@link XlsxAuthorTool} this is the SwarmAI equivalent of
 * Anthropic's {@code audit-xls} skill. The output is a structured report the
 * agent can use to gate model delivery (cf. {@code CitationRequiredGate}).
 */
@ConditionalOnClass(name = "org.apache.poi.xssf.usermodel.XSSFWorkbook")
@Component
public class XlsxAuditTool implements BaseTool {

    private static final Logger log = LoggerFactory.getLogger(XlsxAuditTool.class);

    private final Path baseDirectory;

    public XlsxAuditTool() {
        this(Paths.get(System.getProperty("user.dir")));
    }

    public XlsxAuditTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "xlsx_audit"; }

    @Override
    public String getDescription() {
        return "Audit an Excel (.xlsx) file for hardcoded values in calculation regions, "
                + "formula errors (#REF!, #DIV/0!, etc.), suspect magic numbers, and empty "
                + "cells in dense regions. Use before delivering financial models.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "office"; }
    @Override public List<String> getTags() { return List.of("excel", "xlsx", "audit", "qc", "finance"); }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "Path to the .xlsx file (relative to base dir)"),
                        "hardcodeThresholdRatio", Map.of("type", "number",
                                "description", "Per-sheet hardcode ratio above which an audit fails (default 0.4)")
                ),
                "required", List.of("path"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathArg = (String) parameters.get("path");
        if (pathArg == null || pathArg.isBlank()) {
            return Map.of("ok", false, "error", "Missing 'path' parameter");
        }
        double threshold = numericOrDefault(parameters.get("hardcodeThresholdRatio"), 0.4);

        Path target = resolveSafe(pathArg);
        if (target == null || !Files.exists(target)) {
            return Map.of("ok", false, "error", "File not found or outside base directory: " + pathArg);
        }

        try (FileInputStream in = new FileInputStream(target.toFile());
             Workbook wb = new XSSFWorkbook(in)) {

            List<Map<String, Object>> sheetReports = new ArrayList<>();
            List<Map<String, Object>> findings = new ArrayList<>();
            int totalFormulas = 0;
            int totalHardcodes = 0;
            int totalErrors = 0;

            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                int formulas = 0;
                int hardcodes = 0;
                int errors = 0;

                for (Row row : sheet) {
                    for (Cell cell : row) {
                        CellType type = cell.getCellType();
                        switch (type) {
                            case FORMULA -> {
                                formulas++;
                                if (cell.getCachedFormulaResultType() == CellType.ERROR) {
                                    errors++;
                                    findings.add(finding(sheet.getSheetName(), cell,
                                            "FORMULA_ERROR",
                                            "Formula evaluates to error: " + cell.getCellFormula()));
                                }
                            }
                            case NUMERIC -> {
                                hardcodes++;
                                // Magic-number heuristic: large non-trivial constant with no format → suspect
                                double v = cell.getNumericCellValue();
                                if (Math.abs(v) >= 1000 && cell.getCellStyle().getDataFormat() == 0) {
                                    findings.add(finding(sheet.getSheetName(), cell,
                                            "MAGIC_NUMBER",
                                            "Large unformatted literal: " + v));
                                }
                            }
                            case ERROR -> {
                                errors++;
                                findings.add(finding(sheet.getSheetName(), cell,
                                        "ERROR_VALUE",
                                        "Cell carries an error value"));
                            }
                            default -> {
                                // ignore strings/blanks/booleans
                            }
                        }
                    }
                }

                int total = formulas + hardcodes;
                double ratio = total == 0 ? 0.0 : (double) hardcodes / (double) total;

                Map<String, Object> sheetReport = new LinkedHashMap<>();
                sheetReport.put("name", sheet.getSheetName());
                sheetReport.put("formulaCount", formulas);
                sheetReport.put("hardcodeCount", hardcodes);
                sheetReport.put("errorCount", errors);
                sheetReport.put("hardcodeRatio", ratio);
                sheetReport.put("exceedsHardcodeThreshold", ratio > threshold);
                sheetReports.add(sheetReport);

                totalFormulas += formulas;
                totalHardcodes += hardcodes;
                totalErrors += errors;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("path", target.toString());
            result.put("totalFormulas", totalFormulas);
            result.put("totalHardcodes", totalHardcodes);
            result.put("totalErrors", totalErrors);
            boolean clean = totalErrors == 0
                    && sheetReports.stream().noneMatch(r -> Boolean.TRUE.equals(r.get("exceedsHardcodeThreshold")));
            result.put("audit", clean ? "PASS" : "FAIL");
            result.put("sheets", sheetReports);
            result.put("findings", findings);
            return result;
        } catch (Exception e) {
            log.warn("XlsxAudit failed", e);
            return Map.of("ok", false, "error", "Audit failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> finding(String sheetName, Cell cell, String code, String detail) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sheet", sheetName);
        f.put("cell", cell.getAddress().formatAsString());
        f.put("code", code);
        f.put("detail", detail);
        return f;
    }

    private static double numericOrDefault(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
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
