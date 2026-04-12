package ai.intelliswarm.swarmai.tool.common.sec;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates formatted reports from SEC filing data
 */
public class ReportGenerator {

    private final FinancialFactsFormatter factsFormatter = new FinancialFactsFormatter();
    private final MDAExtractor mdaExtractor = new MDAExtractor();
    private final InsiderTransactionAggregator insiderAggregator = new InsiderTransactionAggregator();
    private static final int MDA_MAX_BULLETS = 16;

    /**
     * Legacy signature — kept for callers that don't have a {@link CompanyFacts} object yet.
     * New callers should prefer the overload that accepts facts so agents see structured
     * financial data at the top of the report.
     */
    public String generateAnalysisReport(String ticker, String cik, String query, List<Filing> filings) {
        return generateAnalysisReport(ticker, cik, query, filings, null);
    }

    /**
     * Generate a comprehensive analysis report from filings, with an optional
     * "Key Financials (XBRL)" section derived from SEC's companyfacts API.
     *
     * <p>The facts section is placed immediately after the header so agents see
     * structured revenue/margin/EPS numbers before wading through filing content.
     * When {@code facts} is null or has no usable concepts, the section is omitted
     * and behavior is identical to the legacy signature.
     */
    public String generateAnalysisReport(String ticker, String cik, String query,
                                         List<Filing> filings, CompanyFacts facts) {
        StringBuilder report = new StringBuilder();

        // Header
        report.append(String.format("# SEC Filings Analysis for %s\n", ticker));
        report.append(String.format("**CIK:** %s\n", cik));
        if (facts != null && facts.getEntityName() != null) {
            report.append(String.format("**Entity:** %s\n", facts.getEntityName()));
        }
        report.append(String.format("**Search Query:** %s\n\n", query));

        // Key Financials section (XBRL companyfacts — highest-signal content, goes first)
        if (facts != null) {
            String factsSection = factsFormatter.format(facts);
            if (!factsSection.isEmpty()) {
                report.append(factsSection);
            }
        }

        // MD&A Highlights — themed, citation-carrying bullets (risks / opportunities /
        // liquidity / guidance). Second-priority placement so agents see MD&A-level
        // narrative before getting into per-filing detail.
        List<MDAExtractor.Bullet> mdaBullets = mdaExtractor.extract(filings, MDA_MAX_BULLETS);
        String mdaSection = mdaExtractor.render(mdaBullets);
        org.slf4j.LoggerFactory.getLogger(ReportGenerator.class).info(
                "MD&A extractor: {} bullets across {} filings ({} chars rendered)",
                mdaBullets.size(), filings.size(), mdaSection.length());
        if (!mdaSection.isEmpty()) {
            report.append(mdaSection);
        }

        // Insider Transaction Flow — aggregated from Form 4 filings. Gives agents a
        // clean table + net $ flow instead of having to scan multiple Form 4 bodies.
        List<InsiderTransactionAggregator.Transaction> insiderTx = insiderAggregator.extract(filings);
        String insiderSection = insiderAggregator.render(insiderTx);
        org.slf4j.LoggerFactory.getLogger(ReportGenerator.class).info(
                "Insider aggregator: {} transactions extracted ({} chars rendered)",
                insiderTx.size(), insiderSection.length());
        if (!insiderSection.isEmpty()) {
            report.append(insiderSection);
        }

        // Group filings by type
        Map<String, List<Filing>> filingsByType = filings.stream()
            .filter(f -> FilingTypeHandler.isImportantFilingType(f.getFormType()))
            .collect(Collectors.groupingBy(Filing::getFormType));

        // Sort filing types by priority
        List<String> priorityOrder = FilingTypeHandler.getPriorityOrder();

        // Add filings by priority order
        for (String formType : priorityOrder) {
            List<Filing> filingsOfType = filingsByType.get(formType);
            if (filingsOfType != null && !filingsOfType.isEmpty()) {
                int limit = FilingTypeHandler.getFilingLimit(formType);
                List<Filing> limitedFilings = filingsOfType.stream().limit(limit).toList();

                report.append(String.format("## %s\n\n", FilingTypeHandler.getFilingTypeDescription(formType)));
                for (Filing filing : limitedFilings) {
                    report.append(formatFilingInfo(filing));
                }
            }
        }

        // Handle any other important filing types not in priority order
        for (Map.Entry<String, List<Filing>> entry : filingsByType.entrySet()) {
            String formType = entry.getKey();
            if (!priorityOrder.contains(formType)) {
                List<Filing> filingsOfType = entry.getValue();
                int limit = FilingTypeHandler.getFilingLimit(formType);
                List<Filing> limitedFilings = filingsOfType.stream().limit(limit).toList();

                report.append(String.format("## %s\n\n", FilingTypeHandler.getFilingTypeDescription(formType)));
                for (Filing filing : limitedFilings) {
                    report.append(formatFilingInfo(filing));
                }
            }
        }

        // Add analysis guidance
        report.append(String.format("\n## Analysis Guidance for Query: '%s'\n\n", query));
        report.append(generateAnalysisGuidance(query, ticker));

        // Add additional resources
        report.append("\n## Additional Resources\n\n");
        report.append(String.format("- [View all filings on SEC EDGAR](https://www.sec.gov/edgar/browse/?CIK=%s)\n", cik));
        report.append(String.format("- [Company profile on SEC](https://data.sec.gov/submissions/CIK%010d.json)\n", Integer.parseInt(cik)));

        return report.toString();
    }

