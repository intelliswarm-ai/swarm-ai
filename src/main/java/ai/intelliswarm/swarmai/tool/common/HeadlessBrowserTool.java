package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTable;
import org.htmlunit.html.HtmlTableRow;
import org.htmlunit.html.HtmlTableCell;
import org.htmlunit.html.DomNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Headless Browser Tool — renders full web pages with JavaScript execution using HtmlUnit.
 *
 * Unlike http_request (raw HTTP) or web_scrape (Jsoup HTML parsing), this tool:
 * - Executes JavaScript (SPAs, dynamic content, AJAX-loaded data)
 * - Follows redirects and handles cookies
 * - Renders pages like a real browser
 * - Extracts clean text, tables (as markdown), and links
 *
 * Primary use cases:
 * - Scraping financial data from Yahoo Finance, Google Finance
 * - Fetching company information from corporate websites
 * - Accessing any page that requires JS rendering
 * - Replacing Wikipedia API as the default data source
 *
 * Pure Java (HtmlUnit) — no Chrome/Chromium binary required.
 */
@Component
public class HeadlessBrowserTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(HeadlessBrowserTool.class);

    private static final int TIMEOUT_MS = 30_000;
    private static final int JS_WAIT_MS = 5_000;
    private static final int MAX_OUTPUT_LENGTH = 12_000;

    @Override
    public String getFunctionName() {
        return "browse";
    }

    @Override
    public String getDescription() {
        return "Headless browser that renders web pages with JavaScript support. " +
               "Fetches a URL, executes JavaScript, and returns clean text content with tables as markdown. " +
               "Use this for scraping financial data (Yahoo Finance, Google Finance), company websites, " +
               "news articles, or any page with dynamic content. " +
               "Parameters: url (required), selector (optional CSS selector to extract specific content), " +
               "waitMs (optional JS wait time in ms, default 5000).";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        String selector = (String) parameters.get("selector");
        Integer waitMs = parameters.get("waitMs") != null
            ? Integer.parseInt(parameters.get("waitMs").toString())
            : JS_WAIT_MS;

        logger.info("HeadlessBrowserTool: url='{}', selector='{}', waitMs={}", url, selector, waitMs);

        if (url == null || url.isBlank()) {
            return "Error: URL is required. Provide a url parameter.";
        }

        // Basic URL validation
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        try (WebClient webClient = createWebClient()) {
            // Fetch and render the page
            HtmlPage page = webClient.getPage(url);

            // Wait for JavaScript to execute
            if (waitMs > 0) {
                webClient.waitForBackgroundJavaScript(waitMs);
            }

            StringBuilder output = new StringBuilder();
            output.append("**URL:** ").append(url).append("\n");
            output.append("**Title:** ").append(page.getTitleText()).append("\n\n");

            // Extract content based on selector or full page
            if (selector != null && !selector.isBlank()) {
                // CSS selector mode — extract specific elements
                var elements = page.querySelectorAll(selector);
                if (elements.isEmpty()) {
                    output.append("No elements matched selector: ").append(selector).append("\n");
                } else {
                    for (var element : elements) {
                        output.append(extractNodeText(element)).append("\n\n");
                    }
                }
            } else {
                // Full page mode — extract structured content
                String pageText = extractPageContent(page);
                output.append(pageText);
            }

            // Truncate if too long
            String result = output.toString();
            if (result.length() > MAX_OUTPUT_LENGTH) {
                result = result.substring(0, MAX_OUTPUT_LENGTH) + "\n\n[... content truncated at " + MAX_OUTPUT_LENGTH + " chars ...]";
            }

            return result;

        } catch (Exception e) {
            logger.warn("HeadlessBrowserTool failed for '{}': {}", url, e.getMessage());
            return "Error: Failed to browse " + url + ": " + e.getMessage();
        }
    }

    private WebClient createWebClient() {
        WebClient webClient = new WebClient();
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setCssEnabled(false);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setTimeout(TIMEOUT_MS);
        webClient.getOptions().setDownloadImages(false);
        webClient.getOptions().setPrintContentOnFailingStatusCode(false);
        // Suppress ALL HtmlUnit logging noise (JS errors, CSS warnings, cookie issues)
        java.util.logging.Logger.getLogger("org.htmlunit").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.javascript").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.html").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.css").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.cyberneko").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.corejs").setLevel(java.util.logging.Level.OFF);
        java.util.logging.Logger.getLogger("org.htmlunit.IncorrectnessListenerImpl").setLevel(java.util.logging.Level.OFF);
        // Silence the DefaultJavaScriptErrorListener completely
        webClient.setJavaScriptErrorListener(new org.htmlunit.javascript.SilentJavaScriptErrorListener());
        // Silence the IncorrectnessListener (cookie warnings, content type warnings)
        webClient.setIncorrectnessListener((message, origin) -> { /* silenced */ });
        return webClient;
    }

    /**
     * Extract structured content from a rendered page:
     * headings, paragraphs, tables (as markdown), and lists.
     */
    private String extractPageContent(HtmlPage page) {
        StringBuilder content = new StringBuilder();

        // Extract headings
        for (int i = 1; i <= 3; i++) {
            var headings = page.getElementsByTagName("h" + i);
            for (var h : headings) {
                String text = h.getTextContent().trim();
                if (!text.isEmpty()) {
                    content.append("#".repeat(i)).append(" ").append(text).append("\n\n");
                }
            }
        }

        // Extract tables as markdown (critical for financial data)
        var tables = page.getElementsByTagName("table");
        int tableCount = 0;
        for (var tableNode : tables) {
            if (tableCount >= 5) break; // Limit tables
            if (tableNode instanceof HtmlTable table) {
                String mdTable = tableToMarkdown(table);
                if (!mdTable.isEmpty()) {
                    content.append(mdTable).append("\n\n");
                    tableCount++;
                }
            }
        }

        // Extract main text content (paragraphs, divs with text)
        String bodyText = page.getBody() != null ? page.getBody().getTextContent() : "";
        // Clean up whitespace
        bodyText = bodyText.replaceAll("\\s+", " ").trim();
        // Take first portion
        if (bodyText.length() > 6000) {
            bodyText = bodyText.substring(0, 6000) + "...";
        }
        if (!bodyText.isEmpty()) {
            content.append("## Page Content\n\n").append(bodyText).append("\n");
        }

        return content.toString();
    }

    /**
     * Convert an HTML table to a markdown table.
     */
    private String tableToMarkdown(HtmlTable table) {
        List<List<String>> rows = new ArrayList<>();

        for (HtmlTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (HtmlTableCell cell : row.getCells()) {
                cells.add(cell.getTextContent().trim().replace("|", "\\|").replace("\n", " "));
            }
            if (!cells.isEmpty() && cells.stream().anyMatch(c -> !c.isEmpty())) {
                rows.add(cells);
            }
        }

        if (rows.isEmpty()) return "";

        // Determine column count
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (cols == 0 || cols > 20) return ""; // Skip huge tables

        StringBuilder md = new StringBuilder();

        // Header row
        List<String> header = rows.get(0);
        md.append("| ");
        for (int i = 0; i < cols; i++) {
            md.append(i < header.size() ? header.get(i) : "").append(" | ");
        }
        md.append("\n|");
        for (int i = 0; i < cols; i++) {
            md.append("---|");
        }
        md.append("\n");

        // Data rows (limit to 30)
        for (int r = 1; r < Math.min(rows.size(), 31); r++) {
            List<String> row = rows.get(r);
            md.append("| ");
            for (int i = 0; i < cols; i++) {
                md.append(i < row.size() ? row.get(i) : "").append(" | ");
            }
            md.append("\n");
        }

        return md.toString();
    }

    private String extractNodeText(DomNode node) {
        return node.getTextContent().trim().replaceAll("\\s+", " ");
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of("type", "string", "description",
            "The URL to browse and render (e.g., https://finance.yahoo.com/quote/AAPL)"));
        properties.put("selector", Map.of("type", "string", "description",
            "Optional CSS selector to extract specific elements (e.g., '#quote-summary' for Yahoo Finance)"));
        properties.put("waitMs", Map.of("type", "integer", "description",
            "Milliseconds to wait for JavaScript execution (default 5000)"));
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public int getMaxResponseLength() { return MAX_OUTPUT_LENGTH; }

    // ==================== Tool Routing Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to browse a web page with JavaScript rendering, scrape financial data from " +
               "Yahoo Finance or Google Finance, access dynamic web content, or fetch company information " +
               "from corporate websites.";
    }

    @Override
    public String getAvoidWhen() {
        return "The URL is a simple REST API that returns JSON (use http_request instead), " +
               "or the page is static HTML without JavaScript (use web_scrape instead).";
    }

    @Override
    public String getCategory() { return "web"; }

    @Override
    public List<String> getTags() {
        return List.of("browser", "javascript", "scraping", "financial-data", "dynamic-content");
    }
}
