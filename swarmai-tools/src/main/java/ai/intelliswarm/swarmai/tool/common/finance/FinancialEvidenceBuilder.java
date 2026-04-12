package ai.intelliswarm.swarmai.tool.common.finance;

import ai.intelliswarm.swarmai.tool.common.FinancialDataTool;
import ai.intelliswarm.swarmai.tool.common.SECFilingsTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Top-level tool-evidence builder for financial-analysis multi-agent workflows.
 *
 * <p>Wraps four concerns behind a single API so example workflows stay short:
 * <ol>
 *   <li>Ticker validation (fail-fast with near-match suggestion on typos)</li>
 *   <li>Authoritative {@link FactCard} pre-extraction (Finnhub + SEC XBRL)</li>
 *   <li>Tool evidence assembly (Finnhub markdown + SEC filings + web search)</li>
 *   <li>Truncation that preserves high-signal structured sections</li>
 * </ol>
 *
 * <p>Typical usage in an example:
 * <pre>{@code
 * @Autowired FinancialEvidenceBuilder evidence;
 *
 * public void run(String ticker) {
 *     evidence.validateOrFail(ticker);             // throws IllegalArgument on unknown ticker
 *     String toolEvidence = evidence.build(ticker); // fact card + Finnhub + SEC + web
 *     IssuerProfile issuer = evidence.detectIssuer(toolEvidence); // 10-K vs 20-F
 *
 *     // ... define agents + tasks + swarm using toolEvidence in prompts ...
 *
 *     SwarmOutput result = swarm.kickoff(Map.of("ticker", ticker));
 *     String augmented = evidence.appendCanonicalMetrics(result.getFinalOutput(), ticker);
 * }
 * }</pre>
 *
 * <p>The example saves ~500 lines of inline helper code. All parsing/format logic
 * lives here and is tested independently.
 */
@Component
public class FinancialEvidenceBuilder {

    private static final Logger logger = LoggerFactory.getLogger(FinancialEvidenceBuilder.class);

    /** Max chars kept per source section after truncation. */
    private static final int MAX_PER_SOURCE = 15_000;

    /**
     * Markdown section headings considered "high-signal" — these are preserved verbatim
     * during truncation even when the overall evidence exceeds the budget. Ordered by
     * priority: fact card first, then Finnhub sections, then SEC sections.
     */
    private static final String[] HIGH_SIGNAL_HEADINGS = {
            "## 🎯 AUTHORITATIVE FACT CARD",
            "## Company Profile",
            "## Key Metrics & Ratios",
            "## Income Statement (annual)",
            "## Balance Sheet (most recent annual)",
            "## Cash Flow (most recent annual)",
            "## Revenue (recent quarters)",
            "## Insider Transactions (last 90 days)",
            "## Key Financials (XBRL)",
            "## MD&A Highlights",
            "## Insider Transaction Flow"
    };

    private final SECFilingsTool secFilingsTool;
    private final FinancialDataTool financialDataTool;
    private final WebSearchTool webSearchTool;
    private final TickerValidator tickerValidator;
    private final FactCardBuilder factCardBuilder;
    private final IssuerProfileDetector issuerDetector = new IssuerProfileDetector();

    public FinancialEvidenceBuilder(
            SECFilingsTool secFilingsTool,
            FinancialDataTool financialDataTool,
            WebSearchTool webSearchTool,
            @Value("${finnhub.api-key:${FINNHUB_API_KEY:}}") String finnhubApiKey) {
        this.secFilingsTool = secFilingsTool;
        this.financialDataTool = financialDataTool;
        this.webSearchTool = webSearchTool;
        this.tickerValidator = new TickerValidator(finnhubApiKey);
        this.factCardBuilder = new FactCardBuilder(finnhubApiKey);
    }

    // ---------- Public API ----------

    /** Validate ticker or throw with actionable message (suggests a correction if possible). */
    public TickerValidator.Result validateOrFail(String ticker) {
        return tickerValidator.validateOrFail(ticker);
    }

    /** Pre-extracts authoritative fact card. Useful for post-processing and appending to reports. */
    public FactCard buildFactCard(String ticker) {
        return factCardBuilder.build(ticker);
    }

