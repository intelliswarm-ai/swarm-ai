package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CSV Analysis Tool — reads CSV/TSV files and performs basic analysis.
 *
 * Operations:
 * - describe: Column headers, row count, sample data
 * - stats: Column statistics (count, min, max, mean, unique values) for numeric columns
 * - head: First N rows as markdown table
 * - filter: Filter rows by column value
 * - count: Group and count by column
 */
@Component
public class CSVAnalysisTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(CSVAnalysisTool.class);

    private static final long MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024; // 20 MB
    private static final int DEFAULT_HEAD_ROWS = 10;
    private static final Set<String> VALID_OPERATIONS = Set.of("describe", "stats", "head", "filter", "count");

    private final Path baseDirectory;

    public CSVAnalysisTool() {
        this(null);
    }

    public CSVAnalysisTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getFunctionName() {
        return "csv_analysis";
    }

    @Override
    public String getDescription() {
        return "Read and analyze CSV/TSV files. Operations: 'describe' (overview), 'stats' (column statistics), " +
               "'head' (first N rows), 'filter' (filter rows by column value), 'count' (group and count by column).";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.get("path");
        String csvContent = (String) parameters.get("csv_content");
        String operation = ((String) parameters.getOrDefault("operation", "describe")).toLowerCase();
        String column = (String) parameters.getOrDefault("column", null);
        String value = (String) parameters.getOrDefault("value", null);
        Integer rows = parameters.get("rows") != null ? ((Number) parameters.get("rows")).intValue() : DEFAULT_HEAD_ROWS;

        logger.info("CSVAnalysisTool: operation={}, path={}, column={}", operation, pathStr, column);

        try {
            if (!VALID_OPERATIONS.contains(operation)) {
                return "Error: Invalid operation: '" + operation + "'. Valid: " + VALID_OPERATIONS;
            }

            // Get CSV content either from file or direct input
            String content;
            if (csvContent != null && !csvContent.trim().isEmpty()) {
                content = csvContent;
            } else if (pathStr != null && !pathStr.trim().isEmpty()) {
                String fileError = readFileContent(pathStr);
                if (fileError.startsWith("Error:")) return fileError;
                content = fileError; // Not an error, it's the content
            } else {
                return "Error: Either 'path' or 'csv_content' is required";
            }

            // Parse CSV
            List<CSVRecord> records;
            List<String> headers;
            try (CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .setIgnoreEmptyLines(true)
                    .build()
                    .parse(new StringReader(content))) {
                headers = parser.getHeaderNames();
                records = parser.getRecords();
            }

            if (headers.isEmpty()) {
                return "Error: CSV has no headers. First row must contain column names.";
            }

            return switch (operation) {
                case "describe" -> executeDescribe(headers, records);
                case "stats" -> executeStats(headers, records, column);
                case "head" -> executeHead(headers, records, rows);
                case "filter" -> executeFilter(headers, records, column, value);
                case "count" -> executeCount(headers, records, column);
                default -> "Error: Unknown operation: " + operation;
            };

        } catch (IOException e) {
            logger.error("Error analyzing CSV", e);
            return "Error: Failed to parse CSV: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error in CSV analysis", e);
            return "Error: " + e.getMessage();
        }
    }

    private String readFileContent(String pathStr) {
        try {
            if (pathStr.contains("..")) return "Error: Access denied: path traversal not allowed";

            Path filePath = Paths.get(pathStr.trim()).normalize();
            if (baseDirectory != null && !filePath.isAbsolute()) {
                filePath = baseDirectory.resolve(filePath).normalize();
                if (!filePath.toAbsolutePath().normalize().startsWith(baseDirectory)) {
                    return "Error: Access denied: path outside base directory";
                }
            }

            if (!Files.exists(filePath)) return "Error: File not found: " + pathStr;
            if (Files.size(filePath) > MAX_FILE_SIZE_BYTES) return "Error: File too large (max 20 MB)";

            return Files.readString(filePath);
        } catch (IOException e) {
            return "Error: Failed to read file: " + e.getMessage();
        }
    }

    private String executeDescribe(List<String> headers, List<CSVRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("**CSV Overview**\n");
        sb.append("**Rows:** ").append(records.size()).append("\n");
        sb.append("**Columns:** ").append(headers.size()).append("\n\n");

        sb.append("**Column Details:**\n\n");
        sb.append("| Column | Type | Non-Empty | Unique | Sample |\n");
        sb.append("|--------|------|-----------|--------|--------|\n");

        for (String header : headers) {
            List<String> values = records.stream()
                .map(r -> r.isMapped(header) ? r.get(header) : "")
                .collect(Collectors.toList());

            long nonEmpty = values.stream().filter(v -> !v.isEmpty()).count();
            long unique = values.stream().filter(v -> !v.isEmpty()).distinct().count();
            boolean isNumeric = values.stream().filter(v -> !v.isEmpty()).allMatch(this::isNumeric);
            String sample = values.stream().filter(v -> !v.isEmpty()).findFirst().orElse("(empty)");
            if (sample.length() > 30) sample = sample.substring(0, 27) + "...";

            sb.append("| ").append(header)
              .append(" | ").append(isNumeric ? "numeric" : "text")
              .append(" | ").append(nonEmpty)
              .append(" | ").append(unique)
              .append(" | ").append(sample)
              .append(" |\n");
        }

        // Show first 3 rows as preview
        sb.append("\n**Preview (first 3 rows):**\n\n");
        sb.append(formatAsTable(headers, records.subList(0, Math.min(3, records.size()))));

        return sb.toString();
    }

    private String executeStats(List<String> headers, List<CSVRecord> records, String column) {
        List<String> targetColumns = (column != null && !column.isEmpty())
            ? List.of(column) : headers;

        StringBuilder sb = new StringBuilder();
        sb.append("**Column Statistics** (").append(records.size()).append(" rows)\n\n");

        for (String col : targetColumns) {
            if (!headers.contains(col)) {
                sb.append("Column '").append(col).append("' not found.\n\n");
                continue;
            }

            List<String> values = records.stream()
                .map(r -> r.isMapped(col) ? r.get(col) : "")
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toList());

            sb.append("### ").append(col).append("\n");
            sb.append("- **Count:** ").append(values.size()).append("\n");
            sb.append("- **Unique:** ").append(values.stream().distinct().count()).append("\n");

            List<Double> numbers = values.stream()
                .filter(this::isNumeric)
                .map(Double::parseDouble)
                .sorted()
                .collect(Collectors.toList());

            if (!numbers.isEmpty() && numbers.size() == values.size()) {
                DoubleSummaryStatistics stats = numbers.stream().mapToDouble(d -> d).summaryStatistics();
                sb.append("- **Min:** ").append(formatNumber(stats.getMin())).append("\n");
                sb.append("- **Max:** ").append(formatNumber(stats.getMax())).append("\n");
                sb.append("- **Mean:** ").append(formatNumber(stats.getAverage())).append("\n");
                sb.append("- **Sum:** ").append(formatNumber(stats.getSum())).append("\n");
                if (numbers.size() > 1) {
                    double median = numbers.get(numbers.size() / 2);
                    sb.append("- **Median:** ").append(formatNumber(median)).append("\n");
                }
            } else {
                // Top 5 most frequent values
                Map<String, Long> freq = values.stream()
                    .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
                sb.append("- **Top values:** ");
                freq.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> sb.append(e.getKey()).append(" (").append(e.getValue()).append("), "));
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String executeHead(List<String> headers, List<CSVRecord> records, int rows) {
        int count = Math.min(rows, records.size());
        StringBuilder sb = new StringBuilder();
        sb.append("**First ").append(count).append(" of ").append(records.size()).append(" rows:**\n\n");
        sb.append(formatAsTable(headers, records.subList(0, count)));
        return sb.toString();
    }

    private String executeFilter(List<String> headers, List<CSVRecord> records, String column, String value) {
        if (column == null || column.isEmpty()) return "Error: 'column' parameter is required for filter";
        if (value == null) return "Error: 'value' parameter is required for filter";
        if (!headers.contains(column)) return "Error: Column '" + column + "' not found. Available: " + headers;

        List<CSVRecord> filtered = records.stream()
            .filter(r -> {
                String cellValue = r.isMapped(column) ? r.get(column) : "";
                return cellValue.toLowerCase().contains(value.toLowerCase());
            })
            .limit(50)
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("**Filter:** ").append(column).append(" contains '").append(value).append("'\n");
        sb.append("**Matches:** ").append(filtered.size());
        if (filtered.size() >= 50) sb.append(" (showing first 50)");
        sb.append("\n\n");

        if (filtered.isEmpty()) {
            sb.append("No matching rows found.\n");
        } else {
            sb.append(formatAsTable(headers, filtered));
        }

        return sb.toString();
    }

    private String executeCount(List<String> headers, List<CSVRecord> records, String column) {
        if (column == null || column.isEmpty()) return "Error: 'column' parameter is required for count";
        if (!headers.contains(column)) return "Error: Column '" + column + "' not found. Available: " + headers;

        Map<String, Long> counts = records.stream()
            .map(r -> r.isMapped(column) ? r.get(column) : "(empty)")
            .collect(Collectors.groupingBy(v -> v, Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        sb.append("**Group count by:** ").append(column).append("\n\n");
        sb.append("| ").append(column).append(" | Count |\n");
        sb.append("|------|-------|\n");

        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));

        sb.append("\n**Total groups:** ").append(counts.size()).append("\n");
        return sb.toString();
    }

    private String formatAsTable(List<String> headers, List<CSVRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ");
        headers.forEach(h -> sb.append(h).append(" | "));
        sb.append("\n|");
        headers.forEach(h -> sb.append("------|"));
        sb.append("\n");

        for (CSVRecord record : records) {
            sb.append("| ");
            for (String header : headers) {
                String val = record.isMapped(header) ? record.get(header) : "";
                if (val.length() > 40) val = val.substring(0, 37) + "...";
                sb.append(val).append(" | ");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private boolean isNumeric(String str) {
        try {
            Double.parseDouble(str.replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String formatNumber(double d) {
        if (d == (long) d) return String.valueOf((long) d);
        return String.format("%.2f", d);
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "Path to CSV/TSV file");
        properties.put("path", path);

        Map<String, Object> csvContent = new HashMap<>();
        csvContent.put("type", "string");
        csvContent.put("description", "CSV content as string (alternative to path)");
        properties.put("csv_content", csvContent);

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("description", "Operation: describe, stats, head, filter, count (default: describe)");
        operation.put("default", "describe");
        operation.put("enum", List.of("describe", "stats", "head", "filter", "count"));
        properties.put("operation", operation);

        Map<String, Object> column = new HashMap<>();
        column.put("type", "string");
        column.put("description", "Column name for stats/filter/count operations");
        properties.put("column", column);

        Map<String, Object> value = new HashMap<>();
        value.put("type", "string");
        value.put("description", "Value to filter by (for filter operation)");
        properties.put("value", value);

        Map<String, Object> rows = new HashMap<>();
        rows.put("type", "integer");
        rows.put("description", "Number of rows for head operation (default: 10)");
        properties.put("rows", rows);

        schema.put("properties", properties);
        schema.put("required", new String[]{});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ==================== Tool Routing Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to analyze CSV/TSV data: statistics, filtering, counting, column operations.";
    }

    @Override
    public String getAvoidWhen() {
        return "Data is JSON, XML, or not in tabular format.";
    }

    @Override
    public String getCategory() {
        return "analysis";
    }

    @Override
    public List<String> getTags() {
        return List.of("csv", "data", "statistics", "tabular");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Markdown-formatted analysis results: tables, statistics, filtered rows, or group counts depending on operation"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 12000;
    }

    public record Request(String path, String csvContent, String operation, String column, String value, Integer rows) {}
}
