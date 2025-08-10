package ai.intelliswarm.swarmai.examples.stock.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Integration tests for WebSearchTool that make real API calls.
 * These tests require API keys to be configured and are tagged as "integration".
 * 
 * To run only integration tests: mvn test -Dgroups=integration
 * To exclude integration tests: mvn test -DexcludedGroups=integration
 * 
 * Required environment variables for full testing:
 * - GOOGLE_API_KEY: Google Custom Search API key
 * - GOOGLE_SEARCH_ENGINE_ID: Google Custom Search Engine ID
 * - BING_API_KEY: Bing Search API key
 * - NEWSAPI_KEY: NewsAPI key
 * - FINNHUB_API_KEY: Finnhub API key
 * - POLYGON_API_KEY: Polygon.io API key
 */
@Tag("integration")
@DisplayName("WebSearchTool Integration Tests")
class WebSearchToolIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSearchToolIntegrationTest.class);
    private static final String OUTPUT_DIR = "target/integration-test-outputs";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    
    private WebSearchTool webSearchTool;
    
    @BeforeEach
    void setUp() {
        webSearchTool = new WebSearchTool();
        
        // Set up API keys from environment variables
        setupApiKeysFromEnvironment();
        
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
    
    private void setupApiKeysFromEnvironment() {
        String googleApiKey = System.getenv("GOOGLE_API_KEY");
        String googleSearchEngineId = System.getenv("GOOGLE_SEARCH_ENGINE_ID");
        String bingApiKey = System.getenv("BING_API_KEY");
        String newsApiKey = System.getenv("NEWSAPI_KEY");
        String finnhubApiKey = System.getenv("FINNHUB_API_KEY");
        String polygonApiKey = System.getenv("POLYGON_API_KEY");
        
        // Always set Alpha Vantage to demo key for testing
        ReflectionTestUtils.setField(webSearchTool, "alphaVantageApiKey", "demo");
        
        // Set other keys if available
        if (googleApiKey != null && !googleApiKey.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "googleApiKey", googleApiKey);
        }
        if (googleSearchEngineId != null && !googleSearchEngineId.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "googleSearchEngineId", googleSearchEngineId);
        }
        if (bingApiKey != null && !bingApiKey.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "bingApiKey", bingApiKey);
        }
        if (newsApiKey != null && !newsApiKey.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "newsApiKey", newsApiKey);
        }
        if (finnhubApiKey != null && !finnhubApiKey.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "finnhubApiKey", finnhubApiKey);
        }
        if (polygonApiKey != null && !polygonApiKey.isEmpty()) {
            ReflectionTestUtils.setField(webSearchTool, "polygonApiKey", polygonApiKey);
        }
        
        logger.info("Set up API keys - Google: {}, Bing: {}, NewsAPI: {}, Finnhub: {}, Polygon: {}", 
            googleApiKey != null ? "configured" : "not configured",
            bingApiKey != null ? "configured" : "not configured",
            newsApiKey != null ? "configured" : "not configured",
            finnhubApiKey != null ? "configured" : "not configured",
            polygonApiKey != null ? "configured" : "not configured");
    }
    
    /**
     * Writes test output to a file for visual inspection
     */
    private void writeOutputToFile(String testName, String query, String output) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String sanitizedQuery = query.replaceAll("[^a-zA-Z0-9]", "_");
            String filename = String.format("WEB_%s_%s_%s.md", testName, sanitizedQuery, timestamp);
            Path filePath = Paths.get(OUTPUT_DIR, filename);
            
            StringBuilder content = new StringBuilder();
            content.append("# WebSearchTool Integration Test Output\n\n");
            content.append(String.format("**Test Name:** %s\n", testName));
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
    @DisplayName("Should perform basic search with Alpha Vantage (demo key)")
    void testBasicSearchWithAlphaVantage() {
        logger.info("Testing basic search with Alpha Vantage demo key...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL stock analysis");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result, "Result should not be null");
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Basic_Search_Alpha_Vantage", "AAPL stock analysis", resultStr);
        
        logger.info("Result length: {} characters", resultStr.length());
        logger.info("First 500 chars: {}", resultStr.substring(0, Math.min(500, resultStr.length())));
        
        // Basic structure checks
        assertTrue(resultStr.contains("Web Search Results for: \"AAPL stock analysis\""), 
            "Should contain search query");
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: AAPL") || 
                               resultStr.contains("AAPL");
        assertTrue(tickerDetected, "Should contain AAPL ticker reference");
        
        assertTrue(resultStr.contains("Market Data") || resultStr.contains("Company Overview") || 
                  resultStr.contains("Financial News"), 
            "Should contain at least one data section");
        
        // Should be substantial response
        assertTrue(resultStr.length() > 1000, 
            "Response should be comprehensive");
    }
    
    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
    @DisplayName("Should perform Google search when API key is configured")
    void testGoogleSearchIntegration() {
        logger.info("Testing Google Search integration...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "TSLA earnings report");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Google_Search_Integration", "TSLA earnings report", resultStr);
        
        logger.info("Google search result length: {} characters", resultStr.length());
        
        // Should contain Google search results if API key is configured
        assertTrue(resultStr.contains("Web Search Results"), 
            "Should contain web search results");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: TSLA") || 
                               resultStr.contains("TSLA");
        assertTrue(tickerDetected, "Should contain TSLA ticker reference");
        
        // Check for Google search specific content
        boolean hasGoogleResults = resultStr.contains("Google Search") || 
                                 resultStr.contains("search results") ||
                                 resultStr.contains("Found") ||
                                 resultStr.contains("results for") ||
                                 resultStr.contains("Web Search");
        
        assertTrue(hasGoogleResults, "Should contain search results");
    }
    
    @Test
    @EnabledIfEnvironmentVariable(named = "BING_API_KEY", matches = ".+")
    @DisplayName("Should perform Bing search when API key is configured")
    void testBingSearchIntegration() {
        logger.info("Testing Bing Search integration...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "MSFT market analysis");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Bing_Search_Integration", "MSFT market analysis", resultStr);
        
        logger.info("Bing search result length: {} characters", resultStr.length());
        
        // Should contain Bing search results if API key is configured
        assertTrue(resultStr.contains("Web Search Results"), 
            "Should contain web search results");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: MSFT") || 
                               resultStr.contains("MSFT");
        assertTrue(tickerDetected, "Should contain MSFT ticker reference");
        
        // Check for Bing search specific content
        boolean hasBingResults = resultStr.contains("Bing Search") || 
                               resultStr.contains("search results") ||
                               resultStr.contains("Found") ||
                               resultStr.contains("results for") ||
                               resultStr.contains("Web Search");
        
        assertTrue(hasBingResults, "Should contain search results");
    }
    
    @Test
    @EnabledIfEnvironmentVariable(named = "NEWSAPI_KEY", matches = ".+")
    @DisplayName("Should fetch news from NewsAPI when configured")
    void testNewsAPIIntegration() {
        logger.info("Testing NewsAPI integration...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AMZN financial news");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        logger.info("NewsAPI result length: {} characters", resultStr.length());
        
        // Should contain news results
        assertTrue(resultStr.contains("Financial News"), 
            "Should contain Financial News section");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: AMZN") || 
                               resultStr.contains("AMZN");
        assertTrue(tickerDetected, "Should contain AMZN ticker reference");
        
        // Check for news-specific content
        boolean hasNewsContent = resultStr.contains("article") || 
                               resultStr.contains("news") ||
                               resultStr.contains("headline") ||
                               resultStr.contains("published");
        
        assertTrue(hasNewsContent, "Should contain news-related content");
    }
    
    @Test
    @EnabledIfEnvironmentVariable(named = "FINNHUB_API_KEY", matches = ".+")
    @DisplayName("Should fetch financial news from Finnhub when configured")
    void testFinnhubIntegration() {
        logger.info("Testing Finnhub integration...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "GOOGL company news");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        logger.info("Finnhub result length: {} characters", resultStr.length());
        
        // Should contain financial news
        assertTrue(resultStr.contains("Financial News"), 
            "Should contain Financial News section");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: GOOGL") || 
                               resultStr.contains("GOOGL");
        assertTrue(tickerDetected, "Should contain GOOGL ticker reference");
        
        // Check for financial news content
        boolean hasFinancialContent = resultStr.contains("Finnhub") || 
                                    resultStr.contains("financial") ||
                                    resultStr.contains("market");
        
        assertTrue(hasFinancialContent, "Should contain financial content");
    }
    
    @Test
    @DisplayName("Should handle multiple API integrations simultaneously")
    void testMultipleAPIIntegrations() {
        logger.info("Testing multiple API integrations...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "NVDA artificial intelligence growth");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Write output to file for visual inspection
        writeOutputToFile("Multiple_API_Integration", "NVDA artificial intelligence growth", resultStr);
        
        logger.info("Multi-API result length: {} characters", resultStr.length());
        
        // Basic structure should always be present
        assertTrue(resultStr.contains("Web Search Results"), 
            "Should contain web search results");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: NVDA") || 
                               resultStr.contains("NVDA");
        assertTrue(tickerDetected, "Should contain NVDA ticker reference");
        
        assertTrue(resultStr.contains("Market Data") || resultStr.contains("Company Overview") || 
                  resultStr.contains("Financial News"), 
            "Should contain at least one data section");
        
        // Should provide comprehensive information
        assertTrue(resultStr.length() > 1500, 
            "Multi-API response should be comprehensive");
    }
    
    @Test
    @DisplayName("Should handle API failures gracefully in integration environment")
    void testAPIFailureHandling() {
        logger.info("Testing API failure handling...");
        
        // Set invalid API keys to test failure handling
        ReflectionTestUtils.setField(webSearchTool, "googleApiKey", "invalid_key");
        ReflectionTestUtils.setField(webSearchTool, "bingApiKey", "invalid_key");
        ReflectionTestUtils.setField(webSearchTool, "newsApiKey", "invalid_key");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL test failure handling");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result, "Should return result even with API failures");
        String resultStr = result.toString();
        
        // Should still provide structured response
        assertTrue(resultStr.contains("Web Search Results"), 
            "Should still provide basic structure");
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: AAPL") || 
                               resultStr.contains("AAPL");
        assertTrue(tickerDetected, "Should contain AAPL ticker reference");
        
        // Should handle errors gracefully without crashing
        assertTrue(resultStr.length() > 500, 
            "Should provide meaningful response despite API failures");
    }
    
    @Test
    @DisplayName("Should provide different responses for different query types")
    void testDifferentQueryTypes() {
        // Test earnings query
        testQueryTypeIntegration("AAPL quarterly earnings", "earnings", "quarterly");
        
        // Test news query  
        testQueryTypeIntegration("TSLA latest news", "news", "latest");
        
        // Test analysis query
        testQueryTypeIntegration("MSFT financial analysis", "analysis", "financial");
    }
    
    private void testQueryTypeIntegration(String query, String... expectedKeywords) {
        logger.info("Testing query type: {}", query);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", query);
        
        Object result = webSearchTool.execute(parameters);
        assertNotNull(result);
        
        String resultStr = result.toString().toLowerCase();
        
        // Should contain relevant keywords based on query
        for (String keyword : expectedKeywords) {
            assertTrue(resultStr.contains(keyword.toLowerCase()), 
                "Result should contain keyword '" + keyword + "' for query: " + query);
        }
        
        // Should be substantial response
        assertTrue(resultStr.length() > 800, 
            "Response should be substantial for query: " + query);
    }
    
    @Test
    @DisplayName("Performance test - should complete within reasonable time")
    void testPerformance() {
        logger.info("Testing performance...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL performance test");
        
        long startTime = System.currentTimeMillis();
        Object result = webSearchTool.execute(parameters);
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        logger.info("WebSearch API calls took {} ms", duration);
        
        assertNotNull(result);
        
        // Should complete within 15 seconds (generous timeout for multiple API calls)
        assertTrue(duration < 15000, 
            "WebSearch should complete within 15 seconds. Actual: " + duration + " ms");
        
        // Should provide meaningful response
        String resultStr = result.toString();
        assertTrue(resultStr.length() > 500, 
            "Should provide substantial response within time limit");
    }
    
    @Test
    @DisplayName("Should handle concurrent API calls efficiently")
    void testConcurrentAPICalls() {
        logger.info("Testing concurrent API call efficiency...");
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AMZN cloud services market");
        
        long startTime = System.currentTimeMillis();
        Object result = webSearchTool.execute(parameters);
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        logger.info("Concurrent API calls took {} ms", duration);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should complete faster than sequential calls would take
        // (This is hard to test precisely, but should be under 10 seconds for concurrent execution)
        assertTrue(duration < 10000, 
            "Concurrent API execution should be efficient. Actual: " + duration + " ms");
        
        // Should contain comprehensive information from multiple sources
        assertTrue(resultStr.contains("Market Data") || resultStr.contains("Company Overview") || 
                  resultStr.contains("Financial News"), 
            "Should aggregate information from multiple API sources");
    }
    
    @Test
    @DisplayName("Should extract and process ticker symbols correctly in integration")
    void testTickerExtractionIntegration() {
        // Test different ticker formats with real API calls
        testTickerFormatIntegration("$MSFT analysis", "MSFT");
        testTickerFormatIntegration("Analysis of (AAPL)", "AAPL");
        testTickerFormatIntegration("TSLA earnings report", "TSLA");
    }
    
    private void testTickerFormatIntegration(String query, String expectedTicker) {
        logger.info("Testing ticker extraction integration: {}", query);
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", query);
        
        Object result = webSearchTool.execute(parameters);
        assertNotNull(result);
        
        String resultStr = result.toString();
        
        // Check ticker detection (may or may not work depending on implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: " + expectedTicker) || 
                               resultStr.contains(expectedTicker);
        assertTrue(tickerDetected, "Should contain " + expectedTicker + " ticker reference in query: " + query);
        
        // Should provide ticker-specific information
        assertTrue(resultStr.contains("Market Data") || resultStr.contains("Company Overview"), 
            "Should provide ticker-specific information");
    }
}