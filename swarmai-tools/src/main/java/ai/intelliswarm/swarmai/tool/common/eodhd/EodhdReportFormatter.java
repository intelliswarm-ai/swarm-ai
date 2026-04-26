package ai.intelliswarm.swarmai.tool.common.eodhd;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Renders EODHD JSON responses into citation-tagged markdown.
 *
 * <p>Each numeric figure carries a {@code [EODHD: <endpoint>, <date|period>]} tag so
 * downstream agents can quote and cite it directly. Long arrays (OHLCV, dividends, news)
 * are capped at sensible row counts to keep prompts within budget.
 */
public class EodhdReportFormatter {

    /** Build the full multi-section report depending on which payloads were fetched. */
    public String formatFullReport(String symbol,
                                    JsonNode realTime,
                                    JsonNode fundamentals,
                                    JsonNode eod,
                                    JsonNode dividends,
                                    JsonNode splits,
                                    JsonNode news) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Market Data for ").append(symbol).append("\n\n");
        sb.append("_Source: EODHD API (eodhd.com). Each figure is tagged with the endpoint and period it came from._\n\n");

        appendRealTime(sb, realTime);
        appendFundamentalsHighlights(sb, fundamentals);
        appendEod(sb, eod);
        appendDividends(sb, dividends);
        appendSplits(sb, splits);
        appendNews(sb, news);

