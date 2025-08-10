package ai.intelliswarm.swarmai.examples.stock.tools;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.examples.stock.tools.sec.SECApiClient;
import ai.intelliswarm.swarmai.examples.stock.tools.sec.Filing;
import ai.intelliswarm.swarmai.examples.stock.tools.sec.FilingContentProcessor;
import ai.intelliswarm.swarmai.examples.stock.tools.sec.ReportGenerator;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clean, modular SEC Filings Analysis Tool
 * 
 * This tool analyzes SEC filings for public companies by:
 * 1. Looking up company CIK from ticker symbol
 * 2. Fetching recent filings from SEC API
 * 3. Processing and extracting content from important filings
 * 4. Generating comprehensive analysis reports
 * 
 * Supports both domestic and foreign issuers with various form types:
 * - Domestic: 10-K, 10-Q, 8-K, DEF 14A
 * - Foreign: 20-F, 6-K
 * - Ownership: SCHEDULE 13G/13D, SC 13G/13D
 * - Registration: S-1, F-1, 424B series
 */
@Component
public class SECFilingsTool implements BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(SECFilingsTool.class);
    
    private final SECApiClient apiClient;
    private final FilingContentProcessor contentProcessor;
    private final ReportGenerator reportGenerator;
    
    public SECFilingsTool() {
        this.apiClient = new SECApiClient();
        this.contentProcessor = new FilingContentProcessor(apiClient);
        this.reportGenerator = new ReportGenerator();
    }
    
    @Override
    public String getFunctionName() {
        return "sec_filings";
    }
    
    @Override
    public String getDescription() {
        return "Analyzes SEC filings for public companies. Supports both domestic (10-K, 10-Q, 8-K) and foreign issuer forms (20-F, 6-K). " +
               "Input format: 'TICKER:QUERY' where TICKER is the stock symbol and QUERY describes what to analyze " +
               "(e.g., 'AAPL:revenue trends' or 'IMPP:financial statements').";
    }
    
    @Override
    public Object execute(Map<String, Object> parameters) {
        String input = (String) parameters.get("input");
        logger.info("📋 SECFilingsTool: Analyzing filings for: {}", input);
        
        try {
            // 1. Validate and parse input
            ParsedInput parsedInput = parseAndValidateInput(input);
            if (parsedInput == null) {
                return reportGenerator.generateErrorReport("Input must be in format 'TICKER:QUERY' (e.g., 'AAPL:revenue trends')");
            }
            
            // 2. Get CIK for ticker
            String cik = apiClient.getCIKFromTicker(parsedInput.ticker());
            if (cik == null) {
                return reportGenerator.generateErrorReport(
                    String.format("Could not find CIK for ticker %s. Please verify the ticker symbol.", parsedInput.ticker()));
            }
            
            // 3. Fetch recent filings
            List<Filing> recentFilings = apiClient.getRecentFilings(cik);
            logger.info("Found {} recent filings from SEC submissions API", recentFilings.size());
            
            if (recentFilings.isEmpty()) {
                return reportGenerator.generateErrorReport(
                    String.format("No recent filings found for %s. The company may not file with the SEC.", parsedInput.ticker()));
            }
            
            // 4. Process filing content (for important filings only)
            logger.info("Fetching content for recent filings...");
            contentProcessor.fetchFilingContents(recentFilings, 20); // Fetch content for up to 20 important filings
            
            // 5. Generate comprehensive report
            String report = reportGenerator.generateAnalysisReport(parsedInput.ticker(), cik, parsedInput.query(), recentFilings);
            
            logger.info("Successfully generated SEC filings analysis for {} with {} characters", 
                parsedInput.ticker(), report.length());
            
            return report;
            
        } catch (Exception e) {
            logger.error("Error analyzing SEC filings for input: {}", input, e);
            return reportGenerator.generateErrorReport("Unexpected error: " + e.getMessage());
        }
    }
    
    /**
     * Parse and validate input format
     */
    private ParsedInput parseAndValidateInput(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }
        
        String[] parts = input.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        
        String ticker = parts[0].trim().toUpperCase();
        String query = parts[1].trim();
        
        if (ticker.isEmpty() || query.isEmpty()) {
            return null;
        }
        
        return new ParsedInput(ticker, query);
    }
    
    /**
     * Clean shutdown of resources
     */
    public void shutdown() {
        if (contentProcessor != null) {
            contentProcessor.shutdown();
        }
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("input", Map.of(
            "type", "string", 
            "description", "Ticker and query in format 'TICKER:QUERY' (e.g., 'AAPL:revenue trends', 'MSFT:risk factors', 'IMPP:financial statements')"
        ));
        
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", List.of("input")
        );
    }
    
    @Override
    public boolean isAsync() {
        return false;
    }
    
    // Helper record for parsed input
    private record ParsedInput(String ticker, String query) {}
    
    // For backwards compatibility with existing request format
    public record Request(String input) {}
}