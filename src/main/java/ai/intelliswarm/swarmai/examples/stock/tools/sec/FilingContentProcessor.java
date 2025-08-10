package ai.intelliswarm.swarmai.examples.stock.tools.sec;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes SEC filing content - extracts and cleans text from HTML/XBRL documents
 */
public class FilingContentProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(FilingContentProcessor.class);
    private final ExecutorService executorService;
    private final SECApiClient apiClient;
    
    public FilingContentProcessor(SECApiClient apiClient) {
        this.apiClient = apiClient;
        this.executorService = Executors.newFixedThreadPool(8); // Increased for more concurrent requests
    }
    
    /**
     * Fetches and processes content for a list of filings
     */
    public void fetchFilingContents(List<Filing> filings) {
        fetchFilingContents(filings, 15); // Default to 15 filings
    }
    
    /**
     * Fetches and processes content for a list of filings with configurable limit
     */
    public void fetchFilingContents(List<Filing> filings, int maxFilings) {
        // Filter to only important filings and limit the number
        List<Filing> importantFilings = filings.stream()
                .filter(f -> FilingTypeHandler.isImportantFilingType(f.getFormType()))
                .limit(maxFilings)
                .collect(Collectors.toList());
        
        // Log all form types to understand what we're getting
        logger.info("All filing types found: {}", filings.stream().map(Filing::getFormType).distinct().collect(Collectors.toList()));
        
        logger.info("Processing content for {} important filings from {} total", importantFilings.size(), filings.size());
        
        // Fetch content in parallel
        List<CompletableFuture<Void>> contentFutures = importantFilings.stream()
                .map(filing -> CompletableFuture.runAsync(() -> fetchSingleFilingContent(filing), executorService))
                .collect(Collectors.toList());
        
        // Wait for all content to be fetched
        try {
            CompletableFuture.allOf(contentFutures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            logger.warn("Error waiting for filing content to be fetched: {}", e.getMessage());
        }
    }
    
    /**
     * Fetches content for a single filing
     */
    private void fetchSingleFilingContent(Filing filing) {
        try {
            logger.debug("Fetching content for filing: {} - {}", filing.getFormType(), filing.getAccessionNumber());
            
            String content = apiClient.fetchFilingContent(filing.getUrl());
            
            if (content != null) {
                filing.setContent(content);
                filing.setExtractedText(extractKeyContent(content, filing.getFormType()));
                filing.setContentFetched(true);
                logger.debug("Successfully fetched content for {} (length: {} chars)", 
                    filing.getAccessionNumber(), content.length());
            } else {
                filing.setContentError("Failed to fetch content from URL");
                logger.warn("Failed to fetch content for {}", filing.getAccessionNumber());
            }
        } catch (Exception e) {
            filing.setContentError("Error fetching content: " + e.getMessage());
            logger.warn("Error fetching content for {}: {}", filing.getAccessionNumber(), e.getMessage());
        }
    }
    
    /**
     * Extracts key content from SEC filing HTML
     */
    private String extractKeyContent(String htmlContent, String formType) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            return "No content available";
        }
        
        try {
            // First, always try to extract readable HTML content
            String cleanedText = cleanHTML(htmlContent);
            String extractedContent = extractRelevantSections(cleanedText, formType);
            
            // If we got meaningful content from HTML extraction, use it
            if (extractedContent != null && extractedContent.trim().length() > 100) {
                return extractedContent;
            }
            
            // If HTML extraction didn't work well, check if it's XBRL
            if (isXBRLDocument(htmlContent)) {
                logger.debug("Document appears to be XBRL format, extracting structured data");
                return extractXBRLContent(htmlContent, formType);
            }
            
            // Fallback to more aggressive content extraction
            return extractFallbackContent(htmlContent, formType);
            
        } catch (Exception e) {
            logger.warn("Error extracting content: {}", e.getMessage());
            return "Error extracting content: " + e.getMessage();
        }
    }
    
    /**
     * Clean HTML content using Jsoup and extract readable text
     */
    private String cleanHTML(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            
            // Remove script and style elements
            doc.select("script, style, noscript").remove();
            
            // Get text content
            String text = doc.text();
            
            // Clean up whitespace
            return text.replaceAll("\\s+", " ").trim();
            
        } catch (Exception e) {
            logger.warn("Error cleaning HTML with Jsoup: {}", e.getMessage());
            return fallbackCleanHTML(htmlContent);
        }
    }
    
    /**
     * Fallback HTML cleaning method
     */
    private String fallbackCleanHTML(String htmlContent) {
        return htmlContent
            .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replaceAll("&[a-zA-Z0-9#]+;", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    /**
     * Check if document is XBRL format
     */
    private boolean isXBRLDocument(String content) {
        return content != null && (
            content.contains("xbrl") || 
            content.contains("xmlns") && content.contains("xbrli") ||
            content.contains("<xbrl:") ||
            content.contains("us-gaap:")
        );
    }
    
    /**
     * Extract content from XBRL documents
     */
    private String extractXBRLContent(String content, String formType) {
        StringBuilder result = new StringBuilder();
        result.append("**Full Filing Content:**\n\n");
        
        // Return full content for XBRL documents
        result.append(content);
        
        return result.toString();
    }
    
    /**
     * Extract fallback content when other methods fail
     */
    private String extractFallbackContent(String htmlContent, String formType) {
        StringBuilder result = new StringBuilder();
        result.append("**Full Filing Content:**\n\n");
        
        // Return full content as fallback
        result.append(htmlContent);
        
        return result.toString();
    }
    
    /**
     * Extract relevant sections based on form type
     */
    private String extractRelevantSections(String text, String formType) {
        // For now, return the full cleaned text
        // Can be enhanced later with form-specific section extraction
        return "**Full Filing Content:**\n\n" + text;
    }
    
    /**
     * Parse filings from SEC EDGAR browse page HTML
     */
    public List<Filing> parseFilingsFromBrowsePage(String htmlContent, String cik, String ticker) {
        // Implementation moved from main class - simplified for now
        // This would contain the HTML parsing logic for browse pages
        return List.of(); // Placeholder
    }
    
    /**
     * Shutdown the executor service
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}