    /**
     * Format a single filing for display
     */
    private String formatFilingInfo(Filing filing) {
        StringBuilder info = new StringBuilder();

        info.append(String.format("### %s - Filed: %s\n", filing.getFormType(), filing.getFilingDate()));
        info.append(String.format("- **Accession Number:** %s\n", filing.getAccessionNumber()));
        info.append(String.format("- **Document:** [%s](%s)\n\n", filing.getPrimaryDocument(), filing.getUrl()));

        // Add content if available — use higher limits for key filing types
        if (filing.hasExtractedText() && filing.isContentFetched()) {
            info.append("#### Filing Content:\n");
            String text = filing.getExtractedText();
            int maxChars = getContentLimitForType(filing.getFormType());
            if (text.length() > maxChars) {
                info.append(text, 0, maxChars).append("\n[... content truncated at ")
                    .append(maxChars).append(" chars ...]\n\n");
            } else {
                info.append(text).append("\n\n");
            }
        } else if (filing.hasError()) {
            info.append("#### ❌ **Content Error:**\n");
            info.append(filing.getContentError()).append("\n\n");
        } else {
            info.append("#### 📋 **Filing Summary:**\n");
            info.append(String.format("This %s filing is available for review at the SEC EDGAR link above.\n\n",
                filing.getFormType()));
        }

        return info.toString();
    }

    /**
     * Returns content character limit based on filing type importance.
     * Annual/quarterly reports get more space since they contain key financial data.
     */
    private int getContentLimitForType(String formType) {
        return switch (formType.toUpperCase()) {
            case "10-K", "20-F" -> 6000;   // Annual reports — most important
            case "10-Q", "6-K" -> 5000;    // Quarterly reports
            case "8-K" -> 3000;            // Current reports
            default -> 2000;               // Other filings
        };
    }

    /**
     * Generate analysis guidance based on query
     */
    private String generateAnalysisGuidance(String query, String ticker) {
        StringBuilder guidance = new StringBuilder();

        // Generic guidance
        guidance.append("**General Analysis Tips:**\n");
        guidance.append(String.format("- Compare %s's filings with industry peers for benchmarking\n", ticker));
        guidance.append("- Track changes in language and emphasis across filing periods\n");
        guidance.append("- Pay attention to auditor opinions and internal control assessments\n");
        guidance.append("- Review footnotes for critical accounting policies and estimates\n\n");

        // Query-specific guidance
        if (query.toLowerCase().contains("revenue") || query.toLowerCase().contains("earnings")) {
            guidance.append("**Revenue/Earnings Analysis:**\n");
            guidance.append("- Focus on revenue recognition policies and segment reporting\n");
            guidance.append("- Analyze revenue trends and seasonal patterns\n");
            guidance.append("- Review management discussion of revenue drivers\n\n");
        }

        if (query.toLowerCase().contains("risk")) {
            guidance.append("**Risk Analysis:**\n");
            guidance.append("- Review 'Risk Factors' section in 10-K/20-F annual reports\n");
            guidance.append("- Look for changes in risk disclosures between periods\n");
            guidance.append("- Pay attention to forward-looking statements and uncertainties\n\n");
        }

        if (query.toLowerCase().contains("debt") || query.toLowerCase().contains("liquidity")) {
            guidance.append("**Financial Health Analysis:**\n");
            guidance.append("- Review balance sheet for debt levels and maturity schedules\n");
            guidance.append("- Analyze cash flow from operations and free cash flow\n");
            guidance.append("- Check debt covenants and credit facility terms\n\n");
        }

        return guidance.toString();
    }

    /**
     * Generate a simple error report
     */
    public String generateErrorReport(String message) {
        return "Error: " + message;
    }

    /**
     * Generate a summary report (shorter version)
     */
    public String generateSummaryReport(String ticker, String cik, List<Filing> filings) {
        StringBuilder summary = new StringBuilder();

        summary.append(String.format("# SEC Filings Summary for %s\n", ticker));
        summary.append(String.format("**CIK:** %s\n", cik));
        summary.append(String.format("**Total Filings Found:** %d\n\n", filings.size()));

        // Group by form type and count
        Map<String, Long> counts = filings.stream()
            .collect(Collectors.groupingBy(Filing::getFormType, Collectors.counting()));

        summary.append("**Filing Types:**\n");
        counts.entrySet().stream()
            .sorted((a, b) -> FilingTypeHandler.getPriorityScore(a.getKey()) - FilingTypeHandler.getPriorityScore(b.getKey()))
            .forEach(entry -> summary.append(String.format("- %s: %d filings\n",
                FilingTypeHandler.getFilingTypeDescription(entry.getKey()), entry.getValue())));

        return summary.toString();
    }
}
