package ai.intelliswarm.swarmai.examples.stock.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SECFilingsTool Tests")
class SECFilingsToolTest {

    @InjectMocks
    private SECFilingsTool secFilingsTool;

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        
        // Use reflection to set the mocked RestTemplate and ObjectMapper
        Field restTemplateField = SECFilingsTool.class.getDeclaredField("restTemplate");
        restTemplateField.setAccessible(true);
        restTemplateField.set(secFilingsTool, restTemplate);
        
        Field objectMapperField = SECFilingsTool.class.getDeclaredField("objectMapper");
        objectMapperField.setAccessible(true);
        objectMapperField.set(secFilingsTool, objectMapper);
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("sec_filings", secFilingsTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        String description = secFilingsTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("SEC filings"));
        assertTrue(description.contains("TICKER:QUERY"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(secFilingsTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = secFilingsTool.getParameterSchema();
        
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("input"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> inputParam = (Map<String, Object>) properties.get("input");
        assertEquals("string", inputParam.get("type"));
        assertNotNull(inputParam.get("description"));
        
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);
        assertEquals("input", required[0]);
    }

    @Test
    @DisplayName("Should handle invalid input format")
    void testExecuteWithInvalidInputFormat() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL");  // Missing query part
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"));
        assertTrue(result.toString().contains("TICKER:QUERY"));
    }

    @Test
    @DisplayName("Should handle successful SEC filing retrieval")
    void testExecuteWithSuccessfulRetrieval() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:revenue trends");
        
        // Mock ticker to CIK mapping response
        String tickerMappingJson = createMockTickerMappingJson();
        ResponseEntity<String> tickerResponse = new ResponseEntity<>(tickerMappingJson, HttpStatus.OK);
        
        // Mock filings response
        String filingsJson = createMockFilingsJson();
        ResponseEntity<String> filingsResponse = new ResponseEntity<>(filingsJson, HttpStatus.OK);
        
        when(restTemplate.exchange(
            contains("company_tickers.json"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(tickerResponse);
        
        when(restTemplate.exchange(
            contains("submissions/CIK"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(filingsResponse);
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        
        // Verify the report contains expected sections
        assertTrue(resultStr.contains("SEC Filings Analysis for AAPL"), 
            "Result should contain 'SEC Filings Analysis for AAPL'. Actual: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("CIK:"), 
            "Result should contain 'CIK:'. Actual: " + resultStr.substring(0, Math.min(200, resultStr.length())));
        assertTrue(resultStr.contains("Search Query: revenue trends") || resultStr.contains("Search Query:** revenue trends"),
            "Result should contain search query");
        assertTrue(resultStr.contains("Analysis Guidance") || resultStr.contains("Analysis guidance"),
            "Result should contain analysis guidance");
        assertTrue(resultStr.contains("Revenue Analysis:") || resultStr.contains("revenue"),
            "Result should contain revenue analysis");
        assertTrue(resultStr.contains("Additional Resources") || resultStr.contains("SEC EDGAR"),
            "Result should contain additional resources");
        
        // Verify API calls were made (2 original + up to 3 content fetching calls)
        verify(restTemplate, atMost(5)).exchange(
            anyString(),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        );
        
        // Verify at least the basic calls were made (CIK lookup + filings)
        verify(restTemplate, atLeast(2)).exchange(
            anyString(),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        );
    }

    @Test
    @DisplayName("Should handle ticker not found")
    void testExecuteWithTickerNotFound() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "INVALID:test query");
        
        // Mock empty ticker mapping response
        String emptyTickerMappingJson = "{}";
        ResponseEntity<String> tickerResponse = new ResponseEntity<>(emptyTickerMappingJson, HttpStatus.OK);
        
        when(restTemplate.exchange(
            contains("company_tickers.json"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(tickerResponse);
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"));
        assertTrue(resultStr.contains("Could not find CIK for ticker INVALID"));
    }

    @Test
    @DisplayName("Should handle API errors gracefully")
    void testExecuteWithAPIError() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:test query");
        
        when(restTemplate.exchange(
            anyString(),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenThrow(new RestClientException("Network error"));
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains("Error"));
    }

    @Test
    @DisplayName("Should generate appropriate analysis guidance for different queries")
    void testAnalysisGuidanceGeneration() throws Exception {
        // Test revenue query
        testQueryGuidance("MSFT:revenue analysis", "Revenue Analysis:");
        
        // Test risk query
        testQueryGuidance("GOOGL:risk factors", "Risk Factors:");
        
        // Test earnings query
        testQueryGuidance("AMZN:earnings report", "Earnings Analysis:");
        
        // Test debt query
        testQueryGuidance("TSLA:debt levels", "Debt & Liquidity:");
        
        // Test insider trading query
        testQueryGuidance("META:insider trading", "Insider Trading:");
    }

    private void testQueryGuidance(String input, String expectedGuidance) throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", input);
        
        String ticker = input.split(":")[0];
        
        // Mock responses
        String tickerMappingJson = createMockTickerMappingForTicker(ticker);
        ResponseEntity<String> tickerResponse = new ResponseEntity<>(tickerMappingJson, HttpStatus.OK);
        
        String filingsJson = createMockFilingsJson();
        ResponseEntity<String> filingsResponse = new ResponseEntity<>(filingsJson, HttpStatus.OK);
        
        when(restTemplate.exchange(
            contains("company_tickers.json"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(tickerResponse);
        
        when(restTemplate.exchange(
            contains("submissions/CIK"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(filingsResponse);
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        String resultStr = result.toString();
        assertTrue(resultStr.contains(expectedGuidance), 
            "Expected guidance '" + expectedGuidance + "' not found in result for query: " + input);
    }

    @Test
    @DisplayName("Should format filing information correctly")
    void testFilingInfoFormatting() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:general analysis");
        
        String tickerMappingJson = createMockTickerMappingJson();
        ResponseEntity<String> tickerResponse = new ResponseEntity<>(tickerMappingJson, HttpStatus.OK);
        
        String filingsJson = createMockFilingsJson();
        ResponseEntity<String> filingsResponse = new ResponseEntity<>(filingsJson, HttpStatus.OK);
        
        when(restTemplate.exchange(
            contains("company_tickers.json"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(tickerResponse);
        
        when(restTemplate.exchange(
            contains("submissions/CIK"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(filingsResponse);
        
        Object result = secFilingsTool.execute(parameters);
        String resultStr = result.toString();
        
        // Check for filing sections
        assertTrue(resultStr.contains("Latest Annual Reports (10-K)"));
        assertTrue(resultStr.contains("Recent Quarterly Reports (10-Q)"));
        assertTrue(resultStr.contains("Recent Current Reports (8-K)"));
        
        // Check for filing details
        assertTrue(resultStr.contains("Accession Number:"));
        assertTrue(resultStr.contains("Document:"));
        assertTrue(resultStr.contains("Filed:"));
    }

    // Helper methods to create mock JSON responses
    private String createMockTickerMappingJson() {
        // Create a more realistic mock that matches SEC API structure
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode root = mapper.createArrayNode();
        
        // Add multiple companies for more realistic testing
        for (int i = 0; i < 5; i++) {
            ObjectNode company = mapper.createObjectNode();
            company.put("cik_str", 320193 + i);
            company.put("ticker", i == 0 ? "AAPL" : "TEST" + i);
            company.put("title", i == 0 ? "Apple Inc." : "Test Company " + i);
            root.add(company);
        }
        
        // The actual SEC API returns an object with numeric keys
        ObjectNode wrapper = mapper.createObjectNode();
        for (int i = 0; i < root.size(); i++) {
            wrapper.set(String.valueOf(i), root.get(i));
        }
        
        return wrapper.toString();
    }

    private String createMockTickerMappingForTicker(String ticker) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode company = objectMapper.createObjectNode();
        company.put("cik_str", 320193);
        company.put("ticker", ticker);
        company.put("title", ticker + " Company");
        root.set("0", company);
        return root.toString();
    }

    private String createMockFilingsJson() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode filings = objectMapper.createObjectNode();
        ObjectNode recent = objectMapper.createObjectNode();
        
        // Create arrays for filing data
        ArrayNode forms = objectMapper.createArrayNode();
        forms.add("10-K").add("10-K").add("10-Q").add("10-Q").add("10-Q").add("10-Q")
             .add("8-K").add("8-K").add("8-K").add("8-K").add("8-K");
        
        ArrayNode dates = objectMapper.createArrayNode();
        dates.add("2024-11-01").add("2023-11-03").add("2024-08-02").add("2024-05-03")
             .add("2024-02-02").add("2023-11-03").add("2024-09-15").add("2024-09-10")
             .add("2024-09-05").add("2024-08-28").add("2024-08-20");
        
        ArrayNode accessionNumbers = objectMapper.createArrayNode();
        for (int i = 0; i < 11; i++) {
            accessionNumbers.add("0000320193-24-00000" + i);
        }
        
        ArrayNode primaryDocuments = objectMapper.createArrayNode();
        for (int i = 0; i < 11; i++) {
            primaryDocuments.add("doc" + i + ".htm");
        }
        
        recent.set("form", forms);
        recent.set("filingDate", dates);
        recent.set("accessionNumber", accessionNumbers);
        recent.set("primaryDocument", primaryDocuments);
        
        filings.set("recent", recent);
        root.set("filings", filings);
        
        return root.toString();
    }

    @Test
    @DisplayName("Should handle null input gracefully")
    void testExecuteWithNullInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", null);
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"));
    }

    @Test
    @DisplayName("Should handle empty input gracefully")
    void testExecuteWithEmptyInput() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "");
        
        Object result = secFilingsTool.execute(parameters);
        
        assertNotNull(result);
        assertTrue(result.toString().contains("Error"));
        assertTrue(result.toString().contains("cannot be null or empty"));
    }

    @Test
    @DisplayName("Should include SEC EDGAR links in output")
    void testSECEDGARLinks() throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("input", "AAPL:test");
        
        String tickerMappingJson = createMockTickerMappingJson();
        ResponseEntity<String> tickerResponse = new ResponseEntity<>(tickerMappingJson, HttpStatus.OK);
        
        String filingsJson = createMockFilingsJson();
        ResponseEntity<String> filingsResponse = new ResponseEntity<>(filingsJson, HttpStatus.OK);
        
        when(restTemplate.exchange(
            contains("company_tickers.json"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(tickerResponse);
        
        when(restTemplate.exchange(
            contains("submissions/CIK"),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(filingsResponse);
        
        Object result = secFilingsTool.execute(parameters);
        String resultStr = result.toString();
        
        // Check for SEC EDGAR links
        assertTrue(resultStr.contains("https://www.sec.gov/edgar/browse/?CIK="));
        assertTrue(resultStr.contains("https://data.sec.gov/submissions/CIK"));
        assertTrue(resultStr.contains("View all filings on SEC EDGAR"));
        assertTrue(resultStr.contains("Company profile on SEC"));
    }
}