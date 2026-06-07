package ai.intelliswarm.swarmai.tool.office;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XlsxAuthorAndAuditTest {

    @Test
    void authorWritesSheetWithFormulasAndHardcodes(@TempDir Path tempDir) throws Exception {
        XlsxAuthorTool author = new XlsxAuthorTool(tempDir);
        Map<String, Object> spec = Map.of(
                "path", "test.xlsx",
                "sheets", List.of(Map.of(
                        "name", "Income Statement",
                        "headers", List.of("Line", "FY23", "FY24", "FY25E"),
                        "rows", List.of(
                                Map.of("cells", List.of(
                                        Map.of("text", "Revenue"),
                                        Map.of("number", 1000),
                                        Map.of("number", 1200),
                                        Map.of("formula", "C2*1.1")
                                )),
                                Map.of("cells", List.of(
                                        Map.of("text", "Gross margin"),
                                        Map.of("percent", 0.42)
                                ))
                        ),
                        "freezeHeader", true
                )));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) author.execute(Map.of("spec", spec));
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("formulaCount")).isEqualTo(1);
        assertThat(result.get("hardcodeCount")).isEqualTo(3); // 1000, 1200, 0.42

        Path outPath = tempDir.resolve("test.xlsx");
        assertThat(outPath).exists();

        // Verify the file is readable and content matches spec
        try (var in = new FileInputStream(outPath.toFile());
             var wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Income Statement");
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Line");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("FY25E");

            Row revenue = sheet.getRow(1);
            assertThat(revenue.getCell(1).getNumericCellValue()).isEqualTo(1000.0);
            assertThat(revenue.getCell(3).getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(revenue.getCell(3).getCellFormula()).isEqualTo("C2*1.1");
        }
    }

    @Test
    void authorRejectsPathTraversal(@TempDir Path tempDir) {
        XlsxAuthorTool author = new XlsxAuthorTool(tempDir);
        Map<String, Object> spec = Map.of(
                "sheets", List.of(Map.of("name", "x", "rows", List.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) author.execute(
                Map.of("path", "../escape.xlsx", "spec", spec));
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("base directory");
    }

    @Test
    void authorRejectsMissingSpec() {
        XlsxAuthorTool author = new XlsxAuthorTool();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) author.execute(Map.of());
        assertThat(result.get("ok")).isEqualTo(false);
    }

    @Test
    void auditPassesCleanSpreadsheet(@TempDir Path tempDir) throws Exception {
        Path xlsx = writeMixedSheet(tempDir, "clean.xlsx",
                /*formulas*/ 10, /*hardcodes*/ 3, /*errors*/ 0);
        XlsxAuditTool audit = new XlsxAuditTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) audit.execute(Map.of("path", "clean.xlsx"));
        assertThat(report.get("ok")).isEqualTo(true);
        assertThat(report.get("audit")).isEqualTo("PASS");
        assertThat(report.get("totalFormulas")).isEqualTo(10);
        assertThat(report.get("totalErrors")).isEqualTo(0);
    }

    @Test
    void auditFailsWhenHardcodeRatioExceedsThreshold(@TempDir Path tempDir) throws Exception {
        // 2 formulas / 8 hardcodes → ratio 0.8 > default 0.4
        Path xlsx = writeMixedSheet(tempDir, "dirty.xlsx",
                /*formulas*/ 2, /*hardcodes*/ 8, /*errors*/ 0);
        XlsxAuditTool audit = new XlsxAuditTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) audit.execute(Map.of("path", "dirty.xlsx"));
        assertThat(report.get("audit")).isEqualTo("FAIL");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) report.get("sheets");
        assertThat(sheets.get(0).get("exceedsHardcodeThreshold")).isEqualTo(true);
    }

    @Test
    void auditCustomThresholdRespected(@TempDir Path tempDir) throws Exception {
        Path xlsx = writeMixedSheet(tempDir, "borderline.xlsx",
                /*formulas*/ 5, /*hardcodes*/ 5, /*errors*/ 0);
        XlsxAuditTool audit = new XlsxAuditTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> tight = (Map<String, Object>) audit.execute(
                Map.of("path", "borderline.xlsx", "hardcodeThresholdRatio", 0.3));
        assertThat(tight.get("audit")).isEqualTo("FAIL");
        @SuppressWarnings("unchecked")
        Map<String, Object> loose = (Map<String, Object>) audit.execute(
                Map.of("path", "borderline.xlsx", "hardcodeThresholdRatio", 0.8));
        assertThat(loose.get("audit")).isEqualTo("PASS");
    }

    @Test
    void auditFlagsMagicNumbers(@TempDir Path tempDir) throws Exception {
        // Single sheet with one large unformatted hardcode → MAGIC_NUMBER finding
        Path file = tempDir.resolve("magic.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("M");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(12345.67);  // large + no format → magic
            row.createCell(1).setCellFormula("A1*1.1"); // formula → fine
            try (var out = new java.io.FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }
        XlsxAuditTool audit = new XlsxAuditTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) audit.execute(Map.of("path", "magic.xlsx"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) report.get("findings");
        assertThat(findings).anySatisfy(f ->
                assertThat(f.get("code")).isEqualTo("MAGIC_NUMBER"));
    }

    @Test
    void auditRejectsMissingFile(@TempDir Path tempDir) {
        XlsxAuditTool audit = new XlsxAuditTool(tempDir);
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) audit.execute(
                Map.of("path", "does-not-exist.xlsx"));
        assertThat(report.get("ok")).isEqualTo(false);
    }

    // ---------- helpers ----------

    private static Path writeMixedSheet(Path dir, String name, int formulas, int hardcodes, int errors)
            throws Exception {
        Path file = dir.resolve(name);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("S");
            int rowIdx = 0;
            // Seed cell so formulas have something to reference
            sheet.createRow(rowIdx++).createCell(0).setCellValue(100.0);
            for (int i = 0; i < formulas; i++) {
                sheet.createRow(rowIdx++).createCell(0).setCellFormula("A1*1.1");
            }
            for (int i = 0; i < hardcodes; i++) {
                sheet.createRow(rowIdx++).createCell(0).setCellValue(42.0);
            }
            for (int i = 0; i < errors; i++) {
                sheet.createRow(rowIdx++).createCell(0).setCellFormula("1/0");
            }
            try (var out = new java.io.FileOutputStream(file.toFile())) {
                wb.write(out);
            }
        }
        return file;
    }
}
