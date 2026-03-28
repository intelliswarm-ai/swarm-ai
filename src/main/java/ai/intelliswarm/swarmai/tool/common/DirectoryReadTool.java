package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Directory Read Tool — lists files and directories with optional glob filtering.
 *
 * Features: glob patterns, recursive search, file metadata, sorting, configurable base directory.
 */
@Component
public class DirectoryReadTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryReadTool.class);

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;

    private final Path baseDirectory;

    public DirectoryReadTool() {
        this(null);
    }

    public DirectoryReadTool(Path baseDirectory) {
        this.baseDirectory = baseDirectory != null ? baseDirectory.toAbsolutePath().normalize() : null;
    }

    @Override
    public String getFunctionName() {
        return "directory_read";
    }

    @Override
    public String getDescription() {
        return "List files and directories with optional glob pattern filtering. " +
               "Supports recursive search, sorting, and returns file metadata (name, size, modified date). " +
               "Example patterns: '*.java', '**/*.json', 'src/**/*.yml'";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.getOrDefault("path", ".");
        String pattern = (String) parameters.getOrDefault("pattern", null);
        Boolean recursive = parameters.get("recursive") != null
            ? Boolean.valueOf(parameters.get("recursive").toString())
            : (pattern != null && pattern.contains("**"));
        Integer maxResults = parameters.get("max_results") != null
            ? ((Number) parameters.get("max_results")).intValue()
            : DEFAULT_MAX_RESULTS;

        logger.info("DirectoryReadTool: Listing {} (pattern: {}, recursive: {}, max: {})",
            pathStr, pattern, recursive, maxResults);

        try {
            // 1. Validate and resolve path
            String pathError = validatePath(pathStr);
            if (pathError != null) {
                return "Error: " + pathError;
            }

            Path dirPath = resolvePath(pathStr);

            // 2. Security checks
            String securityError = checkSecurity(dirPath, pathStr);
            if (securityError != null) {
                return "Error: " + securityError;
            }

            // 3. Verify directory exists
            if (!Files.exists(dirPath)) {
                return "Error: Directory not found: " + pathStr;
            }
            if (!Files.isDirectory(dirPath)) {
                return "Error: Not a directory: " + pathStr;
            }

            // 4. Cap max results
            maxResults = Math.min(maxResults, ABSOLUTE_MAX_RESULTS);

            // 5. List files
            List<FileEntry> entries = listFiles(dirPath, pattern, recursive, maxResults);

            // 6. Build response
            return buildResponse(pathStr, dirPath, entries, pattern, recursive, maxResults);

        } catch (IOException e) {
            logger.error("Error listing directory: {}", pathStr, e);
            return "Error: Failed to list directory: " + e.getMessage();
        } catch (Exception e) {
            logger.error("Unexpected error listing directory: {}", pathStr, e);
            return "Error: " + e.getMessage();
        }
    }

    private String validatePath(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) {
            return "Directory path is required";
        }
        if (pathStr.contains("..")) {
            return "Access denied: path traversal (..) is not allowed";
        }
        return null;
    }

    private Path resolvePath(String pathStr) {
        Path dirPath = Paths.get(pathStr.trim()).normalize();
        if (baseDirectory != null && !dirPath.isAbsolute()) {
            dirPath = baseDirectory.resolve(dirPath).normalize();
        }
        return dirPath;
    }

    private String checkSecurity(Path dirPath, String originalPath) {
        if (baseDirectory != null) {
            Path normalizedPath = dirPath.toAbsolutePath().normalize();
            if (!normalizedPath.startsWith(baseDirectory)) {
                return "Access denied: path is outside the allowed base directory";
            }
        }
        return null;
    }

    private List<FileEntry> listFiles(Path dirPath, String pattern, boolean recursive, int maxResults)
            throws IOException {
        List<FileEntry> entries = new ArrayList<>();

        if (pattern != null && !pattern.isEmpty()) {
            // Glob-based search
            String globPattern = recursive ? "glob:" + pattern : "glob:" + pattern;
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);

            if (recursive) {
                Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (entries.size() >= maxResults) return FileVisitResult.TERMINATE;
                        Path relativePath = dirPath.relativize(file);
                        if (matcher.matches(relativePath) || matcher.matches(file.getFileName())) {
                            entries.add(createEntry(file, dirPath, attrs));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                    for (Path entry : stream) {
                        if (entries.size() >= maxResults) break;
                        if (matcher.matches(entry.getFileName())) {
                            entries.add(createEntry(entry, dirPath, Files.readAttributes(entry, BasicFileAttributes.class)));
                        }
                    }
                }
            }
        } else {
            // No pattern — list all
            if (recursive) {
                try (Stream<Path> stream = Files.walk(dirPath)) {
                    List<Path> paths = stream
                        .filter(p -> !p.equals(dirPath))
                        .limit(maxResults)
                        .collect(Collectors.toList());
                    for (Path p : paths) {
                        entries.add(createEntry(p, dirPath, Files.readAttributes(p, BasicFileAttributes.class)));
                    }
                }
            } else {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                    for (Path entry : stream) {
                        if (entries.size() >= maxResults) break;
                        entries.add(createEntry(entry, dirPath, Files.readAttributes(entry, BasicFileAttributes.class)));
                    }
                }
            }
        }

        // Sort: directories first, then by name
        entries.sort(Comparator
            .comparing((FileEntry e) -> !e.isDirectory)
            .thenComparing(e -> e.name));

        return entries;
    }

    private FileEntry createEntry(Path path, Path baseDir, BasicFileAttributes attrs) {
        FileEntry entry = new FileEntry();
        entry.name = baseDir.relativize(path).toString();
        entry.isDirectory = attrs.isDirectory();
        entry.size = attrs.isDirectory() ? 0 : attrs.size();
        entry.lastModified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return entry;
    }

    private String buildResponse(String originalPath, Path dirPath, List<FileEntry> entries,
                                  String pattern, boolean recursive, int maxResults) throws IOException {
        StringBuilder response = new StringBuilder();

        // Metadata header
        response.append("**Directory:** ").append(originalPath).append("\n");
        response.append("**Total entries:** ").append(entries.size());
        if (entries.size() >= maxResults) {
            response.append(" (limited to ").append(maxResults).append(")");
        }
        response.append("\n");
        if (pattern != null) {
            response.append("**Pattern:** ").append(pattern).append("\n");
        }
        response.append("**Recursive:** ").append(recursive).append("\n");
        response.append("\n");

        if (entries.isEmpty()) {
            response.append("(no matching files found)\n");
            return response.toString();
        }

        // Table header
        response.append("| Name | Type | Size | Modified |\n");
        response.append("|------|------|------|----------|\n");

        for (FileEntry entry : entries) {
            response.append("| ").append(entry.name)
                .append(" | ").append(entry.isDirectory ? "DIR" : "FILE")
                .append(" | ").append(entry.isDirectory ? "-" : formatFileSize(entry.size))
                .append(" | ").append(entry.lastModified)
                .append(" |\n");
        }

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
        path.put("description", "Directory path to list (default: current directory)");
        path.put("default", ".");
        properties.put("path", path);

        Map<String, Object> pattern = new HashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "Glob pattern to filter files (e.g., '*.java', '**/*.json', '*.{yml,yaml}')");
        properties.put("pattern", pattern);

        Map<String, Object> recursive = new HashMap<>();
        recursive.put("type", "boolean");
        recursive.put("description", "Search subdirectories recursively (default: auto based on pattern)");
        recursive.put("default", false);
        properties.put("recursive", recursive);

        Map<String, Object> maxResults = new HashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum number of results to return (default: 100, max: 500)");
        maxResults.put("default", DEFAULT_MAX_RESULTS);
        properties.put("max_results", maxResults);

        schema.put("properties", properties);
        schema.put("required", new String[]{"path"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    // ==================== Tool Routing Metadata ====================

    @Override
    public String getTriggerWhen() {
        return "User needs to list files, browse directories, or find files matching a pattern.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs file contents (use file_read) or web data.";
    }

    @Override
    public String getCategory() {
        return "data-io";
    }

    @Override
    public List<String> getTags() {
        return List.of("directory", "file-system", "listing", "glob");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Markdown table of directory entries with name, type, size, and last-modified date"
        );
    }

    @Override
    public String smokeTest() {
        try {
            Path cwd = Path.of(".").toAbsolutePath().normalize();
            if (!Files.isDirectory(cwd)) {
                return "Current working directory is not accessible";
            }
            return null;
        } catch (Exception e) {
            return "Directory access check failed: " + e.getMessage();
        }
    }

    @Override
    public int getMaxResponseLength() {
        return 12000;
    }

    // Internal data class for file entries
    private static class FileEntry {
        String name;
        boolean isDirectory;
        long size;
        String lastModified;
    }

    // Request record for Spring AI function binding
    public record Request(String path, String pattern, Boolean recursive, Integer maxResults) {}
}
