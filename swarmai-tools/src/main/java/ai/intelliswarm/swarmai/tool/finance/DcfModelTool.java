package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discounted cash-flow valuation. Deterministic math — no LLM call inside.
 * The agent's job is to feed in good assumptions; the tool's job is to
 * compute the implied enterprise value, equity value, and a small sensitivity
 * grid.
 *
 * <p>Input shape:
 * <pre>{@code
 * {
 *   "ticker": "ACME",
 *   "baseRevenue": 1000.0,        // most recent FY revenue (in $m)
 *   "baseEbitdaMargin": 0.30,     // base-year EBITDA margin
 *   "taxRate": 0.25,
 *   "capexPctOfRevenue": 0.05,
 *   "deltaWcPctOfRevenue": 0.02,
 *   "daPctOfRevenue": 0.04,
 *   "projectionYears": 5,
 *   "revenueGrowth": [0.15, 0.12, 0.10, 0.08, 0.06],   // per-year, length = projectionYears
 *   "marginExpansionPerYear": 0.005,                    // additive
 *   "wacc": 0.09,
 *   "terminalGrowth": 0.025,
 *   "netDebt": 200.0,
 *   "sharesOutstanding": 100.0
 * }
 * }</pre>
 *
 * <p>Output shape: structured per-year FCF, present values, terminal value,
 * enterprise value, equity value, per-share value, plus a 3×3 WACC/terminal-growth
 * sensitivity grid.
 *
 * <p>Companion to {@link ai.intelliswarm.swarmai.tool.office.XlsxAuthorTool}:
 * the structured result can be fed directly into an xlsx model spec.
 */
@Component
public class DcfModelTool implements FinanceTool {

    @Override public String getFunctionName() { return "dcf_model"; }

    @Override
    public String getDescription() {
        return "Run a discounted cash-flow valuation. Takes base-year financials + growth/margin "
                + "assumptions + WACC, returns year-by-year FCF, terminal value, enterprise value, "
                + "implied equity value per share, and a sensitivity grid. Use for intrinsic-value "
                + "analysis of mature operating businesses.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "finance"; }
    @Override public List<String> getTags() { return List.of("finance", "valuation", "dcf", "intrinsic-value"); }

    @Override public String getTriggerWhen() {
        return "User asks for intrinsic valuation, DCF, fair value, target price for a public company";
    }
    @Override public String getAvoidWhen() {
        return "Early-stage businesses without revenue, banks/insurers (use Dividend Discount Model), commodities";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("ticker", Map.of("type", "string")),
                        Map.entry("baseRevenue", Map.of("type", "number", "description", "FY0 revenue ($m)")),
                        Map.entry("baseEbitdaMargin", Map.of("type", "number")),
                        Map.entry("taxRate", Map.of("type", "number")),
                        Map.entry("capexPctOfRevenue", Map.of("type", "number")),
                        Map.entry("deltaWcPctOfRevenue", Map.of("type", "number")),
                        Map.entry("daPctOfRevenue", Map.of("type", "number")),
                        Map.entry("projectionYears", Map.of("type", "integer")),
                        Map.entry("revenueGrowth", Map.of("type", "array", "items", Map.of("type", "number"))),
                        Map.entry("marginExpansionPerYear", Map.of("type", "number")),
                        Map.entry("wacc", Map.of("type", "number")),
                        Map.entry("terminalGrowth", Map.of("type", "number")),
                        Map.entry("netDebt", Map.of("type", "number")),
                        Map.entry("sharesOutstanding", Map.of("type", "number"))),
                "required", List.of("baseRevenue", "wacc", "terminalGrowth", "projectionYears", "revenueGrowth"));
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        try {
            DcfInputs in = parseInputs(parameters);
            DcfResult result = compute(in);
            return toResult(result);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    DcfResult compute(DcfInputs in) {
        List<YearProjection> years = new ArrayList<>();
        double revenue = in.baseRevenue;
        double margin = in.baseEbitdaMargin;
        double sumPvFcf = 0.0;

        for (int y = 1; y <= in.projectionYears; y++) {
            double growth = in.revenueGrowth.get(y - 1);
            revenue = revenue * (1 + growth);
            margin = margin + in.marginExpansionPerYear;
            double ebitda = revenue * margin;
            double da = revenue * in.daPctOfRevenue;
            double ebit = ebitda - da;
            double nopat = ebit * (1 - in.taxRate);
            double capex = revenue * in.capexPctOfRevenue;
            double deltaWc = revenue * in.deltaWcPctOfRevenue;
            double fcf = nopat + da - capex - deltaWc;
            double discountFactor = Math.pow(1 + in.wacc, y);
            double pvFcf = fcf / discountFactor;
            sumPvFcf += pvFcf;
            years.add(new YearProjection(y, revenue, margin, ebitda, ebit, nopat, capex, deltaWc, fcf, pvFcf));
        }

        // Terminal value via Gordon growth on final year's FCF
        double finalFcf = years.get(years.size() - 1).fcf;
        double terminalValue = (finalFcf * (1 + in.terminalGrowth)) / (in.wacc - in.terminalGrowth);
        double pvTerminal = terminalValue / Math.pow(1 + in.wacc, in.projectionYears);

        double enterpriseValue = sumPvFcf + pvTerminal;
        double equityValue = enterpriseValue - in.netDebt;
        double perShare = in.sharesOutstanding > 0 ? equityValue / in.sharesOutstanding : 0.0;

        // 3x3 sensitivity grid: WACC ±100bps × terminal growth ±50bps
        double[] waccs = {in.wacc - 0.01, in.wacc, in.wacc + 0.01};
        double[] gs = {in.terminalGrowth - 0.005, in.terminalGrowth, in.terminalGrowth + 0.005};
        double[][] sensitivity = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (waccs[i] <= gs[j]) {
                    sensitivity[i][j] = Double.NaN;
                    continue;
                }
                double s = 0.0;
                for (YearProjection yp : years) {
                    s += yp.fcf / Math.pow(1 + waccs[i], yp.year);
                }
                double tv = (finalFcf * (1 + gs[j])) / (waccs[i] - gs[j]);
                s += tv / Math.pow(1 + waccs[i], in.projectionYears);
                double eq = s - in.netDebt;
                sensitivity[i][j] = in.sharesOutstanding > 0 ? eq / in.sharesOutstanding : eq;
            }
        }

