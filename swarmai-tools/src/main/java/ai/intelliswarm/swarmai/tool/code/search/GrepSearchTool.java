package ai.intelliswarm.swarmai.tool.code.search;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Recursive regex search across project files — the "grep" of the framework.
 *
 * <p>Features:
 * <ul>
 *   <li>Java regex (case-sensitive by default).</li>
 *   <li>Glob path filter ({@code path_glob}); fallback matcher rewrites
 *       {@code **&#47;X} → {@code X} so root-level files match too (works
 *       around a Java NIO PathMatcher quirk).</li>
 *   <li>Optional context lines around each match.</li>
 *   <li>Skips common build-output / VCS directories
 *       ({@code .git}, {@code target}, {@code node_modules}, …).</li>
 *   <li>Per-file size cap ({@code 5 MB}) and overall match cap.</li>
 * </ul>
 */
@PublicApi(since = "1.0.26")
@Component
public class GrepSearchTool implements BaseTool {

    private static final int DEFAULT_MAX_MATCHES = 200;
    private static final int DEFAULT_CONTEXT_LINES = 0;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".gradle", ".mvn", ".idea", ".vscode", "__pycache__", ".swarmcode");

    private final Path projectRoot;

    public GrepSearchTool() { this(null); }
    public GrepSearchTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "grep_search"; }

    @Override
    public String getDescription() {
        return "Search file contents for a regex pattern across the project. Returns matching lines "
             + "with file:line prefix. Supports glob path filter, case-insensitive mode, and "
             + "context lines. Far more efficient than reading files one by one.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-search"; }
    @Override public List<String> getTags() { return List.of("search", "grep", "regex", "code"); }
    @Override public boolean isAsync() { return false; }
    @Override public int getMaxResponseLength() { return 16_000; }

    @Override
    public String getTriggerWhen() {
        return "User wants to find code, symbols, function definitions, references, or text patterns across the codebase.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pattern = (String) parameters.get("pattern");
        if (pattern == null || pattern.isBlank()) return "Error: 'pattern' is required";
        String pathGlob = (String) parameters.getOrDefault("path_glob", "**/*");
        String relativePath = (String) parameters.getOrDefault("path", ".");
        boolean caseInsensitive = Boolean.TRUE.equals(parameters.get("case_insensitive"));
        int maxMatches = parseInt(parameters.get("max_matches"), DEFAULT_MAX_MATCHES);
        int contextLines = Math.max(0, parseInt(parameters.get("context_lines"), DEFAULT_CONTEXT_LINES));

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex — " + e.getMessage();
        }

        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path searchRoot = root.resolve(relativePath).normalize();
        if (!searchRoot.startsWith(root)) {
            return "Error: refusing to search outside the project root";
        }
        if (!Files.isDirectory(searchRoot)) {
            return "Error: not a directory: " + relativePath;
        }

        // Java NIO globs like `**/X` don't match top-level `X`. Add a fallback
        // that strips the leading `**/` so root-level files match too.
        PathMatcher primary = FileSystems.getDefault().getPathMatcher("glob:" + pathGlob);
        PathMatcher fallback = pathGlob.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + pathGlob.substring(3))
                : null;

        StringBuilder out = new StringBuilder();
        int[] matchCount = {0};
        int[] fileCount = {0};
        final Path projectRootForRel = root;

        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName() != null
                            ? dir.getFileName().toString() : "")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matchCount[0] >= maxMatches) return FileVisitResult.TERMINATE;
                    if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    Path rel = projectRootForRel.relativize(file);
                    boolean matched = primary.matches(rel) || primary.matches(file.getFileName());
                    if (!matched && fallback != null) {
                        matched = fallback.matches(rel) || fallback.matches(file.getFileName());
                    }
                    if (!matched) return FileVisitResult.CONTINUE;
                    searchFile(file, rel, compiled, contextLines, maxMatches, matchCount, fileCount, out);
                    return matchCount[0] >= maxMatches
                            ? FileVisitResult.TERMINATE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "Error walking project: " + e.getMessage();
        }

        if (matchCount[0] == 0) {
            return "(no matches for /" + pattern + "/ under " + relativePath + ")";
        }
        String header = String.format("Found %d match%s across %d file%s%s%n%n",
                matchCount[0], matchCount[0] == 1 ? "" : "es",
                fileCount[0], fileCount[0] == 1 ? "" : "s",
                matchCount[0] >= maxMatches ? " (truncated)" : "");
        return header + out.toString();
    }

    private void searchFile(Path file, Path rel, Pattern compiled, int contextLines,
                            int maxMatches, int[] matchCount, int[] fileCount, StringBuilder out) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        boolean anyMatchInFile = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = compiled.matcher(lines.get(i));
            if (!m.find()) continue;
            if (matchCount[0] >= maxMatches) return;
            anyMatchInFile = true;
            int start = Math.max(0, i - contextLines);
            int end = Math.min(lines.size(), i + contextLines + 1);
            for (int j = start; j < end; j++) {
                char sep = (j == i) ? ':' : '-';
                out.append(rel).append(sep).append(j + 1).append(sep)
                        .append(truncate(lines.get(j), 240)).append('\n');
            }
            if (contextLines > 0) out.append("--\n");
            matchCount[0]++;
        }
        if (anyMatchInFile) fileCount[0]++;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + " …";
    }

    private static int parseInt(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return dflt; }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of(
                "type", "string",
                "description", "Java regex to search for (case-sensitive unless case_insensitive=true)."));
        props.put("path", Map.of(
                "type", "string",
                "description", "Subdirectory relative to project root (default: '.').",
                "default", "."));
        props.put("path_glob", Map.of(
                "type", "string",
                "description", "Glob to filter files (e.g. '**/*.java'). Default '**/*'.",
                "default", "**/*"));
        props.put("case_insensitive", Map.of(
                "type", "boolean",
                "description", "Case-insensitive match. Default false.",
                "default", false));
        props.put("max_matches", Map.of(
                "type", "integer",
                "description", "Cap on total matches returned. Default 200."));
        props.put("context_lines", Map.of(
                "type", "integer",
                "description", "Lines of context around each match. Default 0."));
        schema.put("properties", props);
        schema.put("required", new String[]{"pattern"});
        return schema;
    }
}
