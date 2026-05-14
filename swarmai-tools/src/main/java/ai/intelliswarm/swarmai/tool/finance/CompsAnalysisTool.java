package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comparable-company analysis. Takes a peer set with their multiples plus the
 * target's metrics, returns the peer-implied valuation range (low / mean /
 * median / high) for each multiple.
 *
 * <p>Input shape:
 * <pre>{@code
 * {
 *   "target": {
 *     "ticker": "ACME",
 *     "revenue": 1000.0,        // $m
 *     "ebitda": 300.0,
 *     "netIncome": 150.0,
 *     "sharesOutstanding": 100.0,
 *     "netDebt": 200.0
 *   },
 *   "peers": [
 *     {"ticker": "PEER1", "evRevenue": 7.5, "evEbitda": 19.0, "pe": 22.0},
 *     {"ticker": "PEER2", "evRevenue": 8.2, "evEbitda": 22.1, "pe": 25.0},
 *     {"ticker": "PEER3", "evRevenue": 6.8, "evEbitda": 17.5, "pe": 19.5}
 *   ],
 *   "multiples": ["evRevenue", "evEbitda", "pe"]
 * }
 * }</pre>
 */
@Component
public class CompsAnalysisTool implements FinanceTool {

    @Override public String getFunctionName() { return "comps_analysis"; }

    @Override
    public String getDescription() {
        return "Comparable-company analysis. Takes a target company's financials plus a peer set "
                + "with their trading multiples, returns the peer-implied valuation range (low / "
                + "mean / median / high) and implied equity value per share for each multiple.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "finance"; }
    @Override public List<String> getTags() { return List.of("finance", "valuation", "comps", "relative-value"); }

    @Override public String getTriggerWhen() {
        return "User asks for relative valuation, comparable companies, peer multiples, comps analysis";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "target", Map.of("type", "object",
                                "description", "Target company financials"),
                        "peers", Map.of("type", "array",
                                "items", Map.of("type", "object",
                                        "description", "Peer with ticker + multiples")),
                        "multiples", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Which multiples to apply (evRevenue, evEbitda, pe)")),
                "required", List.of("target", "peers"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> parameters) {
        try {
            Map<String, Object> targetMap = (Map<String, Object>) requireMap(parameters, "target");
            List<Map<String, Object>> peers = (List<Map<String, Object>>) requireList(parameters, "peers");
            List<String> multiples = (List<String>) parameters.getOrDefault(
                    "multiples", List.of("evRevenue", "evEbitda", "pe"));

            double targetRevenue = toDouble(targetMap.getOrDefault("revenue", 0.0));
            double targetEbitda = toDouble(targetMap.getOrDefault("ebitda", 0.0));
            double targetNetIncome = toDouble(targetMap.getOrDefault("netIncome", 0.0));
            double netDebt = toDouble(targetMap.getOrDefault("netDebt", 0.0));
            double shares = toDouble(targetMap.getOrDefault("sharesOutstanding", 1.0));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (String mult : multiples) {
                double[] values = peers.stream()
                        .map(p -> p.get(mult))
                        .filter(o -> o instanceof Number)
                        .mapToDouble(o -> ((Number) o).doubleValue())
                        .toArray();
                if (values.length == 0) continue;
                Arrays.sort(values);

                double low = values[0];
                double high = values[values.length - 1];
                double mean = Arrays.stream(values).average().orElse(0);
                double median = values.length % 2 == 1
                        ? values[values.length / 2]
                        : (values[values.length / 2 - 1] + values[values.length / 2]) / 2.0;

                double driver;
                boolean isEnterpriseLevel;
                switch (mult) {
                    case "evRevenue" -> { driver = targetRevenue; isEnterpriseLevel = true; }
                    case "evEbitda" -> { driver = targetEbitda; isEnterpriseLevel = true; }
                    case "pe" -> { driver = targetNetIncome; isEnterpriseLevel = false; }
                    default -> { driver = 0.0; isEnterpriseLevel = false; }
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("multiple", mult);
                row.put("peerCount", values.length);
                row.put("low", round(low));
                row.put("mean", round(mean));
                row.put("median", round(median));
                row.put("high", round(high));
                row.put("driver", round(driver));
                // implied per-share equity value at the median multiple
                double impliedValuation = median * driver;
                double impliedEquity = isEnterpriseLevel ? impliedValuation - netDebt : impliedValuation;
                row.put("impliedEquityAtMedian", round(impliedEquity));
                row.put("impliedPerShareAtMedian", shares > 0 ? round(impliedEquity / shares) : 0.0);
                rows.add(row);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("target", targetMap.get("ticker"));
            out.put("peerCount", peers.size());
            out.put("multipleBreakdown", rows);
            return out;
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    private static Object requireMap(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Required object missing: " + key);
        }
        return v;
    }

    private static Object requireList(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (!(v instanceof List<?>)) {
            throw new IllegalArgumentException("Required array missing: " + key);
        }
        return v;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        return 0.0;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
