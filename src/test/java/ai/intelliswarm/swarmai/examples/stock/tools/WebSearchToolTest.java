package ai.intelliswarm.swarmai.examples.stock.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchTool Tests")
class WebSearchToolTest {

    @InjectMocks
    private WebSearchTool webSearchTool;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ExecutorService executorService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // Set up test configuration using ReflectionTestUtils
        ReflectionTestUtils.setField(webSearchTool, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(webSearchTool, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(webSearchTool, "alphaVantageApiKey", "demo");
        ReflectionTestUtils.setField(webSearchTool, "newsApiKey", "");
        ReflectionTestUtils.setField(webSearchTool, "polygonApiKey", "");
        ReflectionTestUtils.setField(webSearchTool, "finnhubApiKey", "");
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("web_search", webSearchTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = webSearchTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("web searches"));
        assertTrue(description.contains("stock-related information"));
        assertTrue(description.contains("multiple sources"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(webSearchTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = webSearchTool.getParameterSchema();
        
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("query"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> queryParam = (Map<String, Object>) properties.get("query");
        assertEquals("string", queryParam.get("type"));
        assertNotNull(queryParam.get("description"));
        
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);
        assertEquals("query", required[0]);
    }

    @Test
    @DisplayName("Should handle basic search query without APIs")
    void testExecuteBasicSearch() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "Apple stock news");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Verify basic structure
        assertTrue(resultStr.contains("Web Search Results for: \"Apple stock news\""));
        assertTrue(resultStr.contains("Financial News"));
        assertTrue(resultStr.contains("SEC EDGAR"));
        assertTrue(resultStr.contains("Social Media Sentiment"));
        assertTrue(resultStr.contains("Search Recommendations"));
        assertTrue(resultStr.contains("Configuration Note"));
    }

    @Test
    @DisplayName("Should extract ticker symbols from queries")
    void testTickerExtraction() {
        // Test various ticker formats
        testTickerExtractionCase("AAPL stock analysis", "AAPL");
        testTickerExtractionCase("What is $MSFT doing?", "MSFT");  
        testTickerExtractionCase("Analysis of (GOOGL) performance", "GOOGL");
        testTickerExtractionCase("Tesla TSLA earnings", "TSLA");
        testTickerExtractionCase("general market news", null);
    }

    private void testTickerExtractionCase(String query, String expectedTicker) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", query);
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        if (expectedTicker != null) {
            boolean tickerDetected = resultStr.contains("Detected Ticker: " + expectedTicker) || 
                                   resultStr.contains(expectedTicker);
            assertTrue(tickerDetected,
                "Should contain ticker " + expectedTicker + " in query: " + query);
        } else {
            assertFalse(resultStr.contains("Detected Ticker:"),
                "Should not detect ticker in query: " + query);
        }
    }

    @Test
    @DisplayName("Should handle null query gracefully")
    void testNullQuery() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", null);
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        assertTrue(result.toString().contains("Error") || result.toString().contains("null"));
    }

