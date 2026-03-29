package ai.intelliswarm.swarmai.tool.common.sec;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for communicating with SEC APIs.
 * Implements rate limiting, CIK caching, and retry-with-backoff for 429 errors
 * to comply with SEC's 10 req/sec guideline.
 */
public class SECApiClient {

    private static final Logger logger = LoggerFactory.getLogger(SECApiClient.class);
    private static final String SEC_API_BASE = "https://data.sec.gov";
    private static final String TICKER_LOOKUP_URL = "https://www.sec.gov/files/company_tickers.json";
    private static final String USER_AGENT = "SwarmAI Stock Analysis Tool (contact@intelliswarm.ai)";

    // Rate limiting: minimum ms between requests (~5 req/sec to stay well under SEC's 10/sec limit)
    private static final long MIN_REQUEST_INTERVAL_MS = 250;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_BACKOFF_MS = 3000; // 3 seconds backoff on 429

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Cache CIK lookups to avoid repeated calls for the same ticker
    private final Map<String, String> cikCache = new ConcurrentHashMap<>();

    // Track last request time for rate limiting
    private volatile long lastRequestTimeMs = 0;
    private final Object rateLimitLock = new Object();

    public SECApiClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Throttle requests to stay under SEC's 10 req/sec limit
     */
    private void throttle() {
        synchronized (rateLimitLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestTimeMs;
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                try {
                    Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestTimeMs = System.currentTimeMillis();
        }
    }

    /**
     * Execute an HTTP GET with retry-on-429 logic
     */
    private ResponseEntity<String> executeWithRetry(String url, HttpHeaders headers) {
        HttpEntity<String> entity = new HttpEntity<>(headers);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                throttle();
                return restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (attempt < MAX_RETRIES) {
                    long backoff = RETRY_BACKOFF_MS * (attempt + 1);
                    logger.warn("SEC rate limit hit (429). Waiting {}ms before retry {}/{}",
                        backoff, attempt + 1, MAX_RETRIES);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    throw e; // exhausted retries
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Get CIK (Central Index Key) for a ticker symbol.
     * Results are cached to avoid repeated API calls.
     */
    public String getCIKFromTicker(String ticker) {
        String upperTicker = ticker.toUpperCase();

        // Return cached result if available
        if (cikCache.containsKey(upperTicker)) {
            return cikCache.get(upperTicker);
        }

        try {
            ResponseEntity<String> response = executeWithRetry(TICKER_LOOKUP_URL, createHeaders());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                for (JsonNode company : root) {
                    String companyTicker = company.get("ticker").asText();
                    if (companyTicker.equalsIgnoreCase(upperTicker)) {
                        String cik = String.valueOf(company.get("cik_str").asInt());
                        cikCache.put(upperTicker, cik);
                        return cik;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching CIK for ticker {}: {}", ticker, e.getMessage());
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

            ResponseEntity<String> response = executeWithRetry(url, createHeaders());

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

            ResponseEntity<String> response = executeWithRetry(url, headers);

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

            ResponseEntity<String> response = executeWithRetry(url, createHeaders());

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
