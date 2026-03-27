package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File Write Tool — writes content to files on the filesystem.
 *
 * Supports: create, overwrite, and append modes.
 * Features: auto-create parent directories, configurable base directory, security checks.
 */
@Component
public class FileWriteTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(FileWriteTool.class);

    private static final long MAX_CONTENT_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    // Patterns that are denied for security reasons
    private static final List<String> DENIED_PATTERNS = List.of(
        ".env", "credentials", "secret", "password", "private_key",
        ".pem", ".key", ".p12", ".jks", ".keystore", "id_rsa", "id_ed25519",
        ".bash_history", ".ssh", "authorized_keys"
    );

    // Extensions that should never be written (executables, scripts)
    private static final List<String> DENIED_EXTENSIONS = List.of(
        ".exe", ".bat", ".cmd", ".sh", ".ps1", ".vbs", ".com", ".msi",
        ".dll", ".so", ".dylib", ".class", ".jar", ".war"
    );

    private final Path baseDirectory;

    public FileWriteTool() {
        this(null);
    }

    public FileWriteTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getFunctionName() {
        return "file_write";
    }

    @Override
    public String getDescription() {
        return "Write content to a file on the filesystem. Supports create, overwrite, and append modes. " +
               "Automatically creates parent directories. Returns confirmation with file path and size.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.get("path");
        String content = (String) parameters.get("content");
        String mode = (String) parameters.getOrDefault("mode", "overwrite");

        logger.info("FileWriteTool: Writing file: {} (mode: {})", pathStr, mode);

        try {
            // 1. Validate inputs
            String inputError = validateInputs(pathStr, content, mode);
            if (inputError != null) {
                return "Error: " + inputError;
            }

            Path filePath = resolvePath(pathStr);

            // 2. Security checks
            String securityError = checkSecurity(filePath, pathStr);
            if (securityError != null) {
                return "Error: " + securityError;
            }

            // 3. Check content size
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_SIZE_BYTES) {
                return String.format("Error: Content too large (max %d bytes)", MAX_CONTENT_SIZE_BYTES);
            }

            // 4. Create parent directories if needed
            Path parentDir = filePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                logger.info("Created parent directories: {}", parentDir);
            }

            // 5. Write content based on mode
            long bytesWritten = writeContent(filePath, content, mode.toLowerCase());

            // 6. Build success response
            return buildResponse(pathStr, filePath, bytesWritten, mode, content);

        } catch (IOException e) {
            logger.error("Error writing file: {}", pathStr, e);
            return "Error: Failed to write file: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error writing file: {}", pathStr, e);
            return "Error: " + e.getMessage();
        }
    }

    private String validateInputs(String pathStr, String content, String mode) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            return "File path is required";
        }
        if (content == null) {
            return "Content is required (use empty string to create empty file)";
        }
        if (!List.of("overwrite", "append", "create").contains(mode.toLowerCase())) {
            return "Invalid mode: '" + mode + "'. Must be one of: overwrite, append, create";
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
        if (originalPath.contains("..")) {
            return "Access denied: path traversal (..) is not allowed";
        }

        if (baseDirectory != null) {
            Path normalizedPath = filePath.toAbsolutePath().normalize();
            if (!normalizedPath.startsWith(baseDirectory)) {
                return "Access denied: path is outside the allowed base directory";
            }
        }

        // Check denied file patterns
        String fileName = filePath.getFileName().toString().toLowerCase();
        String fullPath = filePath.toString().toLowerCase();
        for (String pattern : DENIED_PATTERNS) {
            if (fileName.contains(pattern) || fullPath.contains(pattern)) {
                return "Access denied: writing files matching '" + pattern + "' is not allowed for security reasons";
            }
        }

        // Check denied extensions
        for (String ext : DENIED_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return "Access denied: writing '" + ext + "' files is not allowed for security reasons";
            }
        }

        return null;
    }

    private long writeContent(Path filePath, String content, String mode) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        switch (mode) {
            case "create" -> {
                if (Files.exists(filePath)) {
                    throw new IOException("File already exists and mode is 'create': " + filePath);
                }
                Files.write(filePath, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
            case "append" -> {
                Files.write(filePath, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            }
            default -> { // overwrite
                Files.write(filePath, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        }

        return bytes.length;
    }

    private String buildResponse(String originalPath, Path filePath, long bytesWritten,
                                  String mode, String content) {
        int lineCount = content.isEmpty() ? 0 : content.split("\n", -1).length;

        StringBuilder response = new StringBuilder();
        response.append("**File written successfully**\n");
        response.append("**Path:** ").append(originalPath).append("\n");
        response.append("**Size:** ").append(formatFileSize(bytesWritten)).append("\n");
        response.append("**Lines:** ").append(lineCount).append("\n");
        response.append("**Mode:** ").append(mode).append("\n");

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
        path.put("description", "File path to write (relative to working directory or absolute)");
        properties.put("path", path);

        Map<String, Object> content = new HashMap<>();
        content.put("type", "string");
        content.put("description", "Content to write to the file");
        properties.put("content", content);

        Map<String, Object> mode = new HashMap<>();
        mode.put("type", "string");
        mode.put("description", "Write mode: 'overwrite' (replace file), 'append' (add to end), 'create' (fail if exists). Default: overwrite");
        mode.put("default", "overwrite");
        mode.put("enum", List.of("overwrite", "append", "create"));
        properties.put("mode", mode);

        schema.put("properties", properties);
        schema.put("required", new String[]{"path", "content"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ==================== OpenClaw Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to save output, write reports, create files, or persist data to disk.";
    }

    @Override
    public String getAvoidWhen() {
        return "User only needs to display results, or data should go to an API instead.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("file", "write", "save", "output");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Confirmation message with file path, size, line count, and write mode"
        );
    }

    @Override
    public String smokeTest() {
        try {
            Path tempFile = Files.createTempFile("swarm-file-write-smoke", ".txt");
            Files.writeString(tempFile, "smoke-test");
            Files.deleteIfExists(tempFile);
            return null;
        } catch (IOException e) {
            return "File system write check failed: " + e.getMessage();
        }
    }

    // Request record for Spring AI function binding
    public record Request(String path, String content, String mode) {}
}
