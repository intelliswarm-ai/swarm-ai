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
     * Fetches structured XBRL financial facts for a company via SEC's companyfacts API.
     *
     * <p>Endpoint: {@code https://data.sec.gov/api/xbrl/companyfacts/CIK##########.json}
     * — returns every us-gaap concept the company has ever reported, with values and
     * periods. This is dramatically more reliable than scraping inline-XBRL from filing
     * HTML because SEC already did the parsing.
     *
     * @return a populated {@link CompanyFacts}, or {@code null} if the API was unreachable.
     *         Companies that don't report in us-gaap (some foreign issuers) may return
     *         a facts object with no concepts — callers should check {@code hasConcept}.
     */
    public CompanyFacts fetchCompanyFacts(String cik) {
        try {
            String formattedCik = String.format("%010d", Integer.parseInt(cik));
            String url = SEC_API_BASE + "/api/xbrl/companyfacts/CIK" + formattedCik + ".json";

            ResponseEntity<String> response = executeWithRetry(url, createHeaders());
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.warn("companyfacts API returned HTTP {} for CIK {}", response.getStatusCode(), cik);
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            CompanyFacts facts = new CompanyFacts();
            facts.setCik(cik);
            facts.setEntityName(root.path("entityName").asText(null));

            // SEC reports XBRL facts under two namespaces:
            //   - "us-gaap"   — domestic filers (10-K, 10-Q)
            //   - "ifrs-full" — foreign private issuers (20-F, 6-K) who file under IFRS
            // We parse both so foreign tickers (e.g. IMPP, a Greek shipping company) get
            // the same structured revenue/margin/EPS rescue that domestic filers get.
            JsonNode factsNode = root.path("facts");
            int parsedConcepts = 0;
            parsedConcepts += ingestNamespace(factsNode.path("us-gaap"), "us-gaap", facts);
            parsedConcepts += ingestNamespace(factsNode.path("ifrs-full"), "ifrs-full", facts);

            if (parsedConcepts == 0) {
                logger.info("No us-gaap or ifrs-full facts reported for CIK {}", cik);
            } else {
                logger.info("Loaded {} XBRL concepts (us-gaap+ifrs-full) from companyfacts for CIK {}",
                        parsedConcepts, cik);
            }
            return facts;

        } catch (HttpClientErrorException.NotFound nf) {
            logger.info("companyfacts not available for CIK {} (404)", cik);
            return null;
        } catch (Exception e) {
            logger.warn("Error fetching companyfacts for CIK {}: {}", cik, e.getMessage());
            return null;
        }
    }

    /**
     * Parses one XBRL namespace (us-gaap or ifrs-full) from the companyfacts response
     * and inserts all observations into {@code facts}. Concepts from different namespaces
     * are stored under "<namespace>:<Concept>" keys so the formatter can still look up
     * either one — but for readability when there's no collision, us-gaap concepts keep
     * their bare name for backward compatibility and ifrs-full gets a prefix only when it
     * collides.
     */
    private int ingestNamespace(JsonNode nsNode, String namespace, CompanyFacts facts) {
        if (nsNode.isMissingNode() || !nsNode.isObject()) {
            return 0;
        }
        int[] count = {0};
        nsNode.fieldNames().forEachRemaining(concept -> {
            JsonNode unitsNode = nsNode.get(concept).path("units");
            if (!unitsNode.isObject()) return;
            // If us-gaap already registered this concept, put the ifrs-full one under a
            // prefixed key so both can be inspected. The formatter looks up concepts by
            // a prioritized list so it'll prefer the bare (us-gaap) name when both exist.
            String storageKey = "us-gaap".equals(namespace) || !facts.hasConcept(concept)
                    ? concept
                    : namespace + ":" + concept;
            unitsNode.fieldNames().forEachRemaining(unit -> {
                JsonNode observations = unitsNode.get(unit);
                if (!observations.isArray()) return;
                // Collect, then sort by endDate descending (most recent first)
                List<CompanyFacts.Fact> batch = new ArrayList<>();
                for (JsonNode obs : observations) {
                    String endDate = obs.path("end").asText(null);
                    if (endDate == null) continue;
                    batch.add(new CompanyFacts.Fact(
                            obs.path("val").asDouble(0.0),
                            unit,
                            obs.path("fp").asText("") + "-" + obs.path("fy").asText(""),
                            endDate,
                            obs.path("form").asText(null),
                            obs.path("fy").asText(null),
                            obs.path("fp").asText(null)
                    ));
                }
                batch.sort((a, b) -> b.endDate().compareTo(a.endDate()));
                for (CompanyFacts.Fact f : batch) {
                    facts.addFact(storageKey, f);
                }
            });
            count[0]++;
        });
        return count[0];
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
