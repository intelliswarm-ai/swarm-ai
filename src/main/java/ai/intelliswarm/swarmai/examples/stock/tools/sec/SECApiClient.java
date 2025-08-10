package ai.intelliswarm.swarmai.examples.stock.tools.sec;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for communicating with SEC APIs
 */
public class SECApiClient {
    
    private static final Logger logger = LoggerFactory.getLogger(SECApiClient.class);
    private static final String SEC_API_BASE = "https://data.sec.gov";
    private static final String TICKER_LOOKUP_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String USER_AGENT = "SwarmAI Stock Analysis Tool (contact@intelliswarm.ai)";
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    public SECApiClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Get CIK (Central Index Key) for a ticker symbol
     */
    public String getCIKFromTicker(String ticker) {
        try {
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(
                TICKER_LOOKUP_URL, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                for (JsonNode company : root) {
                    String companyTicker = company.get("ticker").asText();
                    if (companyTicker.equalsIgnoreCase(ticker)) {
                        return String.valueOf(company.get("cik_str").asInt());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching CIK for ticker: {}", ticker, e);
        }
        return null;
    }
    
    /**
     * Get recent filings for a company by CIK
     */
    public List<Filing> getRecentFilings(String cik) {
        List<Filing> filings = new ArrayList<>();
        
        try {
            String formattedCik = String.format("%010d", Integer.parseInt(cik));
            String url = SEC_API_BASE + "/submissions/CIK" + formattedCik + ".json";
            
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode recentFilingsNode = root.path("filings").path("recent");
                
                if (!recentFilingsNode.isMissingNode()) {
                    JsonNode forms = recentFilingsNode.path("form");
                    JsonNode dates = recentFilingsNode.path("filingDate");
                    JsonNode accessionNumbers = recentFilingsNode.path("accessionNumber");
                    JsonNode primaryDocuments = recentFilingsNode.path("primaryDocument");
                    
                    int count = Math.min(50, forms.size());
                    
                    for (int i = 0; i < count; i++) {
                        Filing filing = new Filing();
                        filing.setFormType(forms.get(i).asText());
                        filing.setFilingDate(dates.get(i).asText());
                        filing.setAccessionNumber(accessionNumbers.get(i).asText());
                        filing.setPrimaryDocument(primaryDocuments.get(i).asText());
                        
                        // Create URL for the filing
                        String accessionNumberFormatted = filing.getAccessionNumber().replace("-", "");
                        String url_filing = String.format("https://www.sec.gov/Archives/edgar/data/%s/%s/%s",
                            cik, accessionNumberFormatted, filing.getPrimaryDocument());
                        filing.setUrl(url_filing);
                        
                        filings.add(filing);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching recent filings for CIK: {}", cik, e);
        }
        
        return filings;
    }
    
    /**
     * Fetch content from a filing URL
     */
    public String fetchFilingContent(String url) {
        try {
            HttpHeaders headers = createHeaders();
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            } else {
                logger.warn("Failed to fetch filing content from {}: HTTP {}", url, response.getStatusCode());
            }
        } catch (Exception e) {
            logger.warn("Error fetching filing content from {}: {}", url, e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Scrape filings from SEC EDGAR browse page
     */
    public String fetchBrowsePage(String cik) {
        try {
            String url = String.format("https://www.sec.gov/edgar/browse/?CIK=%s", cik);
            HttpHeaders headers = createHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);
            
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
        } catch (RestClientException e) {
            logger.warn("Error fetching SEC EDGAR browse page for CIK {}: {}", cik, e.getMessage());
        }
        
        return null;
    }
    
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.set("Accept", "application/json");
        return headers;
    }
}