    /** Detect whether a ticker is a domestic (10-K) or foreign (20-F) filer. */
    public IssuerProfile detectIssuer(String toolEvidence) {
        return issuerDetector.detect(toolEvidence);
    }

    /**
     * Builds the complete tool-evidence string the multi-agent workflow will inject into
     * every analyst prompt. Structure (in order):
     * <ol>
     *   <li>🎯 Authoritative Fact Card (highest priority — quote-verbatim)</li>
     *   <li>=== FINANCIAL DATA (FINNHUB) === — income statement / balance / cash flow / insiders</li>
     *   <li>=== SEC FILINGS DATA (EDGAR) === — 10-K / 10-Q / MD&A / XBRL concepts</li>
     *   <li>=== WEB SEARCH RESULTS === — recent news and analyst commentary</li>
     * </ol>
     * Each source is truncated to {@link #MAX_PER_SOURCE} chars, but the truncation is
     * high-signal-preserving — the fact card and Key Metrics sections are never cut.
     */
    public String build(String ticker) {
        return buildWithStats(ticker).evidence();
    }

    /**
     * Same as {@link #build(String)} but returns per-tool wall-clock timings alongside
     * the evidence. Call this from workflows that need to surface pre-fetch tool calls
     * in their metrics — otherwise {@code WorkflowMetricsCollector} reports 0 tool calls
     * because the Agent-path {@code ToolHook} never runs for pre-fetch tools.
     */
    public EvidenceResult buildWithStats(String ticker) {
        StringBuilder evidence = new StringBuilder();
        java.util.LinkedHashMap<String, Long> timings = new java.util.LinkedHashMap<>();

        // 0. 🎯 Authoritative fact card first — pre-extracted hard values the LLM MUST cite
        long t0 = System.currentTimeMillis();
        FactCard factCard = factCardBuilder.build(ticker);
        timings.put("fact_card", System.currentTimeMillis() - t0);
        logger.info("📋 FactCard for {}: {} rows", ticker, factCard.rows().size());
        evidence.append(factCard.toMarkdown());

        // 1. Finnhub financial data (primary source for income statement, margins, insiders)
        evidence.append("=== FINANCIAL DATA (FINNHUB) ===\n");
        evidence.append("Company: ").append(ticker).append("\n");
        evidence.append("Source: Finnhub financial API\n");
        evidence.append("Retrieved: ").append(LocalDateTime.now()).append("\n\n");
        long t1 = System.currentTimeMillis();
        evidence.append(truncate(callFinancialData(ticker)));
        timings.put("financial_data", System.currentTimeMillis() - t1);

        // 2. SEC filings (authoritative for MD&A text + XBRL facts cross-check)
        evidence.append("\n\n=== SEC FILINGS DATA (EDGAR) ===\n");
        evidence.append("Company: ").append(ticker).append("\n");
        evidence.append("Source: SEC EDGAR (public, no API key required)\n");
        evidence.append("Retrieved: ").append(LocalDateTime.now()).append("\n\n");
        long t2 = System.currentTimeMillis();
        evidence.append(truncate(callSecFilings(ticker)));
        timings.put("sec_filings", System.currentTimeMillis() - t2);

        // 3. Web search (news, analyst opinions, market sentiment)
        evidence.append("\n\n=== WEB SEARCH RESULTS ===\n");
        evidence.append("Query: \"").append(ticker).append(" stock analysis\"\n");
        evidence.append("Retrieved: ").append(LocalDateTime.now()).append("\n\n");
        long t3 = System.currentTimeMillis();
        evidence.append(truncate(callWebSearch(ticker)));
        timings.put("web_search", System.currentTimeMillis() - t3);

        evidence.append("\n\n=== END OF TOOL EVIDENCE ===");
        return new EvidenceResult(evidence.toString(), timings);
    }

    /**
     * Evidence string paired with per-tool wall-clock timings from the pre-fetch phase.
     * Iteration order is the order the tools were invoked.
     */
    public record EvidenceResult(String evidence, java.util.Map<String, Long> toolTimings) {}

