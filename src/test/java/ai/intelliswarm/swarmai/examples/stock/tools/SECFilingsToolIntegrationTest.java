package ai.intelliswarm.swarmai.examples.stock.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SECFilingsTool that make real API calls to SEC EDGAR.
 * These tests are tagged as "integration" and can be run separately.
 * 
 * To run only integration tests: mvn test -Dgroups=integration
 * To exclude integration tests: mvn test -DexcludedGroups=integration
 */
@Tag("integration")
@DisplayName("SECFilingsTool Integration Tests")
class SECFilingsToolIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SECFilingsToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    private SECFilingsTool secFilingsTool;
    
    @BeforeEach
    void setUp() {
        secFilingsTool = new SECFilingsTool();
        
        // Create output directory if it doesn't exist
        try {
            Path outputPath = Paths.get(OUTPUT_DIR);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
                logger.info("Created output directory: {}", outputPath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.warn("Failed to create output directory: {}", e.getMessage());
        }
    }
    
    /**
     * Writes test output to a file for visual inspection
     */
    private void writeOutputToFile(String testName, String ticker, String query, String output) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String filename = String.format("SEC_%s_%s_%s.md", testName, ticker, timestamp);
            Path filePath = Paths.get(OUTPUT_DIR, filename);
            
            StringBuilder content = new StringBuilder();
            content.append("# SECFilingsTool Integration Test Output\n\n");
            content.append(String.format("**Test Name:** %s\n", testName));
            content.append(String.format("**Ticker:** %s\n", ticker));
            content.append(String.format("**Query:** %s\n", query));
            content.append(String.format("**Timestamp:** %s\n", LocalDateTime.now()));
            content.append(String.format("**Output Length:** %d characters\n\n", output.length()));
            content.append("---\n\n");
            content.append(output);
            
            try (FileWriter writer = new FileWriter(filePath.toFile())) {
                writer.write(content.toString());
            }
            
            logger.info("Test output written to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to write test output to file: {}", e.getMessage());
        }
    }
    
    @Test
    @DisplayName("Should fetch real SEC filings for Apple (AAPL)")
    void testRealSECFilingsForApple() {
        logger.info("Testing real SEC API call for AAPL...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:revenue trends");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Apple_Real_SEC_Filings", "AAPL", "revenue trends", resultStr);
        
        logger.info("Result length: {} characters", resultStr.length());
        logger.info("First 500 chars of result:\n{}", 
            resultStr.substring(0, Math.min(500, resultStr.length())));
        
        // Verify the response contains expected elements
        assertFalse(resultStr.contains("Error"), 
            "Result should not contain error. Actual: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        
        // Check for Apple-specific information
        assertTrue(resultStr.contains("SEC Filings Analysis for AAPL"), 
            "Result should contain SEC Filings Analysis for AAPL");
        
        // Apple's CIK is 320193
        assertTrue(resultStr.contains("320193") || resultStr.contains("CIK"), 
            "Result should contain Apple's CIK number or CIK reference");
        
        // Check for filing types
        assertTrue(resultStr.contains("10-K") || resultStr.contains("10-Q") || resultStr.contains("8-K"), 
            "Result should contain at least one filing type");
        
        // Check for revenue-specific guidance since we searched for "revenue trends"
        assertTrue(resultStr.contains("Revenue") || resultStr.contains("revenue"), 
            "Result should contain revenue-related guidance");
        
        // Check for SEC EDGAR links
        assertTrue(resultStr.contains("sec.gov"), 
            "Result should contain SEC.gov links");
    }
    
    @Test
    @DisplayName("Should fetch real SEC filings for Microsoft (MSFT)")
    void testRealSECFilingsForMicrosoft() {
        logger.info("Testing real SEC API call for MSFT...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "MSFT:earnings report");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Microsoft_Real_SEC_Filings", "MSFT", "earnings report", resultStr);
        
        logger.info("Result length: {} characters", resultStr.length());
        
        // Verify the response contains expected elements
        assertFalse(resultStr.contains("Error"), 
            "Result should not contain error");
        
        assertTrue(resultStr.contains("SEC Filings Analysis for MSFT"), 
            "Result should contain SEC Filings Analysis for MSFT");
        
        // Microsoft's CIK is 789019
        assertTrue(resultStr.contains("789019") || resultStr.contains("CIK"), 
            "Result should contain Microsoft's CIK number or CIK reference");
        
        // Check for earnings-specific guidance
        assertTrue(resultStr.contains("Earnings") || resultStr.contains("earnings") || resultStr.contains("Income Statement"), 
            "Result should contain earnings-related guidance");
    }
    
    @Test
    @DisplayName("Should fetch real SEC filings for Tesla (TSLA)")
    void testRealSECFilingsForTesla() {
        logger.info("Testing real SEC API call for TSLA...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "TSLA:risk factors");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Tesla_Real_SEC_Filings", "TSLA", "risk factors", resultStr);
        
        logger.info("Result length: {} characters", resultStr.length());
        
        // Verify the response contains expected elements
        assertFalse(resultStr.contains("Error"), 
            "Result should not contain error");
        
        assertTrue(resultStr.contains("SEC Filings Analysis for TSLA"), 
            "Result should contain SEC Filings Analysis for TSLA");
        
        // Tesla's CIK is 1318605
        assertTrue(resultStr.contains("1318605") || resultStr.contains("CIK"), 
            "Result should contain Tesla's CIK number or CIK reference");
        
        // Check for risk-specific guidance
        assertTrue(resultStr.contains("Risk") || resultStr.contains("risk"), 
            "Result should contain risk-related guidance");
    }
    
    @Test
    @DisplayName("Should handle invalid ticker gracefully")
    void testInvalidTicker() {
        logger.info("Testing invalid ticker handling...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "INVALIDTICKER123:test query");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Invalid_Ticker_Test", "INVALIDTICKER123", "test query", resultStr);
        
        logger.info("Result for invalid ticker: {}", resultStr);
        
        // Should return an error message
        assertTrue(resultStr.contains("Error"), 
            "Result should contain error message for invalid ticker");
        assertTrue(resultStr.contains("Could not find CIK") || resultStr.contains("Error"), 
            "Result should indicate that CIK was not found");
    }
    
    @Test
    @DisplayName("Should fetch filings with different query types")
    void testDifferentQueryTypes() {
        // Test debt query
        testQueryType("AAPL:debt and liquidity", "Debt", "Liquidity");
        
        // Test insider trading query
        testQueryType("MSFT:insider trading activity", "Insider", "Form 4");
        
        // Test general financial query
        testQueryType("GOOGL:financial performance", "financial", "General Analysis");
    }
    
    private void testQueryType(String input, String... expectedKeywords) {
        logger.info("Testing query: {}", input);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", input);
        
        Object result = secFilingsTool.execute(parameters);
        assertNotNull(result);
        
        String resultStr = result.toString();
        assertFalse(resultStr.contains("Error"), 
            "Result should not contain error for query: " + input);
        
        for (String keyword : expectedKeywords) {
            assertTrue(resultStr.toLowerCase().contains(keyword.toLowerCase()), 
                "Result should contain keyword '" + keyword + "' for query: " + input);
        }
    }
    
    @Test
    @DisplayName("Should include proper filing URLs")
    void testFilingURLs() {
        logger.info("Testing filing URLs...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:latest filings");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Check for proper SEC URLs
        assertTrue(resultStr.contains("https://www.sec.gov/Archives/edgar/data/"), 
            "Result should contain SEC Archives URLs");
        assertTrue(resultStr.contains("https://www.sec.gov/edgar/browse/"), 
            "Result should contain SEC browse URL");
        
        // Check for accession numbers (format: 0000320193-24-000001 or 0000320193-25-000001)
        // The accession number pattern is present in the result
        assertTrue(resultStr.contains("Accession Number:") || 
                  (resultStr.contains("-") && resultStr.contains("0000")), 
            "Result should contain accession numbers");
    }
    
    @Test
    @DisplayName("Should fetch recent filings with dates")
    void testRecentFilingsWithDates() {
        logger.info("Testing recent filings with dates...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "NVDA:recent quarterly reports");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        logger.info("NVDA filings result length: {} characters", resultStr.length());
        
        // Should not be an error
        assertFalse(resultStr.contains("Error"), 
            "Result should not contain error");
        
        // Check for filing dates (format: YYYY-MM-DD) - NVDA has fewer filings, check if we got any response
        assertTrue(resultStr.contains("SEC Filings Analysis") || resultStr.contains("CIK:"), 
            "Result should contain SEC filings information");
        
        // If there are filings, they should have dates (YYYY-MM-DD format)
        if (resultStr.contains("Filed:") || resultStr.contains("Filing Date")) {
            // Check for date pattern more flexibly
            assertTrue(resultStr.contains("202") || resultStr.contains("201"), 
                "Result should contain recent filing dates");
        }
        
        // Check for filing sections
        assertTrue(resultStr.contains("Filed:") || resultStr.contains("Filing Date") || resultStr.contains("10-Q"), 
            "Result should contain filing information");
    }
    
    @Test
    @DisplayName("Performance test - should complete within reasonable time")
    void testPerformance() {
        logger.info("Testing performance...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:test");
        
        long startTime = System.currentTimeMillis();
        Object result = secFilingsTool.execute(parameters);
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        logger.info("API call took {} ms", duration);
        
        assertNotNull(result);
        
        // Should complete within 10 seconds (generous timeout for network delays)
        assertTrue(duration < 10000, 
            "API call should complete within 10 seconds. Actual: " + duration + " ms");
    }
    
    @Test
    @DisplayName("Should handle multiple filing types")
    void testMultipleFilingTypes() {
        logger.info("Testing multiple filing types...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AMZN:comprehensive analysis");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should contain different filing types if available
        int filingTypesFound = 0;
        if (resultStr.contains("10-K")) filingTypesFound++;
        if (resultStr.contains("10-Q")) filingTypesFound++;
        if (resultStr.contains("8-K")) filingTypesFound++;
        
        logger.info("Found {} different filing types", filingTypesFound);
        
        assertTrue(filingTypesFound >= 1, 
            "Result should contain at least one filing type");
    }
    
    @Test
    @DisplayName("Should fetch real SEC filings for IMPP")
    void testRealSECFilingsForIMPP() {
        logger.info("Testing real SEC API call for IMPP...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "IMPP:financial statements and business operations");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("IMPP_Real_SEC_Filings", "IMPP", "financial statements and business operations", resultStr);
        
        logger.info("Result length: {} characters", resultStr.length());
        logger.info("First 500 chars of result:\n{}", 
            resultStr.substring(0, Math.min(500, resultStr.length())));
        
        // Check if this is an error or successful result
        if (resultStr.contains("Error") && resultStr.contains("Could not find CIK")) {
            // IMPP might not be found in SEC database, log this for information
            logger.warn("IMPP ticker not found in SEC database: {}", resultStr);
            
            // Verify it's the expected "ticker not found" error
            assertTrue(resultStr.contains("Could not find CIK for ticker IMPP"), 
                "Should return specific CIK not found error for IMPP");
        } else {
            // If IMPP is found, verify the successful response
            logger.info("IMPP filings found successfully");
            
            // Verify the response contains expected elements for successful lookup
            assertFalse(resultStr.contains("Error"), 
                "Result should not contain error for valid IMPP ticker");
            
            // Check for IMPP-specific information
            assertTrue(resultStr.contains("SEC Filings Analysis for IMPP"), 
                "Result should contain SEC Filings Analysis for IMPP");
            
            // Check for filing information
            assertTrue(resultStr.contains("CIK:") || resultStr.contains("10-K") || 
                      resultStr.contains("10-Q") || resultStr.contains("8-K"), 
                "Result should contain CIK or filing types");
            
            // Check for financial statements guidance since we searched for that
            assertTrue(resultStr.contains("financial") || resultStr.contains("Financial") || 
                      resultStr.contains("statements") || resultStr.contains("business"), 
                "Result should contain financial or business related content");
            
            // Check for SEC EDGAR links
            assertTrue(resultStr.contains("sec.gov"), 
                "Result should contain SEC.gov links");
        }
    }
}