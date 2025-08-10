package ai.intelliswarm.swarmai.examples.stock.tools.sec;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates formatted reports from SEC filing data
 */
public class ReportGenerator {
    
    /**
     * Generate a comprehensive analysis report from filings
     */
    public String generateAnalysisReport(String ticker, String cik, String query, List<Filing> filings) {
        StringBuilder report = new StringBuilder();
        
        // Header
        report.append(String.format("# SEC Filings Analysis for %s\n", ticker));
        report.append(String.format("**CIK:** %s\n", cik));
        report.append(String.format("**Search Query:** %s\n\n", query));
        
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
        
        // Add content if available
        if (filing.hasExtractedText() && filing.isContentFetched()) {
            info.append("#### 📄 **Filing Content:**\n");
            info.append(filing.getExtractedText()).append("\n\n");
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