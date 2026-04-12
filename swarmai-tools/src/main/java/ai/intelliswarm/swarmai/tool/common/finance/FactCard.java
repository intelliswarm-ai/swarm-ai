package ai.intelliswarm.swarmai.tool.common.finance;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured "fact card" — a curated list of authoritative financial metrics for a
 * ticker, extracted from Finnhub metrics + SEC XBRL companyfacts. Designed to be
 * embedded verbatim in multi-agent prompts so analyst LLMs can't lazily emit
 * "DATA NOT AVAILABLE" for values that ARE in the evidence.
 *
 * <p>Two representations:
 * <ul>
 *   <li>{@link #rows()} — structured data for programmatic access / post-processing</li>
 *   <li>{@link #toMarkdown()} — human-readable table with strict "quote verbatim" header</li>
 * </ul>
 */
public class FactCard {

    /** A single metric row in the fact card. */
    public record Row(String label, String value, String citation) {
        public boolean hasValue() {
            return value != null && !value.isBlank() && !value.equals("— not reported —");
        }
    }

    private final String ticker;
    private final List<Row> rows = new ArrayList<>();

    public FactCard(String ticker) {
        this.ticker = ticker;
    }

    public String ticker() { return ticker; }

    public List<Row> rows() { return rows; }

    /** Adds a row. Pass {@code null} value to render "— not reported —". */
    public FactCard add(String label, String value, String citation) {
        rows.add(new Row(label, value != null ? value : "— not reported —", citation));
        return this;
    }

    /**
     * Renders the fact card as a markdown block with a strict quote-verbatim preamble.
     * Intended to be prepended to tool evidence in multi-agent workflows. The preamble
     * is prescriptive by design — analyst prompts reference it to override the LLM's
     * tendency to write "DATA NOT AVAILABLE" for values that ARE in the evidence.
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🎯 AUTHORITATIVE FACT CARD — QUOTE VERBATIM\n\n")
                .append("_The following values are pre-extracted from Finnhub's metrics API and ")
                .append("SEC's XBRL companyfacts API. They are AUTHORITATIVE. Every analyst and the ")
                .append("final synthesis MUST quote these values verbatim in the Financial Analysis ")
                .append("Summary. Writing 'DATA NOT AVAILABLE' for any metric listed below with a ")
                .append("value is a correctness failure — the verifier will flag it as FABRICATED-")
                .append("GAP. For rows marked '— not reported —', state the absence and move on._\n\n");
        sb.append("| Metric | Value | Citation |\n|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.label()).append(" | ").append(r.value())
                    .append(" | ").append(r.citation()).append(" |\n");
        }
        sb.append("\n_End of AUTHORITATIVE FACT CARD. Any metric above with a numeric value ")
                .append("MUST be quoted verbatim (with its citation tag) in the final report. Rows ")
                .append("showing '— not reported —' are genuine gaps — state them as DATA NOT ")
                .append("AVAILABLE only for those._\n\n");
        return sb.toString();
    }
}
