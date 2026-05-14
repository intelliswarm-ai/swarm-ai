package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leveraged-buyout (LBO) returns analysis. Deterministic — given entry price,
 * leverage, operating projections, and exit assumptions, computes year-by-year
 * cash flow / debt paydown and the resulting equity returns (IRR, MOIC).
 *
 * <p>Input shape:
 * <pre>{@code
 * {
 *   "entryEbitda": 100.0,           // $m
 *   "entryMultiple": 12.0,          // EV / EBITDA
 *   "debtPctOfPurchase": 0.55,
 *   "interestRate": 0.075,
 *   "holdYears": 5,
 *   "ebitdaGrowth": [0.08, 0.10, 0.12, 0.10, 0.07],
 *   "exitMultiple": 10.5,
 *   "capexPctOfEbitda": 0.20,
 *   "deltaWcPctOfEbitda": 0.05,
 *   "taxRate": 0.25,
 *   "minDebtFloor": 0.0
 * }
 * }</pre>
 *
 * <p>Output: year-by-year EBITDA, cash flow, debt balance, plus exit-year EV /
 * equity value / IRR / MOIC.
 */
@Component
public class LboModelTool implements FinanceTool {

    @Override public String getFunctionName() { return "lbo_model"; }

    @Override
    public String getDescription() {
        return "Leveraged-buyout returns analysis. Given entry price, leverage, operating "
                + "projections, and exit assumptions, returns IRR / MOIC and the underlying "
                + "year-by-year debt paydown schedule. Use for PE deal screening, sponsor analysis.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "finance"; }
    @Override public List<String> getTags() { return List.of("finance", "lbo", "private-equity", "irr"); }

    @Override public String getTriggerWhen() {
        return "User asks about LBO returns, sponsor IRR, MOIC, debt paydown, PE deal analysis";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("entryEbitda", Map.of("type", "number")),
                        Map.entry("entryMultiple", Map.of("type", "number")),
                        Map.entry("debtPctOfPurchase", Map.of("type", "number")),
                        Map.entry("interestRate", Map.of("type", "number")),
                        Map.entry("holdYears", Map.of("type", "integer")),
                        Map.entry("ebitdaGrowth", Map.of("type", "array", "items", Map.of("type", "number"))),
                        Map.entry("exitMultiple", Map.of("type", "number")),
                        Map.entry("capexPctOfEbitda", Map.of("type", "number")),
                        Map.entry("deltaWcPctOfEbitda", Map.of("type", "number")),
                        Map.entry("taxRate", Map.of("type", "number"))),
                "required", List.of("entryEbitda", "entryMultiple", "debtPctOfPurchase",
                        "interestRate", "holdYears", "ebitdaGrowth", "exitMultiple"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        try {
            double entryEbitda = toDouble(req(parameters, "entryEbitda"));
            double entryMult = toDouble(req(parameters, "entryMultiple"));
            double debtPct = toDouble(req(parameters, "debtPctOfPurchase"));
            double rate = toDouble(req(parameters, "interestRate"));
            int holdYears = intOf(req(parameters, "holdYears"));
            List<Object> growthList = (List<Object>) req(parameters, "ebitdaGrowth");
            if (growthList.size() != holdYears) {
                return Map.of("ok", false,
                        "error", "ebitdaGrowth length must equal holdYears (" + holdYears + ")");
            }
            double exitMult = toDouble(req(parameters, "exitMultiple"));
            double capexPct = toDouble(parameters.getOrDefault("capexPctOfEbitda", 0.20));
            double deltaWcPct = toDouble(parameters.getOrDefault("deltaWcPctOfEbitda", 0.05));
            double tax = toDouble(parameters.getOrDefault("taxRate", 0.25));
            double minDebtFloor = toDouble(parameters.getOrDefault("minDebtFloor", 0.0));

            double purchasePrice = entryEbitda * entryMult;
            double debt = purchasePrice * debtPct;
            double equity = purchasePrice - debt;

            List<Map<String, Object>> yearly = new ArrayList<>();
            double ebitda = entryEbitda;
            for (int y = 1; y <= holdYears; y++) {
                double growth = toDouble(growthList.get(y - 1));
                ebitda = ebitda * (1 + growth);
                double interest = debt * rate;
                double pretax = ebitda - interest;
                double taxes = pretax > 0 ? pretax * tax : 0.0;
                double capex = ebitda * capexPct;
                double dWc = ebitda * deltaWcPct;
                double fcf = ebitda - interest - taxes - capex - dWc;
                double debtPaydown = Math.min(Math.max(fcf, 0.0), Math.max(debt - minDebtFloor, 0.0));
                debt -= debtPaydown;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("year", y);
                row.put("ebitda", round(ebitda));
                row.put("interestExpense", round(interest));
                row.put("taxes", round(taxes));
                row.put("capex", round(capex));
                row.put("deltaWc", round(dWc));
                row.put("fcf", round(fcf));
                row.put("debtPaydown", round(debtPaydown));
                row.put("endingDebt", round(debt));
                yearly.add(row);
            }

            double exitEv = ebitda * exitMult;
            double exitEquity = exitEv - debt;
            double moic = equity > 0 ? exitEquity / equity : 0.0;
            double irr = moic > 0 && holdYears > 0 ? Math.pow(moic, 1.0 / holdYears) - 1.0 : 0.0;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("purchasePrice", round(purchasePrice));
            out.put("entryDebt", round(purchasePrice * debtPct));
            out.put("entryEquity", round(equity));
            out.put("yearly", yearly);
            out.put("exitEv", round(exitEv));
            out.put("exitDebt", round(debt));
            out.put("exitEquity", round(exitEquity));
            out.put("moic", round(moic));
            out.put("irr", round(irr));
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return 0.0;
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        return 0;
    }

    private static Object req(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v == null) throw new IllegalArgumentException("Required parameter missing: " + key);
        return v;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
