package ai.intelliswarm.swarmai.tool.common.eodhd;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Renders EODHD discovery endpoints (search, screener, calendars, economic events)
 * as citation-tagged markdown. Output shapes are list-oriented rather than
 * per-symbol drilldowns, so this lives separately from {@link EodhdReportFormatter}.
 */
public class EodhdDiscoveryFormatter {

    public String formatSearch(String query, JsonNode results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Symbol Search: \"").append(query).append("\"\n\n");
        if (results == null || !results.isArray() || results.isEmpty()) {
            sb.append("_No symbols matched._\n");
            return sb.toString();
        }
        sb.append("| Code | Name | Exchange | Country | Currency | Type | ISIN |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(results.size(), 25);
        for (int i = 0; i < max; i++) {
            JsonNode r = results.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "Code"), text(r, "Name"), text(r, "Exchange"),
                    text(r, "Country"), text(r, "Currency"),
                    text(r, "Type"), text(r, "ISIN")));
        }
        sb.append("\n_Citation: [EODHD: search, query=\"").append(query).append("\"]_\n");
        return sb.toString();
    }

    public String formatScreener(String filters, String sort, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Screener\n\n");
        if (filters != null && !filters.isBlank()) sb.append("- **Filters:** `").append(filters).append("`\n");
        if (sort != null && !sort.isBlank()) sb.append("- **Sort:** ").append(sort).append("\n");
        sb.append("\n");

        // EODHD returns either an array directly or { "data": [...] }
        JsonNode rows = (payload != null && payload.has("data")) ? payload.path("data") : payload;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            sb.append("_No matches for the supplied filters._\n");
            return sb.toString();
        }
        sb.append("| Code | Name | Exchange | Sector | Industry | Market Cap | Last Price |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(rows.size(), 30);
        for (int i = 0; i < max; i++) {
            JsonNode r = rows.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "code"), text(r, "name"), text(r, "exchange"),
                    text(r, "sector"), text(r, "industry"),
                    money(r, "market_capitalization"),
                    num(r, "last_day_data_price")));
        }
        sb.append("\n_Citation: [EODHD: screener, filters=").append(filters == null ? "default" : filters)
                .append("]_\n");
        return sb.toString();
    }

    public String formatEarnings(String fromIso, String toIso, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Earnings Calendar (").append(fromIso).append(" → ").append(toIso).append(")\n\n");
        JsonNode rows = (payload != null && payload.has("earnings")) ? payload.path("earnings")
                : (payload != null && payload.has("data")) ? payload.path("data") : payload;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            sb.append("_No scheduled earnings in this window._\n");
            return sb.toString();
        }
        sb.append("| Date | Code | Name | Currency | Estimate | Actual | Surprise % |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(rows.size(), 40);
        for (int i = 0; i < max; i++) {
            JsonNode r = rows.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "report_date"), text(r, "code"), text(r, "name"),
                    text(r, "currency"),
                    num(r, "estimate"), num(r, "actual"),
                    num(r, "percent")));
        }
        sb.append("\n_Citation: [EODHD: calendar/earnings, ")
                .append(fromIso).append(" → ").append(toIso).append("]_\n");
        return sb.toString();
    }

    public String formatIpos(String fromIso, String toIso, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD IPO Calendar (").append(fromIso).append(" → ").append(toIso).append(")\n\n");
        JsonNode rows = (payload != null && payload.has("ipos")) ? payload.path("ipos")
                : (payload != null && payload.has("data")) ? payload.path("data") : payload;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            sb.append("_No IPOs scheduled in this window._\n");
            return sb.toString();
        }
        sb.append("| Filing | Code | Name | Exchange | Currency | Price Range | Deal Type |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(rows.size(), 40);
        for (int i = 0; i < max; i++) {
            JsonNode r = rows.get(i);
            String range;
            String low = num(r, "price_from"), high = num(r, "price_to");
            if (!"—".equals(low) || !"—".equals(high)) range = low + " – " + high;
            else range = "—";
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "filing_date"), text(r, "code"), text(r, "name"),
                    text(r, "exchange"), text(r, "currency"), range,
                    text(r, "deal_type")));
        }
        sb.append("\n_Citation: [EODHD: calendar/ipos, ")
                .append(fromIso).append(" → ").append(toIso).append("]_\n");
        return sb.toString();
    }

    public String formatTrends(String symbols, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Analyst Trends for ").append(symbols).append("\n\n");
        JsonNode rows = (payload != null && payload.has("trends")) ? payload.path("trends")
                : (payload != null && payload.has("data")) ? payload.path("data") : payload;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            sb.append("_No trend data returned._\n");
            return sb.toString();
        }
        sb.append("| Date | Code | Strong Buy | Buy | Hold | Sell | Strong Sell |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(rows.size(), 24);
        for (int i = 0; i < max; i++) {
            JsonNode r = rows.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "date"), text(r, "code"),
                    num(r, "strong_buy"), num(r, "buy"),
                    num(r, "hold"),
                    num(r, "sell"), num(r, "strong_sell")));
        }
        sb.append("\n_Citation: [EODHD: calendar/trends, symbols=").append(symbols).append("]_\n");
        return sb.toString();
    }

    public String formatEconomicEvents(String country, String fromIso, String toIso, JsonNode payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Economic Events");
        if (country != null && !country.isBlank()) sb.append(" — ").append(country);
        sb.append(" (").append(fromIso).append(" → ").append(toIso).append(")\n\n");
        JsonNode rows = (payload != null && payload.has("data")) ? payload.path("data") : payload;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            sb.append("_No economic events in this window._\n");
            return sb.toString();
        }
        sb.append("| Date | Country | Type | Period | Estimate | Previous | Actual |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int max = Math.min(rows.size(), 40);
        for (int i = 0; i < max; i++) {
            JsonNode r = rows.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(r, "date"), text(r, "country"), text(r, "type"),
                    text(r, "period"),
                    num(r, "estimate"), num(r, "previous"), num(r, "actual")));
        }
        sb.append("\n_Citation: [EODHD: economic-events, ")
                .append(country == null ? "all" : country)
                .append(", ").append(fromIso).append(" → ").append(toIso).append("]_\n");
        return sb.toString();
    }

    // ---------- helpers ----------

    private String text(JsonNode node, String field) {
        if (node == null) return "—";
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return "—";
        String s = v.asText("");
        return s.isEmpty() ? "—" : s;
    }

    private String num(JsonNode node, String field) {
        if (node == null) return "—";
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return "—";
        if (v.isNumber()) {
            double d = v.asDouble();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.format(Locale.US, "%,d", (long) d);
            }
            return String.format(Locale.US, "%.4f", d);
        }
        String s = v.asText("");
        return s.isEmpty() ? "—" : s;
    }

    private String money(JsonNode node, String field) {
        if (node == null) return "—";
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) return "—";
        double d = v.asDouble();
        double abs = Math.abs(d);
        String sign = d < 0 ? "−" : "";
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%s$%.2fB", sign, abs / 1_000_000_000.0);
        if (abs >= 1_000_000) return String.format(Locale.US, "%s$%.2fM", sign, abs / 1_000_000.0);
        if (abs >= 1_000) return String.format(Locale.US, "%s$%.2fK", sign, abs / 1_000.0);
        return String.format(Locale.US, "%s$%.2f", sign, abs);
    }
}
