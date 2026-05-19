package ai.intelliswarm.swarmai.tool.code.search;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glob-based file finder, results sorted most-recently-modified first.
 *
 * <p>Same {@code **&#47;X} → {@code X} fallback matcher quirk-fix as
 * {@link GrepSearchTool} so the agent's intuition matches the result.
 */
@PublicApi(since = "1.0.25")
@Component
public class GlobFilesTool implements BaseTool {

    private static final int DEFAULT_LIMIT = 200;
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".gradle", ".mvn", ".idea", ".vscode", "__pycache__", ".swarmcode");

    private final Path projectRoot;

    public GlobFilesTool() { this(null); }
    public GlobFilesTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "glob_files"; }

    @Override
    public String getDescription() {
        return "List files in the project matching a glob pattern (e.g. '**/*.java', 'src/**/*Service.java'). "
             + "Results sorted by most-recently-modified first. Use when you know the filename pattern but not the path.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-search"; }
    @Override public List<String> getTags() { return List.of("glob", "find", "files"); }
    @Override public boolean isAsync() { return false; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String glob = (String) parameters.get("pattern");
        if (glob == null || glob.isBlank()) return "Error: 'pattern' is required";
        String relRoot = (String) parameters.getOrDefault("path", ".");
        int limit = parameters.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;

        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path searchRoot = root.resolve(relRoot).normalize();
        if (!searchRoot.startsWith(root)) return "Error: refusing to search outside project root";
        if (!Files.isDirectory(searchRoot)) return "Error: not a directory: " + relRoot;

        // Same fallback as GrepSearchTool — Java NIO PathMatcher `**/X` doesn't match `X`.
        PathMatcher primary = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        PathMatcher fallback = glob.startsWith("**/")
                ? FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3))
                : null;

        List<Path> hits = new ArrayList<>();
        final Path projectRootForRel = root;

        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return SKIP_DIRS.contains(dir.getFileName() != null
                            ? dir.getFileName().toString() : "")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path rel = projectRootForRel.relativize(file);
                    boolean matched = primary.matches(rel) || primary.matches(file.getFileName());
                    if (!matched && fallback != null) {
                        matched = fallback.matches(rel) || fallback.matches(file.getFileName());
                    }
                    if (matched) hits.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return "Error walking project: " + e.getMessage();
        }

        hits.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        if (hits.isEmpty()) return "(no files match '" + glob + "' under " + relRoot + ")";

        StringBuilder sb = new StringBuilder();
        int total = hits.size();
        int shown = Math.min(limit, total);
        sb.append("Matched ").append(total).append(" file").append(total == 1 ? "" : "s");
        if (shown < total) sb.append(" (showing first ").append(shown).append(", most-recent first)");
        sb.append(":\n\n");
        for (int i = 0; i < shown; i++) {
            sb.append(root.relativize(hits.get(i))).append('\n');
        }
        return sb.toString();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", Map.of(
                "type", "string",
                "description", "Glob pattern, e.g. '**/*.java' or 'src/main/**/*Test.java'."));
        props.put("path", Map.of(
                "type", "string",
                "description", "Subdirectory to search under (default '.').",
                "default", "."));
        props.put("limit", Map.of(
                "type", "integer",
                "description", "Maximum results to return (default 200)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"pattern"});
        return schema;
    }
}