        if (sb.length() < 200) {
            sb.append("_No data returned from EODHD for this symbol. Verify the suffix " +
                    "(e.g. AAPL.US, BMW.XETRA, VOD.LSE) and that the API key has access " +
                    "to the requested exchange._\n");
        }
        return sb.toString();
    }

    // ---------- real-time quote ----------

    private void appendRealTime(StringBuilder sb, JsonNode rt) {
        if (rt == null || rt.isMissingNode() || rt.isEmpty()) return;
        sb.append("## Latest Quote\n\n");
        appendKV(sb, "Symbol", text(rt, "code"));
        appendKV(sb, "Timestamp", text(rt, "timestamp"));
        appendKV(sb, "Open", num(rt, "open"));
        appendKV(sb, "High", num(rt, "high"));
        appendKV(sb, "Low", num(rt, "low"));
        appendKV(sb, "Close", num(rt, "close"));
        appendKV(sb, "Previous Close", num(rt, "previousClose"));
        appendKV(sb, "Change", num(rt, "change"));
        appendKV(sb, "Change %", num(rt, "change_p"));
        appendKV(sb, "Volume", num(rt, "volume"));
        sb.append("_Citation: [EODHD: real-time, latest]_\n\n");
    }

    // ---------- fundamentals highlights ----------

    private void appendFundamentalsHighlights(StringBuilder sb, JsonNode fundamentals) {
        if (fundamentals == null || fundamentals.isMissingNode() || fundamentals.isEmpty()) return;
        JsonNode general = fundamentals.path("General");
        JsonNode highlights = fundamentals.path("Highlights");
        JsonNode valuation = fundamentals.path("Valuation");
        JsonNode tech = fundamentals.path("Technicals");

        sb.append("## Company Profile & Highlights\n\n");
        if (!general.isMissingNode()) {
            appendKV(sb, "Name", text(general, "Name"));
            appendKV(sb, "Exchange", text(general, "Exchange"));
            appendKV(sb, "Currency", text(general, "CurrencyCode"));
            appendKV(sb, "Country", text(general, "CountryName"));
            appendKV(sb, "Sector", text(general, "Sector"));
            appendKV(sb, "Industry", text(general, "Industry"));
            appendKV(sb, "ISIN", text(general, "ISIN"));
            appendKV(sb, "IPO Date", text(general, "IPODate"));
        }
        if (!highlights.isMissingNode() && !highlights.isEmpty()) {
            sb.append("\n### Highlights\n\n");
            sb.append("| Metric | Value | Citation |\n|---|---|---|\n");
            row(sb, "Market Cap", money(highlights, "MarketCapitalization"), "Highlights.MarketCapitalization");
            row(sb, "EBITDA", money(highlights, "EBITDA"), "Highlights.EBITDA");
            row(sb, "P/E", num(highlights, "PERatio"), "Highlights.PERatio");
            row(sb, "PEG", num(highlights, "PEGRatio"), "Highlights.PEGRatio");
            row(sb, "EPS (TTM)", num(highlights, "EarningsShare"), "Highlights.EarningsShare");
            row(sb, "Book Value", num(highlights, "BookValue"), "Highlights.BookValue");
            row(sb, "Dividend Yield", pct(highlights, "DividendYield"), "Highlights.DividendYield");
            row(sb, "Profit Margin", pct(highlights, "ProfitMargin"), "Highlights.ProfitMargin");
            row(sb, "Operating Margin (TTM)", pct(highlights, "OperatingMarginTTM"), "Highlights.OperatingMarginTTM");
            row(sb, "ROA (TTM)", pct(highlights, "ReturnOnAssetsTTM"), "Highlights.ReturnOnAssetsTTM");
            row(sb, "ROE (TTM)", pct(highlights, "ReturnOnEquityTTM"), "Highlights.ReturnOnEquityTTM");
            row(sb, "Revenue (TTM)", money(highlights, "RevenueTTM"), "Highlights.RevenueTTM");
            row(sb, "Quarterly Revenue Growth YoY", pct(highlights, "QuarterlyRevenueGrowthYOY"),
                    "Highlights.QuarterlyRevenueGrowthYOY");
        }
        if (!valuation.isMissingNode() && !valuation.isEmpty()) {
            sb.append("\n### Valuation\n\n");
            sb.append("| Metric | Value | Citation |\n|---|---|---|\n");
            row(sb, "Trailing P/E", num(valuation, "TrailingPE"), "Valuation.TrailingPE");
            row(sb, "Forward P/E", num(valuation, "ForwardPE"), "Valuation.ForwardPE");
            row(sb, "Price/Sales (TTM)", num(valuation, "PriceSalesTTM"), "Valuation.PriceSalesTTM");
            row(sb, "Price/Book", num(valuation, "PriceBookMRQ"), "Valuation.PriceBookMRQ");
            row(sb, "Enterprise Value", money(valuation, "EnterpriseValue"), "Valuation.EnterpriseValue");
            row(sb, "EV/Revenue", num(valuation, "EnterpriseValueRevenue"), "Valuation.EnterpriseValueRevenue");
            row(sb, "EV/EBITDA", num(valuation, "EnterpriseValueEbitda"), "Valuation.EnterpriseValueEbitda");
        }
        if (!tech.isMissingNode() && !tech.isEmpty()) {
            sb.append("\n### Technicals\n\n");
            sb.append("| Metric | Value | Citation |\n|---|---|---|\n");
            row(sb, "Beta", num(tech, "Beta"), "Technicals.Beta");
            row(sb, "52-Week High", num(tech, "52WeekHigh"), "Technicals.52WeekHigh");
            row(sb, "52-Week Low", num(tech, "52WeekLow"), "Technicals.52WeekLow");
            row(sb, "50-Day MA", num(tech, "50DayMA"), "Technicals.50DayMA");
            row(sb, "200-Day MA", num(tech, "200DayMA"), "Technicals.200DayMA");
        }
        sb.append("\n");
    }

    // ---------- intraday OHLCV ----------

    public String formatIntraday(String symbol, String interval, JsonNode bars) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Intraday (").append(interval).append(") for ").append(symbol).append("\n\n");
        if (bars == null || !bars.isArray() || bars.isEmpty()) {
            sb.append("_No intraday bars returned. Check the symbol and interval (1m, 5m, 1h)._\n");
            return sb.toString();
        }
        sb.append("| Datetime | Open | High | Low | Close | Volume |\n|---|---|---|---|---|---|\n");
        int total = bars.size();
        int max = Math.min(total, 60);
        for (int i = total - 1; i >= total - max; i--) {
            JsonNode b = bars.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s |%n",
                    text(b, "datetime"),
                    num(b, "open"), num(b, "high"), num(b, "low"),
                    num(b, "close"), num(b, "volume")));
        }
        sb.append("\n_Citation: [EODHD: intraday/").append(interval)
                .append(", last ").append(max).append(" bars]_\n");
        return sb.toString();
    }

    // ---------- technical indicator ----------

    public String formatTechnical(String symbol, String function, Integer period, JsonNode series) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Technical: ").append(function.toUpperCase(Locale.US));
        if (period != null) sb.append(" (period ").append(period).append(")");
        sb.append(" for ").append(symbol).append("\n\n");
        if (series == null || !series.isArray() || series.isEmpty()) {
            sb.append("_No indicator series returned. Verify function name and that the ticker has enough history._\n");
            return sb.toString();
        }
        // Header is dynamic — first row dictates the columns (besides "date")
        JsonNode first = series.get(0);
        java.util.List<String> cols = new java.util.ArrayList<>();
        first.fieldNames().forEachRemaining(name -> {
            if (!"date".equals(name)) cols.add(name);
        });
        sb.append("| Date");
        for (String c : cols) sb.append(" | ").append(c);
        sb.append(" |\n|---");
        for (int i = 0; i < cols.size(); i++) sb.append("|---");
        sb.append("|\n");

        int total = series.size();
        int max = Math.min(total, 30);
        for (int i = total - 1; i >= total - max; i--) {
            JsonNode r = series.get(i);
            sb.append("| ").append(text(r, "date"));
            for (String c : cols) sb.append(" | ").append(num(r, c));
            sb.append(" |\n");
        }
        sb.append("\n_Citation: [EODHD: technical/").append(function)
                .append(", last ").append(max).append(" points]_\n");
        return sb.toString();
    }

    // ---------- macro indicator ----------

    public String formatMacro(String country, String indicator, JsonNode series) {
        StringBuilder sb = new StringBuilder();
        sb.append("# EODHD Macro Indicator: ").append(indicator)
                .append(" for ").append(country).append("\n\n");
        if (series == null || !series.isArray() || series.isEmpty()) {
            sb.append("_No macro series returned. Check the country (ISO-3, e.g. USA, GBR, DEU) and the indicator key._\n");
            return sb.toString();
        }
        sb.append("| Date | Value |\n|---|---|\n");
        int total = series.size();
        int max = Math.min(total, 20);
        for (int i = total - 1; i >= total - max; i--) {
            JsonNode p = series.get(i);
            sb.append(String.format(Locale.US, "| %s | %s |%n",
                    text(p, "Date"), num(p, "Value")));
        }
        sb.append("\n_Citation: [EODHD: macro-indicator/").append(country)
                .append("/").append(indicator).append(", last ").append(max).append(" observations]_\n");
        return sb.toString();
    }

    // ---------- end-of-day OHLCV ----------

    private void appendEod(StringBuilder sb, JsonNode eod) {
        if (eod == null || !eod.isArray() || eod.isEmpty()) return;
        sb.append("## End-of-Day OHLCV (most recent ").append(Math.min(eod.size(), 30)).append(" sessions)\n\n");
        sb.append("| Date | Open | High | Low | Close | Adj Close | Volume |\n|---|---|---|---|---|---|---|\n");
        // EODHD returns oldest-first; iterate from the tail to print newest first
        int total = eod.size();
        int start = Math.max(0, total - 30);
        for (int i = total - 1; i >= start; i--) {
            JsonNode d = eod.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s | %s |%n",
                    text(d, "date"),
                    num(d, "open"), num(d, "high"), num(d, "low"),
                    num(d, "close"), num(d, "adjusted_close"),
                    num(d, "volume")));
        }
        sb.append("\n_Citation: [EODHD: eod, ").append(text(eod.get(start), "date")).append(" → ")
                .append(text(eod.get(total - 1), "date")).append("]_\n\n");
    }

    // ---------- dividends ----------

    private void appendDividends(StringBuilder sb, JsonNode divs) {
        if (divs == null || !divs.isArray() || divs.isEmpty()) return;
        sb.append("## Dividends (most recent 12)\n\n");
        sb.append("| Date | Value | Currency | Declaration | Record | Payment |\n|---|---|---|---|---|---|\n");
        int total = divs.size();
        int start = Math.max(0, total - 12);
        for (int i = total - 1; i >= start; i--) {
            JsonNode d = divs.get(i);
            sb.append(String.format(Locale.US, "| %s | %s | %s | %s | %s | %s |%n",
                    text(d, "date"),
                    num(d, "value"),
                    text(d, "currency"),
                    text(d, "declarationDate"),
                    text(d, "recordDate"),
                    text(d, "paymentDate")));
        }
        sb.append("\n_Citation: [EODHD: div, last 12 distributions]_\n\n");
    }

    // ---------- splits ----------

    private void appendSplits(StringBuilder sb, JsonNode splits) {
        if (splits == null || !splits.isArray() || splits.isEmpty()) return;
        sb.append("## Stock Splits\n\n");
        sb.append("| Date | Split |\n|---|---|\n");
        int total = splits.size();
        int start = Math.max(0, total - 10);
        for (int i = total - 1; i >= start; i--) {
            JsonNode s = splits.get(i);
            sb.append(String.format(Locale.US, "| %s | %s |%n",
                    text(s, "date"), text(s, "split")));
        }
        sb.append("\n_Citation: [EODHD: splits]_\n\n");
    }

    // ---------- news ----------

    private void appendNews(StringBuilder sb, JsonNode news) {
        if (news == null || !news.isArray() || news.isEmpty()) return;
        sb.append("## Recent News\n\n");
        int max = Math.min(news.size(), 10);
        for (int i = 0; i < max; i++) {
            JsonNode n = news.get(i);
            String title = text(n, "title");
            String date = text(n, "date");
            String link = text(n, "link");
            String sentiment = "";
            JsonNode sentNode = n.path("sentiment");
            if (sentNode.isObject()) {
                sentiment = String.format(Locale.US, "  _(sentiment: pol=%s, neg=%s, neu=%s, pos=%s)_",
                        num(sentNode, "polarity"),
                        num(sentNode, "neg"), num(sentNode, "neu"), num(sentNode, "pos"));
            }
            sb.append("- **").append(date).append("** — [").append(title).append("](")
                    .append(link).append(")").append(sentiment)
                    .append("  [EODHD: news, ").append(date).append("]\n");
        }
        sb.append("\n");
    }

    // ---------- helpers ----------

    private void appendKV(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return;
        sb.append("- **").append(key).append(":** ").append(value).append("\n");
    }

    private void row(StringBuilder sb, String label, String value, String citation) {
        if (value == null || value.isEmpty() || "—".equals(value)) return;
        sb.append("| ").append(label).append(" | ").append(value)
                .append(" | [EODHD: ").append(citation).append("] |\n");
    }

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

    private String pct(JsonNode node, String field) {
        if (node == null) return "—";
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || !v.isNumber()) return "—";
        return String.format(Locale.US, "%.2f%%", v.asDouble() * 100);
    }
}
