package ai.intelliswarm.swarmai.examples.stock.tools;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.http.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Component
public class WebSearchTool implements BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    
    // API Keys - these should be externalized to application.yml in production
    @Value("${stock.search.alphavantage.api-key:demo}")
    private String alphaVantageApiKey;
    
    @Value("${stock.search.newsapi.api-key:}")
    private String newsApiKey;
    
    @Value("${stock.search.polygon.api-key:}")
    private String polygonApiKey;
    
    @Value("${stock.search.finnhub.api-key:}")
    private String finnhubApiKey;
    
    @Value("${stock.search.google.api-key:}")
    private String googleApiKey;
    
    @Value("${stock.search.google.search-engine-id:}")
    private String googleSearchEngineId;
    
    @Value("${stock.search.bing.api-key:}")
    private String bingApiKey;
    
    // API Endpoints
    private static final String ALPHA_VANTAGE_BASE = "https://www.alphavantage.co/query";
    private static final String NEWS_API_BASE = "https://newsapi.org/v2";
    private static final String POLYGON_BASE = "https://api.polygon.io/v2";
    private static final String FINNHUB_BASE = "https://finnhub.io/api/v1";
    private static final String GOOGLE_SEARCH_BASE = "https://www.googleapis.com/customsearch/v1";
    private static final String BING_SEARCH_BASE = "https://api.bing.microsoft.com/v7.0/search";
    private static final String SEC_RSS_FEED = "https://www.sec.gov/news/pressreleases.rss";
    
    public WebSearchTool() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newFixedThreadPool(4);
    }
    
    @Override
    public String getFunctionName() {
        return "web_search";
    }
    
    @Override
    public String getDescription() {
        return "Performs comprehensive web searches for stock-related information across multiple sources including news, market data, SEC filings, and social media. Input should be a search query string.";
    }
    
    @Override
    public Object execute(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        logger.info("🔍 WebSearchTool: Searching for: {}", query);
        
        try {
            // Parse the query to extract ticker symbols if present
            String ticker = extractTicker(query);
            
            // Create a comprehensive search result by aggregating from multiple sources
            Map<String, Object> searchResults = new HashMap<>();
            searchResults.put("query", query);
            searchResults.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            searchResults.put("ticker", ticker);
            
            // Execute searches in parallel for better performance
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            
            // 1. Web Search (Google/Bing)
            futures.add(CompletableFuture.supplyAsync(() -> searchWeb(query, ticker), executorService));
            
            // 2. Financial News Search
            futures.add(CompletableFuture.supplyAsync(() -> searchFinancialNews(query, ticker), executorService));
            
            // 3. Market Data Search (if ticker is detected)
            if (ticker != null && !ticker.isEmpty()) {
                futures.add(CompletableFuture.supplyAsync(() -> searchMarketData(ticker), executorService));
                futures.add(CompletableFuture.supplyAsync(() -> searchCompanyOverview(ticker), executorService));
            }
            
            // 4. SEC Filings and Announcements
            futures.add(CompletableFuture.supplyAsync(() -> searchSECData(query, ticker), executorService));
            
            // 5. Social Media Sentiment (simulated)
            futures.add(CompletableFuture.supplyAsync(() -> searchSocialMedia(query, ticker), executorService));
            
            // Wait for all searches to complete and aggregate results
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            allFutures.get(); // Wait for completion
            
            // Collect results
            List<Map<String, Object>> allResults = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
            
            // Format and return comprehensive results
            return formatSearchResults(searchResults, allResults, query, ticker);
            
        } catch (Exception e) {
            logger.error("Error performing web search", e);
            return "Error performing web search: " + e.getMessage();
        }
    }
    
    private String extractTicker(String query) {
        // Simple ticker extraction - looks for uppercase sequences of 1-5 letters
        String[] words = query.split("\\s+");
        for (String word : words) {
            // Remove common punctuation
            word = word.replaceAll("[,.:;!?]", "");
            // Check if it looks like a ticker (1-5 uppercase letters)
            if (word.matches("^[A-Z]{1,5}$")) {
                return word;
            }
        }
        // Also check for common patterns like $AAPL or AAPL: or (AAPL)
        if (query.matches(".*\\$([A-Z]{1,5}).*")) {
            return query.replaceAll(".*\\$([A-Z]{1,5}).*", "$1");
        }
        if (query.matches(".*\\(([A-Z]{1,5})\\).*")) {
            return query.replaceAll(".*\\(([A-Z]{1,5})\\).*", "$1");
        }
        return null;
    }
    
    private Map<String, Object> searchWeb(String query, String ticker) {
        Map<String, Object> webResults = new HashMap<>();
        webResults.put("source", "Web Search");
        
        try {
            List<Map<String, Object>> searchResults = new ArrayList<>();
            
            // Try Google Custom Search if configured
            if (googleApiKey != null && !googleApiKey.isEmpty() && 
                googleSearchEngineId != null && !googleSearchEngineId.isEmpty()) {
                searchResults.addAll(searchGoogle(query));
            }
            
            // Try Bing Search if configured
            if (bingApiKey != null && !bingApiKey.isEmpty()) {
                searchResults.addAll(searchBing(query));
            }
            
            // If no search APIs are configured, provide informative response
            if (searchResults.isEmpty()) {
                Map<String, Object> info = new HashMap<>();
                info.put("title", "Web Search");
                info.put("description", String.format("Would search the web for: %s", query));
                info.put("note", "Configure Google Custom Search or Bing Search API for real web search results");
                info.put("search_targets", Arrays.asList(
                    "Financial news websites and blogs",
                    "Company investor relations pages", 
                    "Analyst reports and research",
                    "Market data and trading platforms",
                    "Regulatory filing announcements",
                    "Earnings call transcripts"
                ));
                if (ticker != null) {
                    info.put("ticker_specific_searches", Arrays.asList(
                        String.format("%s earnings report", ticker),
                        String.format("%s stock analysis", ticker),
                        String.format("%s company news", ticker),
                        String.format("%s analyst ratings", ticker)
                    ));
                }
                searchResults.add(info);
            }
            
            webResults.put("results", searchResults);
            webResults.put("count", searchResults.size());
            
        } catch (Exception e) {
            logger.error("Error performing web search", e);
            webResults.put("error", e.getMessage());
        }
        
        return webResults;
    }
    
    private List<Map<String, Object>> searchGoogle(String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        try {
            String encodedQuery = URLEncoder.encode(query + " finance stock market", StandardCharsets.UTF_8);
            String url = GOOGLE_SEARCH_BASE 
                + "?key=" + googleApiKey
                + "&cx=" + googleSearchEngineId
                + "&q=" + encodedQuery
                + "&num=5"
                + "&safe=active";
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode items = root.path("items");
                
                for (JsonNode item : items) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("title", item.path("title").asText());
                    result.put("snippet", item.path("snippet").asText());
                    result.put("link", item.path("link").asText());
                    result.put("displayLink", item.path("displayLink").asText());
                    result.put("source", "Google");
                    results.add(result);
                    
                    if (results.size() >= 5) break;
                }
            }
        } catch (Exception e) {
            logger.debug("Google Search failed: {}", e.getMessage());
        }
        
        return results;
    }
    
    private List<Map<String, Object>> searchBing(String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        try {
            String encodedQuery = URLEncoder.encode(query + " finance stock market", StandardCharsets.UTF_8);
            String url = BING_SEARCH_BASE + "?q=" + encodedQuery + "&count=5";
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Ocp-Apim-Subscription-Key", bingApiKey);
            headers.set("Accept", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode webPages = root.path("webPages");
                JsonNode values = webPages.path("value");
                
                for (JsonNode value : values) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("title", value.path("name").asText());
                    result.put("snippet", value.path("snippet").asText());
                    result.put("link", value.path("url").asText());
                    result.put("displayLink", value.path("displayUrl").asText());
                    result.put("source", "Bing");
                    results.add(result);
                    
                    if (results.size() >= 5) break;
                }
            }
        } catch (Exception e) {
            logger.debug("Bing Search failed: {}", e.getMessage());
        }
        
        return results;
    }
    
    private Map<String, Object> searchFinancialNews(String query, String ticker) {
        Map<String, Object> newsResults = new HashMap<>();
        newsResults.put("source", "Financial News");
        
        try {
            List<Map<String, Object>> articles = new ArrayList<>();
            
            // Try NewsAPI if we have an API key
            if (newsApiKey != null && !newsApiKey.isEmpty()) {
                articles.addAll(searchNewsAPI(query));
            }
            
            // Try Finnhub news if we have an API key and ticker
            if (finnhubApiKey != null && !finnhubApiKey.isEmpty() && ticker != null) {
                articles.addAll(searchFinnhubNews(ticker));
            }
            
            // If no API keys are configured, provide informative response
            if (articles.isEmpty()) {
                Map<String, Object> info = new HashMap<>();
                info.put("title", "Financial News Search");
                info.put("description", String.format("Would search for news about: %s", query));
                info.put("note", "Configure NewsAPI or Finnhub API keys for real news data");
                info.put("suggested_sources", Arrays.asList(
                    "Reuters Finance", "Bloomberg", "CNBC", "Wall Street Journal",
                    "Financial Times", "MarketWatch", "Yahoo Finance"
                ));
                articles.add(info);
            }
            
            newsResults.put("articles", articles);
            newsResults.put("count", articles.size());
            
        } catch (Exception e) {
            logger.error("Error searching financial news", e);
            newsResults.put("error", e.getMessage());
        }
        
        return newsResults;
    }
    
    private List<Map<String, Object>> searchNewsAPI(String query) {
        List<Map<String, Object>> articles = new ArrayList<>();
        
        try {
            String url = NEWS_API_BASE + "/everything"
                + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&domains=reuters.com,bloomberg.com,wsj.com,ft.com,cnbc.com"
                + "&sortBy=relevancy"
                + "&apiKey=" + newsApiKey;
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode articlesNode = root.path("articles");
                
                for (JsonNode article : articlesNode) {
                    Map<String, Object> articleMap = new HashMap<>();
                    articleMap.put("title", article.path("title").asText());
                    articleMap.put("description", article.path("description").asText());
                    articleMap.put("url", article.path("url").asText());
                    articleMap.put("source", article.path("source").path("name").asText());
                    articleMap.put("publishedAt", article.path("publishedAt").asText());
                    articles.add(articleMap);
                    
                    if (articles.size() >= 5) break; // Limit to 5 articles
                }
            }
        } catch (Exception e) {
            logger.debug("NewsAPI search failed: {}", e.getMessage());
        }
        
        return articles;
    }
    
    private List<Map<String, Object>> searchFinnhubNews(String ticker) {
        List<Map<String, Object>> articles = new ArrayList<>();
        
        try {
            String url = FINNHUB_BASE + "/company-news"
                + "?symbol=" + ticker
                + "&from=" + LocalDateTime.now().minusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "&to=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "&token=" + finnhubApiKey;
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                for (JsonNode article : root) {
                    Map<String, Object> articleMap = new HashMap<>();
                    articleMap.put("title", article.path("headline").asText());
                    articleMap.put("summary", article.path("summary").asText());
                    articleMap.put("url", article.path("url").asText());
                    articleMap.put("source", article.path("source").asText());
                    articleMap.put("datetime", article.path("datetime").asText());
                    articles.add(articleMap);
                    
                    if (articles.size() >= 5) break;
                }
            }
        } catch (Exception e) {
            logger.debug("Finnhub news search failed: {}", e.getMessage());
        }
        
        return articles;
    }
    
    private Map<String, Object> searchMarketData(String ticker) {
        Map<String, Object> marketData = new HashMap<>();
        marketData.put("source", "Market Data");
        marketData.put("ticker", ticker);
        
        try {
            // Use Alpha Vantage for market data
            if ("demo".equals(alphaVantageApiKey) || (alphaVantageApiKey != null && !alphaVantageApiKey.isEmpty())) {
                Map<String, Object> quote = getAlphaVantageQuote(ticker);
                if (!quote.isEmpty()) {
                    marketData.putAll(quote);
                }
            }
            
            // If no real data available, provide structured mock data
            if (!marketData.containsKey("price")) {
                marketData.put("price", "Latest price data");
                marketData.put("change", "Daily change percentage");
                marketData.put("volume", "Trading volume");
                marketData.put("marketCap", "Market capitalization");
                marketData.put("pe_ratio", "Price-to-earnings ratio");
                marketData.put("52_week_high", "52-week high price");
                marketData.put("52_week_low", "52-week low price");
                marketData.put("note", "Configure Alpha Vantage API key for real market data");
            }
            
        } catch (Exception e) {
            logger.error("Error fetching market data", e);
            marketData.put("error", e.getMessage());
        }
        
        return marketData;
    }
    
    private Map<String, Object> getAlphaVantageQuote(String ticker) {
        Map<String, Object> quote = new HashMap<>();
        
        try {
            String url = ALPHA_VANTAGE_BASE
                + "?function=GLOBAL_QUOTE"
                + "&symbol=" + ticker
                + "&apikey=" + alphaVantageApiKey;
            
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                JsonNode globalQuote = root.path("Global Quote");
                
                if (!globalQuote.isMissingNode()) {
                    quote.put("symbol", globalQuote.path("01. symbol").asText());
                    quote.put("price", globalQuote.path("05. price").asText());
                    quote.put("change", globalQuote.path("09. change").asText());
                    quote.put("changePercent", globalQuote.path("10. change percent").asText());
                    quote.put("volume", globalQuote.path("06. volume").asText());
                    quote.put("latestTradingDay", globalQuote.path("07. latest trading day").asText());
                    quote.put("previousClose", globalQuote.path("08. previous close").asText());
                    quote.put("open", globalQuote.path("02. open").asText());
                    quote.put("high", globalQuote.path("03. high").asText());
                    quote.put("low", globalQuote.path("04. low").asText());
                }
            }
        } catch (Exception e) {
            logger.debug("Alpha Vantage quote failed: {}", e.getMessage());
        }
        
        return quote;
    }
    
    private Map<String, Object> searchCompanyOverview(String ticker) {
        Map<String, Object> overview = new HashMap<>();
        overview.put("source", "Company Overview");
        overview.put("ticker", ticker);
        
        try {
            // Use Alpha Vantage for company overview
            if ("demo".equals(alphaVantageApiKey) || (alphaVantageApiKey != null && !alphaVantageApiKey.isEmpty())) {
                String url = ALPHA_VANTAGE_BASE
                    + "?function=OVERVIEW"
                    + "&symbol=" + ticker
                    + "&apikey=" + alphaVantageApiKey;
                
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    
                    if (root.has("Symbol")) {
                        overview.put("name", root.path("Name").asText());
                        overview.put("description", root.path("Description").asText());
                        overview.put("exchange", root.path("Exchange").asText());
                        overview.put("sector", root.path("Sector").asText());
                        overview.put("industry", root.path("Industry").asText());
                        overview.put("marketCap", root.path("MarketCapitalization").asText());
                        overview.put("peRatio", root.path("PERatio").asText());
                        overview.put("dividendYield", root.path("DividendYield").asText());
                        overview.put("eps", root.path("EPS").asText());
                        overview.put("beta", root.path("Beta").asText());
                        overview.put("52WeekHigh", root.path("52WeekHigh").asText());
                        overview.put("52WeekLow", root.path("52WeekLow").asText());
                    }
                }
            }
            
            // If no real data, provide structure
            if (!overview.containsKey("name")) {
                overview.put("name", "Company name");
                overview.put("sector", "Business sector");
                overview.put("industry", "Industry classification");
                overview.put("description", "Company description");
                overview.put("note", "Configure Alpha Vantage API key for real company data");
            }
            
        } catch (Exception e) {
            logger.error("Error fetching company overview", e);
            overview.put("error", e.getMessage());
        }
        
        return overview;
    }
    
    private Map<String, Object> searchSECData(String query, String ticker) {
        Map<String, Object> secData = new HashMap<>();
        secData.put("source", "SEC EDGAR");
        
        try {
            List<Map<String, Object>> filings = new ArrayList<>();
            
            // Reference to SEC EDGAR search
            Map<String, Object> edgarInfo = new HashMap<>();
            edgarInfo.put("description", "SEC EDGAR Database Search");
            
            if (ticker != null) {
                edgarInfo.put("ticker", ticker);
                edgarInfo.put("search_url", String.format("https://www.sec.gov/edgar/browse/?CIK=%s", ticker));
                edgarInfo.put("recent_filings", Arrays.asList(
                    "Latest 10-K (Annual Report)",
                    "Latest 10-Q (Quarterly Report)",
                    "Recent 8-K (Current Reports)",
                    "Proxy Statements (DEF 14A)",
                    "Insider Trading (Form 4)"
                ));
            } else {
                edgarInfo.put("query", query);
                edgarInfo.put("search_url", "https://www.sec.gov/edgar/search/");
            }
            
            edgarInfo.put("note", "Use SECFilingsTool for detailed SEC filing analysis");
            filings.add(edgarInfo);
            
            secData.put("filings", filings);
            
        } catch (Exception e) {
            logger.error("Error searching SEC data", e);
            secData.put("error", e.getMessage());
        }
        
        return secData;
    }
    
    private Map<String, Object> searchSocialMedia(String query, String ticker) {
        Map<String, Object> socialData = new HashMap<>();
        socialData.put("source", "Social Media Sentiment");
        
        try {
            // Social media sentiment would require Twitter API, Reddit API, etc.
            // For now, provide structured response
            Map<String, Object> sentiment = new HashMap<>();
            
            if (ticker != null) {
                sentiment.put("ticker", ticker);
                sentiment.put("mentions", "Number of mentions across platforms");
                sentiment.put("sentiment_score", "Overall sentiment (-1 to 1)");
                sentiment.put("trending", "Whether ticker is trending");
            }
            
            sentiment.put("platforms", Arrays.asList(
                "Twitter/X - Real-time market sentiment",
                "Reddit (r/wallstreetbets, r/stocks) - Retail investor discussions",
                "StockTwits - Dedicated stock discussion platform",
                "LinkedIn - Professional analysis and news"
            ));
            
            sentiment.put("key_topics", Arrays.asList(
                "Earnings expectations",
                "Product launches",
                "Management changes",
                "Market trends",
                "Regulatory news"
            ));
            
            sentiment.put("note", "Configure Twitter/Reddit APIs for real social sentiment data");
            
            socialData.put("sentiment", sentiment);
            
        } catch (Exception e) {
            logger.error("Error searching social media", e);
            socialData.put("error", e.getMessage());
        }
        
        return socialData;
    }
    
    private String formatSearchResults(Map<String, Object> searchResults, 
                                      List<Map<String, Object>> allResults,
                                      String query, String ticker) {
        StringBuilder report = new StringBuilder();
        
        // Header
        report.append(String.format("# Web Search Results for: \"%s\"\n\n", query));
        if (ticker != null) {
            report.append(String.format("**Detected Ticker:** %s\n\n", ticker));
        }
        report.append(String.format("**Search Time:** %s\n\n", searchResults.get("timestamp")));
        
        // Process each result source
        for (Map<String, Object> result : allResults) {
            String source = (String) result.get("source");
            report.append(String.format("## %s\n\n", source));
            
            if (result.containsKey("error")) {
                report.append(String.format("Error: %s\n\n", result.get("error")));
                continue;
            }
            
            switch (source) {
                case "Web Search":
                    formatWebSearchResults(report, result);
                    break;
                case "Financial News":
                    formatNewsResults(report, result);
                    break;
                case "Market Data":
                    formatMarketData(report, result);
                    break;
                case "Company Overview":
                    formatCompanyOverview(report, result);
                    break;
                case "SEC EDGAR":
                    formatSECResults(report, result);
                    break;
                case "Social Media Sentiment":
                    formatSocialResults(report, result);
                    break;
            }
        }
        
        // Add search recommendations
        report.append("## Search Recommendations\n\n");
        report.append("For more detailed information, consider:\n");
        report.append("- Using the SECFilingsTool for specific SEC filing analysis\n");
        report.append("- Checking earnings call transcripts\n");
        report.append("- Reviewing analyst reports and price targets\n");
        report.append("- Monitoring options flow and institutional holdings\n");
        report.append("- Tracking insider transactions\n\n");
        
        // Add API configuration note if needed
        if (!hasConfiguredAPIs()) {
            report.append("## Configuration Note\n\n");
            report.append("To enable real-time data, configure the following API keys in application.yml:\n");
            report.append("- Alpha Vantage API (free tier available)\n");
            report.append("- NewsAPI (free tier available)\n");
            report.append("- Finnhub (free tier available)\n");
            report.append("- Polygon.io (optional)\n");
        }
        
        return report.toString();
    }
    
    private void formatWebSearchResults(StringBuilder report, Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
        
        if (results != null && !results.isEmpty()) {
            for (Map<String, Object> webResult : results) {
                if (webResult.containsKey("title") && webResult.containsKey("link")) {
                    // Real search result from Google/Bing
                    report.append(String.format("### %s\n", webResult.get("title")));
                    if (webResult.containsKey("snippet")) {
                        report.append(String.format("%s\n", webResult.get("snippet")));
                    }
                    if (webResult.containsKey("source")) {
                        report.append(String.format("**Source:** %s", webResult.get("source")));
                    }
                    if (webResult.containsKey("displayLink")) {
                        report.append(String.format(" (%s)", webResult.get("displayLink")));
                    }
                    report.append(String.format("\n**Link:** %s", webResult.get("link")));
                    report.append("\n\n");
                } else {
                    // Configuration info response
                    for (Map.Entry<String, Object> entry : webResult.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            report.append(String.format("**%s:**\n", 
                                entry.getKey().replaceAll("_", " ").toUpperCase()));
                            for (Object item : (List<?>) entry.getValue()) {
                                report.append(String.format("- %s\n", item));
                            }
                        } else {
                            report.append(String.format("**%s:** %s\n", entry.getKey(), entry.getValue()));
                        }
                    }
                    report.append("\n");
                }
            }
        }
    }
    
    private void formatNewsResults(StringBuilder report, Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articles = (List<Map<String, Object>>) result.get("articles");
        
        if (articles != null && !articles.isEmpty()) {
            for (Map<String, Object> article : articles) {
                if (article.containsKey("title")) {
                    report.append(String.format("### %s\n", article.get("title")));
                    if (article.containsKey("description")) {
                        report.append(String.format("%s\n", article.get("description")));
                    }
                    if (article.containsKey("source")) {
                        report.append(String.format("**Source:** %s", article.get("source")));
                    }
                    if (article.containsKey("publishedAt")) {
                        report.append(String.format(" | **Date:** %s", article.get("publishedAt")));
                    }
                    if (article.containsKey("url")) {
                        report.append(String.format("\n**Link:** %s", article.get("url")));
                    }
                    report.append("\n\n");
                } else {
                    // Handle info response
                    for (Map.Entry<String, Object> entry : article.entrySet()) {
                        if (entry.getValue() instanceof List) {
                            report.append(String.format("**%s:**\n", entry.getKey()));
                            for (Object item : (List<?>) entry.getValue()) {
                                report.append(String.format("- %s\n", item));
                            }
                        } else {
                            report.append(String.format("**%s:** %s\n", entry.getKey(), entry.getValue()));
                        }
                    }
                    report.append("\n");
                }
            }
        }
    }
    
    private void formatMarketData(StringBuilder report, Map<String, Object> result) {
        if (result.containsKey("price")) {
            report.append("### Current Market Data\n\n");
            report.append(String.format("- **Price:** %s\n", result.get("price")));
            if (result.containsKey("change")) {
                report.append(String.format("- **Change:** %s (%s)\n", 
                    result.get("change"), result.get("changePercent")));
            }
            if (result.containsKey("volume")) {
                report.append(String.format("- **Volume:** %s\n", result.get("volume")));
            }
            if (result.containsKey("high")) {
                report.append(String.format("- **Day Range:** %s - %s\n", 
                    result.get("low"), result.get("high")));
            }
            if (result.containsKey("52_week_high") || result.containsKey("52WeekHigh")) {
                String high = (String) result.getOrDefault("52WeekHigh", result.get("52_week_high"));
                String low = (String) result.getOrDefault("52WeekLow", result.get("52_week_low"));
                report.append(String.format("- **52 Week Range:** %s - %s\n", low, high));
            }
        } else {
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                if (!entry.getKey().equals("source") && !entry.getKey().equals("ticker")) {
                    report.append(String.format("- **%s:** %s\n", entry.getKey(), entry.getValue()));
                }
            }
        }
        report.append("\n");
    }
    
    private void formatCompanyOverview(StringBuilder report, Map<String, Object> result) {
        if (result.containsKey("name")) {
            report.append(String.format("### %s\n\n", result.get("name")));
            if (result.containsKey("description")) {
                report.append(String.format("%s\n\n", result.get("description")));
            }
            report.append("**Key Metrics:**\n");
            String[] metrics = {"sector", "industry", "marketCap", "peRatio", "dividendYield", "eps", "beta"};
            for (String metric : metrics) {
                if (result.containsKey(metric) && result.get(metric) != null && !result.get(metric).toString().isEmpty()) {
                    report.append(String.format("- **%s:** %s\n", 
                        metric.replaceAll("([A-Z])", " $1").trim(), result.get(metric)));
                }
            }
        } else {
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                if (!entry.getKey().equals("source") && !entry.getKey().equals("ticker")) {
                    report.append(String.format("- **%s:** %s\n", entry.getKey(), entry.getValue()));
                }
            }
        }
        report.append("\n");
    }
    
    private void formatSECResults(StringBuilder report, Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filings = (List<Map<String, Object>>) result.get("filings");
        
        if (filings != null) {
            for (Map<String, Object> filing : filings) {
                for (Map.Entry<String, Object> entry : filing.entrySet()) {
                    if (entry.getValue() instanceof List) {
                        report.append(String.format("**%s:**\n", entry.getKey()));
                        for (Object item : (List<?>) entry.getValue()) {
                            report.append(String.format("- %s\n", item));
                        }
                    } else {
                        report.append(String.format("**%s:** %s\n", entry.getKey(), entry.getValue()));
                    }
                }
            }
        }
        report.append("\n");
    }
    
    private void formatSocialResults(StringBuilder report, Map<String, Object> result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sentiment = (Map<String, Object>) result.get("sentiment");
        
        if (sentiment != null) {
            for (Map.Entry<String, Object> entry : sentiment.entrySet()) {
                if (entry.getValue() instanceof List) {
                    report.append(String.format("**%s:**\n", 
                        entry.getKey().replaceAll("_", " ").toUpperCase()));
                    for (Object item : (List<?>) entry.getValue()) {
                        report.append(String.format("- %s\n", item));
                    }
                } else {
                    report.append(String.format("**%s:** %s\n", 
                        entry.getKey().replaceAll("_", " "), entry.getValue()));
                }
            }
        }
        report.append("\n");
    }
    
    private boolean hasConfiguredAPIs() {
        return (newsApiKey != null && !newsApiKey.isEmpty()) ||
               (finnhubApiKey != null && !finnhubApiKey.isEmpty()) ||
               (polygonApiKey != null && !polygonApiKey.isEmpty()) ||
               (googleApiKey != null && !googleApiKey.isEmpty()) ||
               (bingApiKey != null && !bingApiKey.isEmpty()) ||
               (!alphaVantageApiKey.equals("demo") && !alphaVantageApiKey.isEmpty());
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> queryParam = new HashMap<>();
        queryParam.put("type", "string");
        queryParam.put("description", "Search query for stock information, news, and market data. Can include ticker symbols.");
        properties.put("query", queryParam);
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"query"});
        
        return schema;
    }
    
    @Override
    public boolean isAsync() {
        return false;
    }
    
    // Request record for Spring AI function binding
    public record Request(String query) {}
}