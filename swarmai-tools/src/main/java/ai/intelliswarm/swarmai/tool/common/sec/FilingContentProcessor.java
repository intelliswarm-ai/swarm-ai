package ai.intelliswarm.swarmai.tool.common.sec;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Processes SEC filing content - extracts and cleans text from HTML/XBRL documents.
 * Preserves table structure for financial data and extracts form-type-specific sections.
 */
public class FilingContentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(FilingContentProcessor.class);
    private final ExecutorService executorService;
    private final SECApiClient apiClient;

    // Patterns for identifying key financial sections in filings
    private static final Pattern FINANCIAL_SECTION_PATTERN = Pattern.compile(
        "(?i)(financial\\s+statements|consolidated\\s+balance\\s+sheet|" +
        "consolidated\\s+statements?\\s+of\\s+(operations|income|comprehensive\\s+income|cash\\s+flows)|" +
        "management.s\\s+discussion|results\\s+of\\s+operations|" +
        "risk\\s+factors|liquidity\\s+and\\s+capital)"
    );

    // XBRL us-gaap tag patterns for key financial metrics
    private static final Pattern XBRL_METRIC_PATTERN = Pattern.compile(
        "<(?:ix:nonFraction|us-gaap:)([A-Za-z]+)[^>]*>([^<]+)</(?:ix:nonFraction|us-gaap:[A-Za-z]+)>",
        Pattern.CASE_INSENSITIVE
    );

    // Key XBRL concepts to extract
    private static final List<String> KEY_XBRL_CONCEPTS = List.of(
        "Revenue", "Revenues", "SalesRevenueNet", "RevenueFromContractWithCustomerExcludingAssessedTax",
        "NetIncomeLoss", "EarningsPerShareBasic", "EarningsPerShareDiluted",
        "Assets", "Liabilities", "StockholdersEquity",
        "CashAndCashEquivalentsAtCarryingValue",
        "OperatingIncomeLoss", "GrossProfit", "CostOfRevenue",
        "LongTermDebt", "ShortTermBorrowings",
        "NetCashProvidedByUsedInOperatingActivities",
        "CommonStockSharesOutstanding"
    );

    public FilingContentProcessor(SECApiClient apiClient) {
        this.apiClient = apiClient;
        // Use 3 threads max to stay under SEC's 10 req/sec rate limit
        this.executorService = Executors.newFixedThreadPool(3);
    }

    /**
     * Fetches and processes content for a list of filings
     */
    public void fetchFilingContents(List<Filing> filings) {
        fetchFilingContents(filings, 15);
    }

    /**
     * Fetches and processes content for a list of filings with configurable limit
     */
    public void fetchFilingContents(List<Filing> filings, int maxFilings) {
        List<Filing> importantFilings = filings.stream()
                .filter(f -> FilingTypeHandler.isImportantFilingType(f.getFormType()))
                .limit(maxFilings)
                .collect(Collectors.toList());

        logger.info("All filing types found: {}", filings.stream().map(Filing::getFormType).distinct().collect(Collectors.toList()));
        logger.info("Processing content for {} important filings from {} total", importantFilings.size(), filings.size());

        List<CompletableFuture<Void>> contentFutures = importantFilings.stream()
                .map(filing -> CompletableFuture.runAsync(() -> fetchSingleFilingContent(filing), executorService))
                .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(contentFutures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            logger.warn("Error waiting for filing content to be fetched: {}", e.getMessage());
        }
    }

    private void fetchSingleFilingContent(Filing filing) {
        try {
            logger.debug("Fetching content for filing: {} - {}", filing.getFormType(), filing.getAccessionNumber());

            String content = apiClient.fetchFilingContent(filing.getUrl());

            if (content != null) {
                filing.setContent(content);
                filing.setExtractedText(extractKeyContent(content, filing.getFormType()));
                filing.setContentFetched(true);
                logger.debug("Successfully fetched content for {} (length: {} chars)",
                    filing.getAccessionNumber(), content.length());
            } else {
                filing.setContentError("Failed to fetch content from URL");
                logger.warn("Failed to fetch content for {}", filing.getAccessionNumber());
            }
        } catch (Exception e) {
            filing.setContentError("Error fetching content: " + e.getMessage());
            logger.warn("Error fetching content for {}: {}", filing.getAccessionNumber(), e.getMessage());
        }
    }

    /**
     * Extracts key content from SEC filing HTML/XBRL
     */
    private String extractKeyContent(String htmlContent, String formType) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            return "No content available";
        }

        try {
            StringBuilder extracted = new StringBuilder();

            // 1. Try XBRL extraction first (most structured)
            if (isXBRLDocument(htmlContent)) {
                String xbrlData = extractXBRLContent(htmlContent, formType);
                if (xbrlData != null && !xbrlData.isEmpty()) {
                    extracted.append(xbrlData).append("\n\n");
                }
            }

            // 2. Extract tables (preserve financial data structure)
            String tables = extractTables(htmlContent);
            if (tables != null && !tables.isEmpty()) {
                extracted.append("**Financial Tables:**\n").append(tables).append("\n\n");
            }

            // 3. Extract form-type-specific sections from cleaned text
            String cleanedText = cleanHTML(htmlContent);
            String sections = extractRelevantSections(cleanedText, formType);
            if (sections != null && !sections.isEmpty()) {
                extracted.append(sections);
            }

            String result = extracted.toString().trim();
            if (result.length() > 100) {
                return result;
            }

            // Fallback: return truncated cleaned text
            if (cleanedText.length() > 8000) {
                return cleanedText.substring(0, 8000) + "\n[... truncated ...]";
            }
            return cleanedText;

        } catch (Exception e) {
            logger.warn("Error extracting content: {}", e.getMessage());
            return "Error extracting content: " + e.getMessage();
        }
    }

    /**
     * Clean HTML content using Jsoup, preserving table structure as markdown
     */
    private String cleanHTML(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            doc.select("script, style, noscript").remove();

            // Get text but preserve paragraph breaks
            StringBuilder text = new StringBuilder();
            for (Element element : doc.body().getAllElements()) {
                if (element.tagName().equals("p") || element.tagName().equals("div") ||
                    element.tagName().matches("h[1-6]")) {
                    String ownText = element.ownText().trim();
                    if (!ownText.isEmpty()) {
                        text.append(ownText).append("\n");
                    }
                }
            }

            String result = text.toString();
            if (result.trim().length() < 100) {
                // Fallback to simple text extraction
                result = doc.text();
            }
            return result.replaceAll("\\n{3,}", "\n\n").trim();

        } catch (Exception e) {
            logger.warn("Error cleaning HTML with Jsoup: {}", e.getMessage());
            return fallbackCleanHTML(htmlContent);
        }
    }

    /**
     * Extract HTML tables and convert to markdown format for LLM readability
     */
    private String extractTables(String htmlContent) {
        try {
            Document doc = Jsoup.parse(htmlContent);
            Elements tables = doc.select("table");

            if (tables.isEmpty()) {
                return null;
            }

            StringBuilder result = new StringBuilder();
            int tableCount = 0;

            for (Element table : tables) {
                // Skip tiny tables (navigation, layout)
                Elements rows = table.select("tr");
                if (rows.size() < 2) continue;

                // Check if table likely contains financial data
                String tableText = table.text().toLowerCase();
                boolean isFinancialTable = tableText.contains("revenue") || tableText.contains("income") ||
                    tableText.contains("assets") || tableText.contains("liabilities") ||
                    tableText.contains("cash") || tableText.contains("earnings") ||
                    tableText.contains("shares") || tableText.contains("equity") ||
                    tableText.contains("operating") || tableText.contains("total") ||
                    tableText.contains("net") || tableText.contains("gross") ||
                    tableText.matches(".*\\$[\\d,.]+.*");

                if (!isFinancialTable) continue;

                // Convert table to markdown
                for (Element row : rows) {
                    Elements cells = row.select("td, th");
                    if (cells.isEmpty()) continue;

                    StringBuilder rowStr = new StringBuilder("| ");
                    for (Element cell : cells) {
                        String cellText = cell.text().trim().replaceAll("\\s+", " ");
                        if (cellText.length() > 60) {
                            cellText = cellText.substring(0, 57) + "...";
                        }
                        rowStr.append(cellText).append(" | ");
                    }
                    result.append(rowStr).append("\n");

                    // Add separator after header row
                    if (row.select("th").size() > 0) {
                        result.append("|");
                        for (int i = 0; i < cells.size(); i++) {
                            result.append("---|");
                        }
                        result.append("\n");
                    }
                }
                result.append("\n");

                tableCount++;
                if (tableCount >= 5) break; // Limit to 5 financial tables
            }

            return result.length() > 0 ? result.toString() : null;

        } catch (Exception e) {
            logger.debug("Error extracting tables: {}", e.getMessage());
            return null;
        }
    }

    private String fallbackCleanHTML(String htmlContent) {
        return htmlContent
            .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<[^>]+>", " ")
            .replaceAll("&[a-zA-Z0-9#]+;", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean isXBRLDocument(String content) {
        return content != null && (
            content.contains("xbrl") ||
            content.contains("xmlns") && content.contains("xbrli") ||
            content.contains("<xbrl:") ||
            content.contains("us-gaap:") ||
            content.contains("ix:nonFraction")
        );
    }

    /**
     * Extract structured financial data from XBRL documents
     */
    private String extractXBRLContent(String content, String formType) {
        StringBuilder result = new StringBuilder();
        result.append("**XBRL Financial Data:**\n");

        try {
            // Parse with Jsoup to handle inline XBRL (iXBRL)
            Document doc = Jsoup.parse(content);

            // Extract ix:nonFraction elements (inline XBRL)
            Elements xbrlElements = doc.select("ix\\:nonFraction, [name*=us-gaap]");
            if (xbrlElements.isEmpty()) {
                // Try regex-based extraction for traditional XBRL
                Matcher matcher = XBRL_METRIC_PATTERN.matcher(content);
                while (matcher.find()) {
                    String concept = matcher.group(1);
                    String value = matcher.group(2).trim();
                    if (isKeyMetric(concept) && !value.isEmpty()) {
                        result.append("- ").append(formatConceptName(concept))
                              .append(": ").append(value).append("\n");
                    }
                }
            } else {
                for (Element el : xbrlElements) {
                    String name = el.attr("name");
                    if (name.isEmpty()) name = el.attr("ix:name");
                    String value = el.text().trim();

                    if (!name.isEmpty() && !value.isEmpty()) {
                        // Extract concept name from namespace:concept format
                        String concept = name.contains(":") ? name.substring(name.indexOf(":") + 1) : name;
                        if (isKeyMetric(concept)) {
                            result.append("- ").append(formatConceptName(concept))
                                  .append(": ").append(value).append("\n");
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error parsing XBRL content: {}", e.getMessage());
        }

        return result.length() > 30 ? result.toString() : "";
    }

    private boolean isKeyMetric(String concept) {
        for (String key : KEY_XBRL_CONCEPTS) {
            if (concept.toLowerCase().contains(key.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String formatConceptName(String concept) {
        // Convert CamelCase to readable: "NetIncomeLoss" -> "Net Income Loss"
        return concept.replaceAll("([a-z])([A-Z])", "$1 $2")
                      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
    }

    /**
     * Extract relevant sections based on form type
     */
    private String extractRelevantSections(String text, String formType) {
        if (text == null || text.length() < 100) {
            return "";
        }

        StringBuilder sections = new StringBuilder();

        switch (formType.toUpperCase()) {
            case "10-K", "20-F" -> {
                sections.append(extractSection(text, "Financial Statements",
                    "financial statements", "consolidated balance sheet", "consolidated statements of operations",
                    "statements of income", "statements of cash flows"));
                sections.append(extractSection(text, "Management's Discussion & Analysis",
                    "management's discussion", "results of operations", "liquidity and capital"));
                sections.append(extractSection(text, "Risk Factors",
                    "risk factors"));
            }
            case "10-Q", "6-K" -> {
                sections.append(extractSection(text, "Financial Statements",
                    "financial statements", "condensed consolidated", "balance sheet",
                    "statements of operations", "statements of cash flows"));
                sections.append(extractSection(text, "Management's Discussion & Analysis",
                    "management's discussion", "results of operations"));
            }
            case "8-K" -> {
                sections.append(extractSection(text, "Current Report Details",
                    "item 2.02", "item 5.02", "item 7.01", "item 8.01", "item 9.01",
                    "results of operations", "financial statements"));
            }
            default -> {
                // For other form types, extract first meaningful content
                if (text.length() > 8000) {
                    sections.append("**Filing Summary:**\n").append(text, 0, 8000).append("\n[... truncated ...]");
                } else {
                    sections.append("**Filing Content:**\n").append(text);
                }
            }
        }

        return sections.toString();
    }

    /**
     * Extract a named section by searching for keywords in the text
     */
    private String extractSection(String text, String sectionTitle, String... keywords) {
        String textLower = text.toLowerCase();
        int bestStart = -1;

        for (String keyword : keywords) {
            int idx = textLower.indexOf(keyword.toLowerCase());
            if (idx != -1 && (bestStart == -1 || idx < bestStart)) {
                bestStart = idx;
            }
        }

        if (bestStart == -1) {
            return "";
        }

        // Extract a window of text around the keyword
        int sectionEnd = Math.min(text.length(), bestStart + 4000);

        // Try to find a natural break point
        String[] breakPatterns = {"Item ", "ITEM ", "PART ", "Note ", "NOTE "};
        for (String bp : breakPatterns) {
            int breakIdx = text.indexOf(bp, bestStart + 200);
            if (breakIdx > 0 && breakIdx < sectionEnd) {
                sectionEnd = breakIdx;
                break;
            }
        }

        String sectionText = text.substring(bestStart, sectionEnd).trim();
        if (sectionText.length() < 50) {
            return "";
        }

        return "**" + sectionTitle + ":**\n" + sectionText + "\n\n";
    }

    /**
     * Parse filings from SEC EDGAR browse page HTML
     */
    public List<Filing> parseFilingsFromBrowsePage(String htmlContent, String cik, String ticker) {
        return List.of();
    }

    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
