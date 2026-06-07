package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.tool.office.XlsxAuthorTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FinanceToolsTest {

    // ---------- DcfModelTool ----------

    @Test
    void dcfProducesEnterpriseValueAndProjections() {
        DcfModelTool dcf = new DcfModelTool();
        Map<String, Object> in = Map.ofEntries(
                Map.entry("ticker", "ACME"),
                Map.entry("baseRevenue", 1000.0),
                Map.entry("baseEbitdaMargin", 0.30),
                Map.entry("taxRate", 0.25),
                Map.entry("capexPctOfRevenue", 0.05),
                Map.entry("deltaWcPctOfRevenue", 0.02),
                Map.entry("daPctOfRevenue", 0.04),
                Map.entry("projectionYears", 5),
                Map.entry("revenueGrowth", List.of(0.15, 0.12, 0.10, 0.08, 0.06)),
                Map.entry("marginExpansionPerYear", 0.005),
                Map.entry("wacc", 0.09),
                Map.entry("terminalGrowth", 0.025),
                Map.entry("netDebt", 200.0),
                Map.entry("sharesOutstanding", 100.0));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) dcf.execute(in);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("enterpriseValue")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.DOUBLE)
                .isGreaterThan(0.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projections = (List<Map<String, Object>>) result.get("projections");
        assertThat(projections).hasSize(5);
        // Revenue grows year over year
        double y1 = (Double) projections.get(0).get("revenue");
        double y5 = (Double) projections.get(4).get("revenue");
        assertThat(y5).isGreaterThan(y1);
        // Equity = EV - net debt
        double ev = (Double) result.get("enterpriseValue");
        double eq = (Double) result.get("equityValue");
        assertThat(eq).isCloseTo(ev - 200.0, within(0.01));
        // Per-share = equity / shares
        double perShare = (Double) result.get("perShareValue");
        assertThat(perShare).isCloseTo(eq / 100.0, within(0.01));
    }

    @Test
    void dcfSensitivityHasNineCells() {
        DcfModelTool dcf = new DcfModelTool();
        Map<String, Object> in = Map.of(
                "baseRevenue", 1000.0,
                "projectionYears", 3,
                "revenueGrowth", List.of(0.1, 0.1, 0.1),
                "wacc", 0.10,
                "terminalGrowth", 0.025);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) dcf.execute(in);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sensitivity = (List<Map<String, Object>>) result.get("sensitivity");
        assertThat(sensitivity).hasSize(9);
    }

    @Test
    void dcfRejectsMismatchedGrowthArray() {
        DcfModelTool dcf = new DcfModelTool();
        Map<String, Object> in = Map.of(
                "baseRevenue", 1000.0,
                "projectionYears", 5,
                "revenueGrowth", List.of(0.1, 0.1, 0.1),
                "wacc", 0.10,
                "terminalGrowth", 0.025);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) dcf.execute(in);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("error").toString()).contains("revenueGrowth");
    }

    // ---------- CompsAnalysisTool ----------

    @Test
    void compsComputeMedianAndImpliedEquity() {
        CompsAnalysisTool tool = new CompsAnalysisTool();
        Map<String, Object> in = Map.of(
                "target", Map.of(
                        "ticker", "ACME",
                        "revenue", 1000.0,
                        "ebitda", 300.0,
                        "netIncome", 150.0,
                        "netDebt", 200.0,
                        "sharesOutstanding", 100.0),
                "peers", List.of(
                        Map.of("ticker", "P1", "evRevenue", 7.0, "evEbitda", 18.0, "pe", 22.0),
                        Map.of("ticker", "P2", "evRevenue", 8.0, "evEbitda", 20.0, "pe", 24.0),
                        Map.of("ticker", "P3", "evRevenue", 9.0, "evEbitda", 22.0, "pe", 26.0)),
                "multiples", List.of("evRevenue", "evEbitda", "pe"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(in);
        assertThat(result.get("ok")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("multipleBreakdown");
        assertThat(rows).hasSize(3);

        Map<String, Object> evRev = rows.get(0);
        assertThat((Double) evRev.get("median")).isCloseTo(8.0, within(0.001));
        // 8.0 × 1000 = 8000 EV; - 200 net debt = 7800 equity; / 100 shares = 78
        assertThat((Double) evRev.get("impliedPerShareAtMedian")).isCloseTo(78.0, within(0.01));

        Map<String, Object> pe = rows.get(2);
        // P/E is equity-level, not enterprise. 24 × 150 = 3600 equity; / 100 = 36 per share
        assertThat((Double) pe.get("impliedPerShareAtMedian")).isCloseTo(36.0, within(0.01));
    }

    @Test
    void compsRequiresTargetAndPeers() {
        CompsAnalysisTool tool = new CompsAnalysisTool();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(Map.of());
        assertThat(result.get("ok")).isEqualTo(false);
    }

    // ---------- LboModelTool ----------

    @Test
    void lboProducesPositiveIrrUnderTypicalAssumptions() {
        LboModelTool lbo = new LboModelTool();
        Map<String, Object> in = Map.ofEntries(
                Map.entry("entryEbitda", 100.0),
                Map.entry("entryMultiple", 12.0),
                Map.entry("debtPctOfPurchase", 0.55),
                Map.entry("interestRate", 0.075),
                Map.entry("holdYears", 5),
                Map.entry("ebitdaGrowth", List.of(0.08, 0.10, 0.12, 0.10, 0.07)),
                Map.entry("exitMultiple", 10.5),
                Map.entry("capexPctOfEbitda", 0.20),
                Map.entry("deltaWcPctOfEbitda", 0.05),
                Map.entry("taxRate", 0.25));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) lbo.execute(in);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("purchasePrice")).isEqualTo(1200.0);
        // Entry equity = 540, exit should generate IRR > 0 with healthy growth + debt paydown
        assertThat((Double) result.get("irr")).isGreaterThan(0.0);
        assertThat((Double) result.get("moic")).isGreaterThan(1.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> yearly = (List<Map<String, Object>>) result.get("yearly");
        assertThat(yearly).hasSize(5);
        // Debt paydown should reduce ending debt year over year (with positive FCF)
        double y1Debt = (Double) yearly.get(0).get("endingDebt");
        double y5Debt = (Double) yearly.get(4).get("endingDebt");
        assertThat(y5Debt).isLessThan(y1Debt);
    }

    @Test
    void lboRejectsMismatchedGrowth() {
        LboModelTool lbo = new LboModelTool();
        Map<String, Object> in = Map.of(
                "entryEbitda", 100.0,
                "entryMultiple", 10.0,
                "debtPctOfPurchase", 0.5,
                "interestRate", 0.07,
                "holdYears", 5,
                "ebitdaGrowth", List.of(0.05, 0.05),  // wrong length
                "exitMultiple", 10.0);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) lbo.execute(in);
        assertThat(result.get("ok")).isEqualTo(false);
    }

    // ---------- TearSheetTool ----------

    @Test
    void tearSheetGeneratesXlsx(@TempDir Path tempDir) {
        TearSheetTool tearSheet = new TearSheetTool(new XlsxAuthorTool(tempDir));
        Map<String, Object> result = (Map<String, Object>) tearSheet.execute(Map.of(
                "path", "ACME_tear.xlsx",
                "company", Map.of(
                        "ticker", "ACME", "name", "ACME Corp",
                        "sector", "Industrials", "marketCap", 12000.0,
                        "ev", 13200.0, "lastPrice", 120.0),
                "financials", List.of(
                        Map.of("year", "FY23", "revenue", 1000, "ebitda", 280, "fcf", 180, "netIncome", 130),
                        Map.of("year", "FY24", "revenue", 1200, "ebitda", 350, "fcf", 220, "netIncome", 170)),
                "multiples", List.of(
                        Map.of("label", "EV/Revenue", "value", 11.0),
                        Map.of("label", "EV/EBITDA", "value", 37.7))));

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("sheetCount")).isEqualTo(3);
        assertThat(tempDir.resolve("ACME_tear.xlsx")).exists();
    }
}
