package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * PDF Read Tool — extracts text and metadata from PDF files.
 *
 * Features: page range support, metadata extraction, configurable base directory, security checks.
 */
@Component
public class PDFReadTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(PDFReadTool.class);

    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
    private static final int DEFAULT_MAX_PAGES = 20;

    private final Path baseDirectory;

    public PDFReadTool() {
        this(null);
    }

    public PDFReadTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getFunctionName() {
        return "pdf_read";
    }

    @Override
    public String getDescription() {
        return "Extract text and metadata from PDF files. Supports page range selection. " +
               "Returns page content with metadata (title, author, page count, creation date).";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.get("path");
        Integer startPage = parameters.get("start_page") != null ? ((Number) parameters.get("start_page")).intValue() : null;
        Integer endPage = parameters.get("end_page") != null ? ((Number) parameters.get("end_page")).intValue() : null;

        logger.info("PDFReadTool: Reading PDF: {} (pages: {}-{})", pathStr,
            startPage != null ? startPage : 1, endPage != null ? endPage : "end");

        try {
            // 1. Validate path
            if (pathStr == null || pathStr.trim().isEmpty()) {
                return "Error: File path is required";
            }
            if (pathStr.contains("..")) {
                return "Error: Access denied: path traversal (..) is not allowed";
            }

            Path filePath = resolvePath(pathStr);

            // 2. Security check
            if (baseDirectory != null) {
                Path normalized = filePath.toAbsolutePath().normalize();
                if (!normalized.startsWith(baseDirectory)) {
                    return "Error: Access denied: path is outside the allowed base directory";
                }
            }

            // 3. Check file exists
            if (!Files.exists(filePath)) {
                return "Error: File not found: " + pathStr;
            }

            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return String.format("Error: File too large (%d bytes, max %d bytes)", fileSize, MAX_FILE_SIZE_BYTES);
            }

            // 4. Read PDF
            return readPDF(filePath, pathStr, fileSize, startPage, endPage);

        } catch (IOException e) {
            logger.error("Error reading PDF: {}", pathStr, e);
            return "Error: Failed to read PDF: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error reading PDF: {}", pathStr, e);
            return "Error: " + e.getMessage();
        }
    }

    private Path resolvePath(String pathStr) {
        Path filePath = Paths.get(pathStr.trim()).normalize();
        if (baseDirectory != null && !filePath.isAbsolute()) {
            filePath = baseDirectory.resolve(filePath).normalize();
        }
        return filePath;
    }

    private String readPDF(Path filePath, String originalPath, long fileSize,
                            Integer startPage, Integer endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            int totalPages = document.getNumberOfPages();

            // Resolve page range
            int from = (startPage != null && startPage > 0) ? startPage : 1;
            int to = (endPage != null && endPage > 0) ? Math.min(endPage, totalPages) : Math.min(totalPages, from + DEFAULT_MAX_PAGES - 1);

            if (totalPages == 0) {
                return "**File:** " + originalPath + "\n**Pages:** 0\n\n(Empty PDF — no pages to extract.)\n";
            }

            if (from > totalPages) {
                return "Error: Start page " + from + " exceeds total pages (" + totalPages + ")";
            }

            // Extract metadata
            StringBuilder response = new StringBuilder();
            response.append("**File:** ").append(originalPath).append("\n");
            response.append("**Size:** ").append(formatFileSize(fileSize)).append("\n");
            response.append("**Pages:** ").append(totalPages).append(" total");
            if (from != 1 || to != totalPages) {
                response.append(" (showing ").append(from).append("-").append(to).append(")");
            }
            response.append("\n");

            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                if (info.getTitle() != null && !info.getTitle().isEmpty()) {
                    response.append("**Title:** ").append(info.getTitle()).append("\n");
                }
                if (info.getAuthor() != null && !info.getAuthor().isEmpty()) {
                    response.append("**Author:** ").append(info.getAuthor()).append("\n");
                }
                if (info.getSubject() != null && !info.getSubject().isEmpty()) {
                    response.append("**Subject:** ").append(info.getSubject()).append("\n");
                }
                if (info.getCreationDate() != null) {
                    response.append("**Created:** ").append(info.getCreationDate().getTime()).append("\n");
                }
            }

            response.append("\n---\n\n");

            // Extract text
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(from);
            stripper.setEndPage(to);

            String text = stripper.getText(document);

            if (text == null || text.trim().isEmpty()) {
                response.append("(No extractable text found. The PDF may contain only images or scanned content.)\n");
            } else {
                response.append(text);
            }

            return truncateResponse(response.toString());
        }
    }

    private String truncateResponse(String response) {
        int max = getMaxResponseLength();
        if (response.length() > max) {
            return response.substring(0, max) + "\n\n[... content truncated at " + max + " chars ...]";
        }
        return response;
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
        path.put("description", "Path to the PDF file");
        properties.put("path", path);

        Map<String, Object> startPage = new HashMap<>();
        startPage.put("type", "integer");
        startPage.put("description", "First page to extract (1-based, default: 1)");
        startPage.put("minimum", 1);
        properties.put("start_page", startPage);

        Map<String, Object> endPage = new HashMap<>();
        endPage.put("type", "integer");
        endPage.put("description", "Last page to extract (default: start_page + 19)");
        endPage.put("minimum", 1);
        properties.put("end_page", endPage);

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
        return "User needs to extract text from PDF documents or read PDF file contents.";
    }

    @Override
    public String getAvoidWhen() {
        return "File is not a PDF or user needs to read plain text, CSV, JSON files.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("pdf", "document", "extract", "read");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Extracted PDF text with metadata header (file path, size, page count, title, author)"
        );
    }

    @Override
    public int getMaxResponseLength() {
        return 15000;
    }

    public record Request(String path, Integer startPage, Integer endPage) {}
}
