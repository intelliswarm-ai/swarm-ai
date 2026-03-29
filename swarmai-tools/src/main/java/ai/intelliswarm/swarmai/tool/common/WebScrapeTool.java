package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Web Scrape Tool — fetches a URL and extracts clean, structured content.
 *
 * Extracts: page title, main text, headings, links, tables (as markdown), and metadata.
 * Features: configurable selectors, private IP blocking, timeout, User-Agent.
 */
@Component
public class WebScrapeTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WebScrapeTool.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_BODY_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final String USER_AGENT = "SwarmAI/1.0 (Research Agent; +https://intelliswarm.ai)";

    // Private/internal IP ranges to block (SSRF prevention)
    private static final List<String> BLOCKED_HOST_PREFIXES = List.of(
        "127.", "10.", "192.168.", "172.16.", "172.17.", "172.18.", "172.19.",
        "172.20.", "172.21.", "172.22.", "172.23.", "172.24.", "172.25.",
        "172.26.", "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
        "0.", "169.254."
    );

    private static final Set<String> BLOCKED_HOSTS = Set.of(
        "localhost", "metadata.google.internal", "169.254.169.254"
    );

    @Override
    public String getFunctionName() {
        return "web_scrape";
    }

    @Override
    public String getDescription() {
        return "Fetch a web page URL and extract clean, structured content including title, text, headings, " +
               "links, and tables (as markdown). Useful for reading articles, documentation, and data pages.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String selector = (String) parameters.getOrDefault("selector", null);
        Boolean includeLinks = parameters.get("include_links") != null
            ? Boolean.valueOf(parameters.get("include_links").toString())
            : false;
        Boolean includeTables = parameters.get("include_tables") != null
            ? Boolean.valueOf(parameters.get("include_tables").toString())
            : true;

        logger.info("WebScrapeTool: Scraping URL: {} (selector: {}, links: {}, tables: {})",
            url, selector, includeLinks, includeTables);

        try {
            // 1. Validate URL
            String urlError = validateUrl(url);
            if (urlError != null) {
                return "Error: " + urlError;
            }

            // 2. Security check (SSRF prevention)
            String securityError = checkUrlSecurity(url);
            if (securityError != null) {
                return "Error: " + securityError;
            }

            // 3. Fetch and parse
            Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(CONNECT_TIMEOUT_MS)
                .maxBodySize(MAX_BODY_SIZE_BYTES)
                .followRedirects(true)
                .get();

            // 4. Extract content
            return buildResponse(url, doc, selector, includeLinks, includeTables);

        } catch (IOException e) {
            logger.error("Error scraping URL: {}", url, e);
            return "Error: Failed to fetch URL: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error scraping URL: {}", url, e);
            return "Error: " + e.getMessage();
        }
    }

    // Domains the LLM commonly hallucinates
    private static final Set<String> FAKE_DOMAINS = Set.of(
        "example.com", "example.org", "example.net",
        "www.example.com", "api.example.com",
        "placeholder.com", "test.com", "fake.com"
    );

    private String validateUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "URL is required";
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return "Only http and https URLs are supported";
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return "URL must have a valid host";
            }

            // Reject hallucinated/placeholder URLs
            if (FAKE_DOMAINS.contains(host.toLowerCase())) {
                return "Rejected: '" + host + "' is a placeholder domain. Use a REAL web page URL to scrape.";
            }

        } catch (URISyntaxException e) {
            return "Invalid URL format: " + e.getMessage();
        }
        return null;
    }

    private String checkUrlSecurity(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost().toLowerCase();

            // Block known internal hostnames
            if (BLOCKED_HOSTS.contains(host)) {
                return "Access denied: internal/private URLs are not allowed";
            }

            // Block private IP ranges
            for (String prefix : BLOCKED_HOST_PREFIXES) {
                if (host.startsWith(prefix)) {
                    return "Access denied: private IP addresses are not allowed";
                }
            }

            // Try to resolve and check for private IPs
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                    return "Access denied: URL resolves to a private/local address";
                }
            } catch (Exception e) {
                // DNS resolution failed — allow the request (Jsoup will handle the error)
                logger.debug("DNS resolution failed for {}: {}", host, e.getMessage());
            }

        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }
        return null;
    }

    private String buildResponse(String url, Document doc, String selector,
                                  boolean includeLinks, boolean includeTables) {
        StringBuilder response = new StringBuilder();

        // Metadata
        String title = doc.title();
        response.append("**URL:** ").append(url).append("\n");
        response.append("**Title:** ").append(title.isEmpty() ? "(no title)" : title).append("\n");

        // Meta description
        Element metaDesc = doc.selectFirst("meta[name=description]");
        if (metaDesc != null && !metaDesc.attr("content").isEmpty()) {
            response.append("**Description:** ").append(metaDesc.attr("content")).append("\n");
        }

        response.append("\n---\n\n");

        // If selector specified, extract only that
        if (selector != null && !selector.isEmpty()) {
            Elements selected = doc.select(selector);
            if (selected.isEmpty()) {
                response.append("No elements found matching selector: ").append(selector).append("\n");
            } else {
                response.append("**Selected content (").append(selector).append("):**\n\n");
                for (Element el : selected) {
                    response.append(cleanElementText(el)).append("\n\n");
                }
            }
            return truncateResponse(response.toString());
        }

        // Main content extraction
        Element mainContent = findMainContent(doc);

        // Headings
        Elements headings = mainContent.select("h1, h2, h3, h4");
        if (!headings.isEmpty()) {
            response.append("## Headings\n\n");
            for (Element h : headings) {
                int level = Integer.parseInt(h.tagName().substring(1));
                response.append("#".repeat(level)).append(" ").append(h.text().trim()).append("\n");
            }
            response.append("\n");
        }

        // Main text content
        response.append("## Content\n\n");
        String textContent = extractReadableText(mainContent);
        response.append(textContent).append("\n\n");

        // Tables (as markdown)
        if (includeTables) {
            String tables = extractTables(mainContent);
            if (!tables.isEmpty()) {
                response.append("## Tables\n\n").append(tables).append("\n");
            }
        }

        // Links
        if (includeLinks) {
            Elements links = mainContent.select("a[href]");
            if (!links.isEmpty()) {
                response.append("## Links\n\n");
                Set<String> seen = new HashSet<>();
                int linkCount = 0;
                for (Element link : links) {
                    String href = link.absUrl("href");
                    String text = link.text().trim();
                    if (!href.isEmpty() && !text.isEmpty() && seen.add(href) && linkCount < 20) {
                        response.append("- [").append(text).append("](").append(href).append(")\n");
                        linkCount++;
                    }
                }
                response.append("\n");
            }
        }

        return truncateResponse(response.toString());
    }

    /**
     * Finds the main content area of the page, avoiding nav/header/footer/sidebar.
     */
    private Element findMainContent(Document doc) {
        // Try common content selectors in priority order
        String[] contentSelectors = {
            "article", "main", "[role=main]",
            ".post-content", ".article-content", ".entry-content",
            ".content", "#content", "#main-content",
            ".post-body", ".article-body"
        };

        for (String sel : contentSelectors) {
            Element content = doc.selectFirst(sel);
            if (content != null && content.text().length() > 200) {
                return content;
            }
        }

        // Fallback: use body but remove nav/header/footer/sidebar
        Element body = doc.body();
        if (body == null) return doc;

        body.select("nav, header, footer, aside, .sidebar, .nav, .menu, .footer, .header, " +
                    "script, style, noscript, iframe, .ad, .ads, .advertisement").remove();
        return body;
    }

    /**
     * Extracts readable text preserving paragraph structure.
     */
    private String extractReadableText(Element content) {
        StringBuilder text = new StringBuilder();

        for (Element el : content.children()) {
            String tag = el.tagName();

            if (tag.equals("p") || tag.equals("div") || tag.matches("h[1-6]")) {
                String elText = el.text().trim();
                if (!elText.isEmpty() && elText.length() > 20) {
                    text.append(elText).append("\n\n");
                }
            } else if (tag.equals("ul") || tag.equals("ol")) {
                for (Element li : el.select("> li")) {
                    text.append("- ").append(li.text().trim()).append("\n");
                }
                text.append("\n");
            } else if (tag.equals("blockquote")) {
                text.append("> ").append(el.text().trim()).append("\n\n");
            } else if (tag.equals("pre") || tag.equals("code")) {
                text.append("```\n").append(el.text().trim()).append("\n```\n\n");
            }
        }

        // If structured extraction yielded too little, fall back to full text
        if (text.length() < 200) {
            String fullText = content.text();
            // Break into sentences for readability
            return fullText.replaceAll("(?<=[.!?])\\s+", "\n\n")
                          .substring(0, Math.min(fullText.length(), getMaxResponseLength()));
        }

        return text.toString();
    }

    private String cleanElementText(Element el) {
        return el.text().trim();
    }

    /**
     * Extracts HTML tables and converts to markdown format.
     */
    private String extractTables(Element content) {
        Elements tables = content.select("table");
        if (tables.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        int tableCount = 0;

        for (Element table : tables) {
            Elements rows = table.select("tr");
            if (rows.size() < 2) continue;

            for (Element row : rows) {
                Elements cells = row.select("td, th");
                if (cells.isEmpty()) continue;

                result.append("| ");
                for (Element cell : cells) {
                    String cellText = cell.text().trim().replaceAll("\\s+", " ");
                    if (cellText.length() > 50) cellText = cellText.substring(0, 47) + "...";
                    result.append(cellText).append(" | ");
                }
                result.append("\n");

                if (!row.select("th").isEmpty()) {
                    result.append("|");
                    for (int i = 0; i < cells.size(); i++) result.append("---|");
                    result.append("\n");
                }
            }
            result.append("\n");

            tableCount++;
            if (tableCount >= 3) break; // Limit tables
        }

        return result.toString();
    }

    private String truncateResponse(String response) {
        int max = getMaxResponseLength();
        if (response.length() > max) {
            return response.substring(0, max) + "\n\n[... content truncated at " + max + " chars ...]";
        }
        return response;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> url = new HashMap<>();
        url.put("type", "string");
        url.put("description", "The URL to scrape (must be http or https)");
        properties.put("url", url);

        Map<String, Object> selector = new HashMap<>();
        selector.put("type", "string");
        selector.put("description", "Optional CSS selector to extract specific elements (e.g., 'article', '.content', '#main')");
        properties.put("selector", selector);

        Map<String, Object> includeLinks = new HashMap<>();
        includeLinks.put("type", "boolean");
        includeLinks.put("description", "Include extracted links in output (default: false)");
        includeLinks.put("default", false);
        properties.put("include_links", includeLinks);

        Map<String, Object> includeTables = new HashMap<>();
        includeTables.put("type", "boolean");
        includeTables.put("description", "Include tables as markdown in output (default: true)");
        includeTables.put("default", true);
        properties.put("include_tables", includeTables);

        schema.put("properties", properties);
        schema.put("required", new String[]{"url"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public String getTriggerWhen() {
        return "User needs to fetch and extract content from a specific web page URL.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs general web search results (use web_search), or data is available locally.";
    }

    @Override
    public String getCategory() {
        return "web";
    }

    @Override
    public List<String> getTags() {
        return List.of("scrape", "html", "extract", "webpage");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Structured markdown with page title, metadata, headings, content text, tables, and links"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 15000; // Web pages can be large
    }

    @Override
    public boolean isCacheable() {
        return true; // Cache scraped pages to avoid re-fetching
    }

    // Request record for Spring AI function binding
    public record Request(String url, String selector, Boolean includeLinks, Boolean includeTables) {}
}