    /**
     * Extracts only the high-signal structured sections from tool evidence — useful for
     * the verifier agent whose prompt would otherwise exceed its context window.
     */
    public String extractHighSignalSections(String toolEvidence) {
        if (toolEvidence == null) return "";
        StringBuilder kept = new StringBuilder();

        for (String marker : HIGH_SIGNAL_HEADINGS) {
            int start = toolEvidence.indexOf(marker);
            if (start < 0) continue;
            int next = toolEvidence.indexOf("\n## ", start + marker.length());
            int end = next > 0 ? next : Math.min(toolEvidence.length(), start + 8_000);
            kept.append(toolEvidence, start, end).append("\n\n");
        }

        if (kept.length() == 0) {
            int take = Math.min(toolEvidence.length(), 6_000);
            kept.append("[No structured sections found — compact raw evidence slice]\n")
                    .append(toolEvidence, 0, take);
        }
        return kept.toString();
    }

    /**
     * Appends a verbatim Canonical Metrics section (the full fact card) to an LLM-produced
     * report. Deterministic safeguard — ensures the reader sees authoritative numbers even
     * if the LLM wrote "DATA NOT AVAILABLE" in the narrative above.
     */
    public String appendCanonicalMetrics(String finalOutput, String ticker) {
        return finalOutput
                + "\n\n---\n\n"
                + "# ✅ Canonical Metrics (pre-extracted, authoritative)\n\n"
                + "_The following values are extracted directly from Finnhub's metrics API and " +
                "SEC's XBRL companyfacts API. They are authoritative regardless of any DATA NOT " +
                "AVAILABLE or rounded-number appearances in the narrative above. When the narrative " +
                "and this table disagree on a metric, THIS TABLE wins._\n\n"
                + buildFactCard(ticker).toMarkdown();
    }

    // ---------- Truncation ----------

    /**
     * Truncates a source section while preserving the high-signal headings. If the
     * structured sections fit within the budget, they stay intact and the filing-detail
     * bodies get chopped. If structured sections alone exceed the budget, we keep them
     * and drop everything else.
     */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_PER_SOURCE) return text;
        int boundary = findStructuredBoundary(text);
        if (boundary > 0 && boundary <= MAX_PER_SOURCE) {
            int remaining = MAX_PER_SOURCE - boundary - 100;
            if (remaining > 0) {
                String head = text.substring(0, boundary);
                String tail = text.substring(boundary);
                if (tail.length() > remaining) {
                    tail = tail.substring(0, remaining) +
                            "\n\n[... filing-detail bodies truncated, " +
                            (text.length() - (boundary + remaining)) + " chars omitted ...]";
                }
                logger.info("Truncating tool evidence from {} to {} chars (preserved structured sections, {} chars)",
                        text.length(), head.length() + tail.length(), boundary);
                return head + tail;
            }
        }
        logger.info("Truncating tool evidence from {} to {} chars", text.length(), MAX_PER_SOURCE);
        return text.substring(0, MAX_PER_SOURCE) +
                "\n\n[... truncated, " + text.length() + " total chars ...]";
    }

    private int findStructuredBoundary(String evidence) {
        int last = -1;
        for (String h : HIGH_SIGNAL_HEADINGS) {
            int idx = evidence.indexOf(h);
            if (idx > last) last = idx;
        }
        if (last < 0) return -1;
        int next = evidence.indexOf("\n## ", last + 1);
        return next > 0 ? next : -1;
    }

    // ---------- Tool invocations ----------

    private String callFinancialData(String ticker) {
        try {
            Object r = financialDataTool.execute(Map.of("input", ticker));
            return r != null ? r.toString() : "No financial data output.";
        } catch (Exception e) {
            return "Financial data error: " + e.getMessage();
        }
    }

    private String callSecFilings(String ticker) {
        try {
            Object r = secFilingsTool.execute(Map.of("input", ticker + ":recent filings summary"));
            return r != null ? r.toString() : "No SEC filings output.";
        } catch (Exception e) {
            return "SEC filings error: " + e.getMessage();
        }
    }

    private String callWebSearch(String ticker) {
        try {
            Object r = webSearchTool.execute(Map.of("query", ticker + " stock analysis"));
            return r != null ? r.toString() : "No web search output.";
        } catch (Exception e) {
            return "Web search error: " + e.getMessage();
        }
    }
}