        return new DcfResult(years, terminalValue, pvTerminal, enterpriseValue, equityValue, perShare,
                sumPvFcf, waccs, gs, sensitivity);
    }

    private static DcfInputs parseInputs(Map<String, Object> p) {
        int projectionYears = intOf(p.get("projectionYears"), 5);
        @SuppressWarnings("unchecked")
        List<Object> growthList = (List<Object>) p.get("revenueGrowth");
        if (growthList == null || growthList.size() != projectionYears) {
            throw new IllegalArgumentException(
                    "revenueGrowth array length must equal projectionYears");
        }
        List<Double> growth = new ArrayList<>();
        for (Object g : growthList) growth.add(toDouble(g));

        return new DcfInputs(
                strOf(p.get("ticker"), ""),
                toDouble(req(p, "baseRevenue")),
                toDouble(p.getOrDefault("baseEbitdaMargin", 0.30)),
                toDouble(p.getOrDefault("taxRate", 0.25)),
                toDouble(p.getOrDefault("capexPctOfRevenue", 0.05)),
                toDouble(p.getOrDefault("deltaWcPctOfRevenue", 0.02)),
                toDouble(p.getOrDefault("daPctOfRevenue", 0.04)),
                projectionYears,
                growth,
                toDouble(p.getOrDefault("marginExpansionPerYear", 0.0)),
                toDouble(req(p, "wacc")),
                toDouble(req(p, "terminalGrowth")),
                toDouble(p.getOrDefault("netDebt", 0.0)),
                toDouble(p.getOrDefault("sharesOutstanding", 1.0)));
    }

    private static Map<String, Object> toResult(DcfResult r) {
        List<Map<String, Object>> projections = new ArrayList<>();
        for (YearProjection y : r.years) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", y.year);
            row.put("revenue", round(y.revenue));
            row.put("ebitdaMargin", round(y.margin));
            row.put("ebitda", round(y.ebitda));
            row.put("ebit", round(y.ebit));
            row.put("nopat", round(y.nopat));
            row.put("capex", round(y.capex));
            row.put("deltaWc", round(y.deltaWc));
            row.put("fcf", round(y.fcf));
            row.put("pvFcf", round(y.pvFcf));
            projections.add(row);
        }

        List<Map<String, Object>> sensitivity = new ArrayList<>();
        for (int i = 0; i < r.waccs.length; i++) {
            for (int j = 0; j < r.gs.length; j++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("wacc", round(r.waccs[i]));
                row.put("terminalGrowth", round(r.gs[j]));
                row.put("impliedPerShare", Double.isNaN(r.sensitivity[i][j]) ? null
                        : round(r.sensitivity[i][j]));
                sensitivity.add(row);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projections", projections);
        out.put("sumPvFcf", round(r.sumPvFcf));
        out.put("terminalValue", round(r.terminalValue));
        out.put("pvTerminal", round(r.pvTerminal));
        out.put("enterpriseValue", round(r.enterpriseValue));
        out.put("equityValue", round(r.equityValue));
        out.put("perShareValue", round(r.perShareValue));
        out.put("sensitivity", sensitivity);
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) return Double.parseDouble(s);
        throw new IllegalArgumentException("Expected number, got " + o);
    }

    private static int intOf(Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return fallback;
    }

    private static String strOf(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }

    private static Object req(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return v;
    }

    record DcfInputs(String ticker, double baseRevenue, double baseEbitdaMargin, double taxRate,
                     double capexPctOfRevenue, double deltaWcPctOfRevenue, double daPctOfRevenue,
                     int projectionYears, List<Double> revenueGrowth, double marginExpansionPerYear,
                     double wacc, double terminalGrowth, double netDebt, double sharesOutstanding) {}

    record YearProjection(int year, double revenue, double margin, double ebitda, double ebit,
                          double nopat, double capex, double deltaWc, double fcf, double pvFcf) {}

    record DcfResult(List<YearProjection> years, double terminalValue, double pvTerminal,
                     double enterpriseValue, double equityValue, double perShareValue,
                     double sumPvFcf, double[] waccs, double[] gs, double[][] sensitivity) {}
}