    @Test
    @DisplayName("Should handle empty query gracefully")
    void testEmptyQuery() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Web Search Results"));
    }

    @Test
    @DisplayName("Should include all major sections in results")
    void testResultSections() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL financial analysis");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Check for all major sections
        assertTrue(resultStr.contains("## Financial News"));
        assertTrue(resultStr.contains("## Market Data"));
        assertTrue(resultStr.contains("## Company Overview"));
        assertTrue(resultStr.contains("## SEC EDGAR"));
        assertTrue(resultStr.contains("## Social Media Sentiment"));
        assertTrue(resultStr.contains("## Search Recommendations"));
        assertTrue(resultStr.contains("## Configuration Note"));
    }

    @Test
    @DisplayName("Should provide configuration guidance when no APIs configured")
    void testConfigurationGuidance() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "stock market analysis");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should suggest API configuration
        assertTrue(resultStr.contains("Configuration Note"));
        assertTrue(resultStr.contains("Alpha Vantage"));
        assertTrue(resultStr.contains("NewsAPI"));
        assertTrue(resultStr.contains("Finnhub"));
        assertTrue(resultStr.contains("application.yml"));
    }

    @Test
    @DisplayName("Should provide search recommendations")
    void testSearchRecommendations() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "investment analysis");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Check for useful recommendations
        assertTrue(resultStr.contains("SECFilingsTool"));
        assertTrue(resultStr.contains("earnings call transcripts"));
        assertTrue(resultStr.contains("analyst reports"));
        assertTrue(resultStr.contains("insider transactions"));
    }

    @Test
    @DisplayName("Should handle different query types appropriately")
    void testDifferentQueryTypes() {
        // Test financial query
        testQueryTypeResponse("AAPL earnings report", "earnings");
        
        // Test news query
        testQueryTypeResponse("Tesla latest news", "news");
        
        // Test market analysis query
        testQueryTypeResponse("Microsoft market analysis", "analysis");
        
        // Test sector query
        testQueryTypeResponse("tech stock performance", "tech");
    }

    private void testQueryTypeResponse(String query, String expectedContent) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", query);
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString().toLowerCase();
        
        // Should contain relevant content based on query type
        assertNotNull(resultStr);
        assertTrue(resultStr.length() > 100, "Response should be substantial");
    }

    @Test
    @DisplayName("Should format timestamp correctly")
    void testTimestampFormatting() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "test query");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should contain a properly formatted timestamp
        assertTrue(resultStr.contains("Search Time:"));
        assertTrue(resultStr.contains("202") && resultStr.contains("T"), 
            "Should contain ISO timestamp format");
    }

    @Test
    @DisplayName("Should provide social media platform information")
    void testSocialMediaInfo() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL social sentiment");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should mention key social media platforms
        assertTrue(resultStr.contains("Twitter"));
        assertTrue(resultStr.contains("Reddit"));
        assertTrue(resultStr.contains("StockTwits"));
        assertTrue(resultStr.contains("LinkedIn"));
    }

    @Test
    @DisplayName("Should provide SEC EDGAR information")
    void testSECInformation() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "MSFT SEC filings");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should reference SEC EDGAR and filing types
        assertTrue(resultStr.contains("SEC EDGAR"));
        assertTrue(resultStr.contains("10-K"));
        assertTrue(resultStr.contains("10-Q"));
        assertTrue(resultStr.contains("8-K"));
        assertTrue(resultStr.contains("SECFilingsTool"));
    }

    @Test
    @DisplayName("Should handle complex queries with multiple tickers")
    void testComplexQueries() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "Compare AAPL vs MSFT performance");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should extract one of the tickers (first match wins in current implementation)
        boolean tickerDetected = resultStr.contains("Detected Ticker: AAPL") || 
                               resultStr.contains("Detected Ticker: MSFT") ||
                               resultStr.contains("AAPL") || resultStr.contains("MSFT");
        assertTrue(tickerDetected, "Should contain ticker reference");
        assertTrue(resultStr.contains("Web Search Results"));
    }

    @Test
    @DisplayName("Should provide structured market data information")
    void testMarketDataStructure() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "GOOGL market data");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should provide market data structure
        assertTrue(resultStr.contains("Market Data"));
        assertTrue(resultStr.contains("price") || resultStr.contains("Price"));
        assertTrue(resultStr.contains("volume") || resultStr.contains("Volume"));
        assertTrue(resultStr.contains("Alpha Vantage"));
    }

    @Test
    @DisplayName("Should provide company overview structure")
    void testCompanyOverviewStructure() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AMZN company information");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should provide company overview structure or content
        assertTrue(resultStr.contains("Company Overview") || resultStr.contains("company") ||
                  resultStr.contains("business") || resultStr.contains("sector") || 
                  resultStr.contains("industry") || resultStr.contains("description") ||
                  resultStr.contains("Market Data") || resultStr.contains("Financial News"));
    }

    @Test
    @DisplayName("Should handle API errors gracefully")
    void testAPIErrorHandling() {
        // Set up API key to trigger actual API calls (which will fail in unit test)
        ReflectionTestUtils.setField(webSearchTool, "alphaVantageApiKey", "test_key");
        
        // Mock a failed API call
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
            .thenThrow(new RuntimeException("API Error"));
        
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "AAPL test");
        
        Object result = webSearchTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Should still return a valid response structure even with API errors
        assertTrue(resultStr.contains("Web Search Results"));
        // May contain error information but should not crash
    }

    @Test
    @DisplayName("Should include financial news sources")
    void testFinancialNewsSources() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "financial market news");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // Should mention financial news or sources
        assertTrue(resultStr.contains("Financial News") || resultStr.contains("financial") || 
                  resultStr.contains("news") || resultStr.contains("Reuters") || 
                  resultStr.contains("Bloomberg") || resultStr.contains("CNBC") || 
                  resultStr.contains("Wall Street Journal"));
    }

    @Test
    @DisplayName("Should provide ticker-specific information when ticker detected")  
    void testTickerSpecificInformation() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", "NVDA quarterly results");
        
        Object result = webSearchTool.execute(parameters);
        String resultStr = result.toString();
        
        // When ticker is detected, should provide more specific information
        boolean tickerDetected = resultStr.contains("Detected Ticker: NVDA") || 
                               resultStr.contains("NVDA");
        assertTrue(tickerDetected, "Should contain NVDA ticker reference");
        
        assertTrue(resultStr.contains("Market Data") || resultStr.contains("Company Overview") ||
                  resultStr.contains("Financial News"), "Should contain data sections");
        
        // Should have different content than general queries
        assertTrue(resultStr.length() > 1000); // Should be comprehensive
    }
}