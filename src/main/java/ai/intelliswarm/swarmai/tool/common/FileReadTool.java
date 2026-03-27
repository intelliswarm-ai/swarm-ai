package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File Read Tool — reads file contents from the filesystem with format auto-detection.
 *
 * Supports: text, JSON, CSV, YAML, XML, properties, and Markdown.
 * Features: line range support, metadata, configurable base directory, security checks.
 */
@Component
public class FileReadTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(FileReadTool.class);

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final int DEFAULT_MAX_LINES = 500;

    // Patterns that are denied for security reasons
    private static final List<String> DENIED_PATTERNS = List.of(
        ".env", "credentials", "secret", "password", "private_key",
        ".pem", ".key", ".p12", ".jks", ".keystore", "id_rsa", "id_ed25519"
    );

    private final ObjectMapper objectMapper;
    private final Path baseDirectory;

    public FileReadTool() {
        this(null);
    }

    public FileReadTool(Path baseDirectory) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.baseDirectory = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getFunctionName() {
        return "file_read";
    }

    @Override
    public String getDescription() {
        return "Read file contents from the filesystem. Supports text, JSON, CSV, YAML, XML, and Markdown formats. " +
               "Can read specific line ranges for large files. Returns file content with metadata.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.get("path");
        String format = (String) parameters.getOrDefault("format", "auto");
        Integer offset = parameters.get("offset") != null ? ((Number) parameters.get("offset")).intValue() : null;
        Integer limit = parameters.get("limit") != null ? ((Number) parameters.get("limit")).intValue() : null;

        logger.info("FileReadTool: Reading file: {} (format: {}, offset: {}, limit: {})", pathStr, format, offset, limit);

        try {
            // 1. Validate path
            String pathError = validatePath(pathStr);
            if (pathError != null) {
                return "Error: " + pathError;
            }

            Path filePath = resolvePath(pathStr);

            // 2. Security checks
            String securityError = checkSecurity(filePath, pathStr);
            if (securityError != null) {
                return "Error: " + securityError;
            }

            // 3. Check file exists and is readable
            if (!Files.exists(filePath)) {
                return "Error: File not found: " + pathStr;
            }
            if (!Files.isRegularFile(filePath)) {
                return "Error: Not a regular file (may be a directory): " + pathStr;
            }
            if (!Files.isReadable(filePath)) {
                return "Error: File is not readable: " + pathStr;
            }

            // 4. Check file size
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return String.format("Error: File too large (%d bytes, max %d bytes): %s",
                    fileSize, MAX_FILE_SIZE_BYTES, pathStr);
            }

            // 5. Detect format
            String detectedFormat = "auto".equalsIgnoreCase(format) ? detectFormat(filePath) : format.toLowerCase();

            // 6. Read content
            String content = readContent(filePath, detectedFormat, offset, limit);

            // 7. Build response with metadata
            return buildResponse(filePath, pathStr, fileSize, detectedFormat, content, offset, limit);

        } catch (IOException e) {
            logger.error("Error reading file: {}", pathStr, e);
            return "Error: Failed to read file: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error reading file: {}", pathStr, e);
            return "Error: " + e.getMessage();
        }
    }

    private String validatePath(String pathStr) {
        if (pathStr == null) {
            return "File path is required";
        }
        if (pathStr.trim().isEmpty()) {
            return "File path cannot be empty";
        }
        return null;
    }

    private Path resolvePath(String pathStr) {
        Path filePath = Paths.get(pathStr.trim()).normalize();
        if (baseDirectory != null && !filePath.isAbsolute()) {
            filePath = baseDirectory.resolve(filePath).normalize();
        }
        return filePath;
    }

    private String checkSecurity(Path filePath, String originalPath) {
        // Check path traversal
        if (baseDirectory != null) {
            Path normalizedPath = filePath.toAbsolutePath().normalize();
            if (!normalizedPath.startsWith(baseDirectory)) {
                return "Access denied: path is outside the allowed base directory";
            }
        }

        // Check path traversal patterns in the original path
        if (originalPath.contains("..")) {
            return "Access denied: path traversal (..) is not allowed";
        }

        // Check denied file patterns
        String fileName = filePath.getFileName().toString().toLowerCase();
        String fullPath = filePath.toString().toLowerCase();
        for (String pattern : DENIED_PATTERNS) {
            if (fileName.contains(pattern) || fullPath.contains(pattern)) {
                return "Access denied: reading files matching '" + pattern + "' is not allowed for security reasons";
            }
        }

        return null;
    }

    private String detectFormat(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".json")) return "json";
        if (fileName.endsWith(".csv") || fileName.endsWith(".tsv")) return "csv";
        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) return "yaml";
        if (fileName.endsWith(".xml") || fileName.endsWith(".xbrl") || fileName.endsWith(".html") || fileName.endsWith(".htm")) return "xml";
        if (fileName.endsWith(".properties")) return "properties";
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) return "markdown";

        return "text";
    }

    private String readContent(Path filePath, String format, Integer offset, Integer limit) throws IOException {
        return switch (format) {
            case "json" -> readAsJson(filePath);
            case "csv" -> readAsCsv(filePath, offset, limit);
            default -> readAsText(filePath, offset, limit);
        };
    }

    private String readAsText(Path filePath, Integer offset, Integer limit) throws IOException {
        int startLine = (offset != null && offset > 0) ? offset : 1;
        int maxLines = (limit != null && limit > 0) ? limit : DEFAULT_MAX_LINES;

        try (Stream<String> lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
            List<String> result = lines
                .skip(startLine - 1)
                .limit(maxLines)
                .collect(Collectors.toList());

            if (result.isEmpty()) {
                return "(empty file or offset beyond end of file)";
            }

            return String.join("\n", result);
        } catch (java.nio.charset.MalformedInputException e) {
            // Try reading as ISO-8859-1 fallback
            try (Stream<String> lines = Files.lines(filePath, java.nio.charset.StandardCharsets.ISO_8859_1)) {
                List<String> result = lines
                    .skip(startLine - 1)
                    .limit(maxLines)
                    .collect(Collectors.toList());
                return String.join("\n", result);
            }
        }
    }

    private String readAsJson(Path filePath) throws IOException {
        String raw = Files.readString(filePath, StandardCharsets.UTF_8);

        try {
            // Parse and re-serialize with pretty printing
            Object json = objectMapper.readValue(raw, Object.class);
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            // If not valid JSON, return as-is
            logger.debug("File is not valid JSON, returning as text: {}", e.getMessage());
            return raw;
        }
    }

    private String readAsCsv(Path filePath, Integer offset, Integer limit) throws IOException {
        int startLine = (offset != null && offset > 0) ? offset : 1;
        int maxLines = (limit != null && limit > 0) ? limit : DEFAULT_MAX_LINES;

        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            StringBuilder markdown = new StringBuilder();
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return "(empty CSV file)";
            }

            // Detect delimiter
            char delimiter = headerLine.contains("\t") ? '\t' : ',';
            String[] headers = splitCsvLine(headerLine, delimiter);

            // Build markdown table header
            markdown.append("| ");
            for (String h : headers) {
                markdown.append(h.trim()).append(" | ");
            }
            markdown.append("\n|");
            for (int i = 0; i < headers.length; i++) {
                markdown.append("---|");
            }
            markdown.append("\n");

            // Read data rows
            String line;
            int lineNum = 1; // header was line 1
            int written = 0;
            while ((line = reader.readLine()) != null && written < maxLines) {
                lineNum++;
                if (lineNum < startLine) continue;

                String[] fields = splitCsvLine(line, delimiter);
                markdown.append("| ");
                for (int i = 0; i < headers.length; i++) {
                    String value = (i < fields.length) ? fields[i].trim() : "";
                    markdown.append(value).append(" | ");
                }
                markdown.append("\n");
                written++;
            }

            return markdown.toString();
        }
    }

    private String[] splitCsvLine(String line, char delimiter) {
        // Simple CSV splitting that handles quoted fields
        if (delimiter == ',') {
            return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        }
        return line.split(String.valueOf(delimiter), -1);
    }

    private String buildResponse(Path filePath, String originalPath, long fileSize,
                                  String format, String content, Integer offset, Integer limit) throws IOException {
        StringBuilder response = new StringBuilder();

        // Metadata header
        response.append("**File:** ").append(originalPath).append("\n");
        response.append("**Size:** ").append(formatFileSize(fileSize)).append("\n");
        response.append("**Format:** ").append(format).append("\n");
        response.append("**Last Modified:** ").append(
            Instant.ofEpochMilli(Files.getLastModifiedTime(filePath).toMillis())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ).append("\n");

        if (offset != null || limit != null) {
            long totalLines = Files.lines(filePath).count();
            response.append("**Lines:** showing ");
            if (offset != null) response.append("from ").append(offset);
            if (limit != null) response.append(" (max ").append(limit).append(")");
            response.append(" of ").append(totalLines).append(" total\n");
        }

        response.append("\n---\n\n");
        response.append(content);

        return response.toString();
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "File path to read (relative to working directory or absolute)");
        properties.put("path", path);

        Map<String, Object> format = new HashMap<>();
        format.put("type", "string");
        format.put("description", "File format: auto, text, json, csv, yaml, xml, markdown, properties (default: auto)");
        format.put("default", "auto");
        properties.put("format", format);

        Map<String, Object> offset = new HashMap<>();
        offset.put("type", "integer");
        offset.put("description", "Start reading from this line number (1-based, default: 1)");
        offset.put("minimum", 1);
        properties.put("offset", offset);

        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "Maximum number of lines to read (default: 500)");
        limit.put("minimum", 1);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", new String[]{"path"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ==================== OpenClaw Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to read file contents, inspect config files, or load data from disk.";
    }

    @Override
    public String getAvoidWhen() {
        return "Data is already available in context or comes from a web API.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("file", "read", "text", "json", "csv", "yaml");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "string",
            "description", "File contents as text, with format auto-detection"
        );
    }

    @Override
    public String smokeTest() {
        try {
            Path tempFile = Files.createTempFile("swarm-file-read-smoke", ".txt");
            Files.writeString(tempFile, "smoke-test");
            String content = Files.readString(tempFile);
            Files.deleteIfExists(tempFile);
            return "smoke-test".equals(content) ? null : "File read/write round-trip failed";
        } catch (IOException e) {
            return "File system access check failed: " + e.getMessage();
        }
    }

    @Override
    public int getMaxResponseLength() {
        return 12000; // Files can be large — allow more room
    }

    // Request record for Spring AI function binding
    public record Request(String path, String format, Integer offset, Integer limit) {}
}
