package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.office.XlsxAuthorTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-page company tear sheet generator. Composes a structured xlsx spec from
 * the input company snapshot, then delegates to {@link XlsxAuthorTool} to
 * write the file. Output: path + summary of what was written.
 *
 * <p>This is the canonical "compose two tools" pattern for finance: the
 * tool itself doesn't reimplement Excel, it builds a spec and hands off.
 * Same pattern works for {@code IcMemoTool} → {@code PptxAuthorTool}.
 *
 * <p>Input shape:
 * <pre>{@code
 * {
 *   "path": "ACME_tear_sheet.xlsx",
 *   "company": {
 *     "ticker": "ACME", "name": "ACME Corp",
 *     "sector": "Industrials", "marketCap": 12000.0,
 *     "ev": 13200.0, "lastPrice": 120.0
 *   },
 *   "financials": [
 *     {"year": "FY23", "revenue": 1000, "ebitda": 280, "fcf": 180, "netIncome": 130},
 *     {"year": "FY24", "revenue": 1200, "ebitda": 350, "fcf": 220, "netIncome": 170}
 *   ],
 *   "multiples": [
 *     {"label": "EV/Revenue", "value": 11.0},
 *     {"label": "EV/EBITDA", "value": 37.7},
 *     {"label": "P/E", "value": 70.6}
 *   ]
 * }
 * }</pre>
 */
@ConditionalOnClass(name = "org.apache.poi.xssf.usermodel.XSSFWorkbook")
@Component
public class TearSheetTool implements FinanceTool {

    private final XlsxAuthorTool xlsxAuthor;

    public TearSheetTool() {
        this(new XlsxAuthorTool(Paths.get(System.getProperty("user.dir"))));
    }

    public TearSheetTool(XlsxAuthorTool xlsxAuthor) {
        this.xlsxAuthor = xlsxAuthor;
    }

    @Override public String getFunctionName() { return "tear_sheet"; }

    @Override
    public String getDescription() {
        return "Generate a one-page company tear sheet as .xlsx — summary metrics, "
                + "historical financials, trading multiples. Composes structured input "
                + "into an XlsxAuthorTool spec; delegates the actual file-write.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "finance"; }
    @Override public List<String> getTags() { return List.of("finance", "tear-sheet", "xlsx", "summary"); }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string"),
                        "company", Map.of("type", "object"),
                        "financials", Map.of("type", "array", "items", Map.of("type", "object")),
                        "multiples", Map.of("type", "array", "items", Map.of("type", "object"))),
                "required", List.of("path", "company"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        String path = (String) parameters.get("path");
        if (path == null || path.isBlank()) {
            return Map.of("ok", false, "error", "Missing 'path' parameter");
        }
        Map<String, Object> company = (Map<String, Object>) parameters.get("company");
        if (company == null) {
            return Map.of("ok", false, "error", "Missing 'company' parameter");
        }
        List<Map<String, Object>> financials =
                (List<Map<String, Object>>) parameters.getOrDefault("financials", List.of());
        List<Map<String, Object>> multiples =
                (List<Map<String, Object>>) parameters.getOrDefault("multiples", List.of());

        Map<String, Object> spec = buildSpec(path, company, financials, multiples);
        return xlsxAuthor.execute(Map.of("spec", spec));
    }

    Map<String, Object> buildSpec(String path, Map<String, Object> company,
                                  List<Map<String, Object>> financials,
                                  List<Map<String, Object>> multiples) {
        // Summary sheet rows
        List<Map<String, Object>> summaryRows = new ArrayList<>();
        appendCompanyRow(summaryRows, "Ticker", company.get("ticker"));
        appendCompanyRow(summaryRows, "Name", company.get("name"));
        appendCompanyRow(summaryRows, "Sector", company.get("sector"));
        appendNumericRow(summaryRows, "Market Cap ($m)", company.get("marketCap"));
        appendNumericRow(summaryRows, "Enterprise Value ($m)", company.get("ev"));
        appendNumericRow(summaryRows, "Last Price", company.get("lastPrice"));

        // Financials sheet
        List<String> finHeaders = List.of("Year", "Revenue", "EBITDA", "FCF", "Net Income");
        List<Map<String, Object>> finRows = new ArrayList<>();
        for (Map<String, Object> f : financials) {
            finRows.add(Map.of("cells", List.of(
                    Map.of("text", String.valueOf(f.getOrDefault("year", ""))),
                    asNumber(f.get("revenue")),
                    asNumber(f.get("ebitda")),
                    asNumber(f.get("fcf")),
                    asNumber(f.get("netIncome")))));
        }

        // Multiples sheet
        List<String> multHeaders = List.of("Multiple", "Value");
        List<Map<String, Object>> multRows = new ArrayList<>();
        for (Map<String, Object> m : multiples) {
            multRows.add(Map.of("cells", List.of(
                    Map.of("text", String.valueOf(m.getOrDefault("label", ""))),
                    asNumber(m.get("value")))));
        }

        Map<String, Object> summarySheet = new LinkedHashMap<>();
        summarySheet.put("name", "Summary");
        summarySheet.put("headers", List.of("Metric", "Value"));
        summarySheet.put("rows", summaryRows);
        summarySheet.put("freezeHeader", true);

        Map<String, Object> finSheet = new LinkedHashMap<>();
        finSheet.put("name", "Financials");
        finSheet.put("headers", finHeaders);
        finSheet.put("rows", finRows);
        finSheet.put("freezeHeader", true);

        Map<String, Object> multSheet = new LinkedHashMap<>();
        multSheet.put("name", "Multiples");
        multSheet.put("headers", multHeaders);
        multSheet.put("rows", multRows);
        multSheet.put("freezeHeader", true);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("path", path);
        spec.put("sheets", List.of(summarySheet, finSheet, multSheet));
        return spec;
    }

    private static void appendCompanyRow(List<Map<String, Object>> rows, String label, Object value) {
        rows.add(Map.of("cells", List.of(
                Map.of("text", label, "bold", true),
                Map.of("text", value == null ? "" : String.valueOf(value)))));
    }

    private static void appendNumericRow(List<Map<String, Object>> rows, String label, Object value) {
        Map<String, Object> v = asNumber(value);
        rows.add(Map.of("cells", List.of(
                Map.of("text", label, "bold", true),
                v)));
    }

    private static Map<String, Object> asNumber(Object value) {
        if (value instanceof Number n) return Map.of("number", n.doubleValue());
        if (value instanceof String s) {
            try { return Map.of("number", Double.parseDouble(s)); }
            catch (NumberFormatException ignored) { return Map.of("text", s); }
        }
        return Map.of("text", value == null ? "" : String.valueOf(value));
    }
}
