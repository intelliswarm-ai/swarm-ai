package ai.intelliswarm.swarmai.tool.code.search;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every <em>usage</em> of a symbol in the codebase via word-boundary
 * matching on a {@code \bSYMBOL\b} pattern, skipping the definition lines
 * (those are what {@link SymbolSearchTool} returns).
 *
 * <p>By default scans common source extensions; pass {@code lang} to scope
 * to one language. Pass {@code include_definitions=true} to also include
 * declaration sites (useful for refactor planning).
 *
 * @since 1.0.25
 */
@PublicApi(since = "1.0.25")
@Component
public class SymbolReferencesTool implements BaseTool {

    private static final int DEFAULT_LIMIT = 200;
    private static final long MAX_FILE_BYTES = 2_000_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".gradle", ".mvn", ".idea", ".vscode", "__pycache__", ".swarmcode",
            "venv", ".venv", "vendor");

    private static final Set<String> DEFAULT_SOURCE_EXTS = Set.of(
            "java", "kt", "kts", "scala", "sc", "py", "pyi",
            "js", "mjs", "cjs", "jsx", "ts", "tsx",
            "go", "rs", "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
            "rb", "swift", "php", "cs", "groovy");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Path projectRoot;

    public SymbolReferencesTool() { this(null); }

    public SymbolReferencesTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "symbol_references"; }

    @Override
    public String getDescription() {
        return "Find every USAGE of a symbol across source files via word-boundary matching. "
             + "By default skips the definition lines (use 'include_definitions' to keep them). "
             + "Use this to scope a refactor: 'how many call sites touch this method?'";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-search"; }
    @Override public List<String> getTags() { return List.of("symbol", "references", "find"); }
    @Override public boolean isAsync() { return false; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String symbol = (String) parameters.get("symbol");
        if (symbol == null || symbol.isBlank()) return "Error: 'symbol' is required";
        symbol = symbol.trim();
        if (!IDENTIFIER.matcher(symbol).matches()) {
            return "Error: 'symbol' must be a plain identifier, got: '" + symbol + "'";
        }

        String relRoot = (String) parameters.getOrDefault("path", ".");
        int limit = parameters.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;
        boolean includeDefs = Boolean.TRUE.equals(parameters.get("include_definitions"));
        String langHint = (String) parameters.get("lang");

        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path searchRoot = root.resolve(relRoot).normalize();
        if (!searchRoot.startsWith(root)) return "Error: refusing to search outside project root";
        if (!Files.isDirectory(searchRoot)) return "Error: not a directory: " + relRoot;

        Set<String> allowedExts = langHint == null
                ? DEFAULT_SOURCE_EXTS
                : SymbolSearchTool.extensionsFor(langHint);
        if (langHint != null && allowedExts.isEmpty()) {
            return "Error: unsupported lang '" + langHint + "'. "
                 + "Supported: java, kotlin, scala, python, javascript, typescript, go, rust, c, cpp, ruby.";
        }

        final Pattern useRegex = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        final String sym = symbol;
        final Path projectRootForRel = root;
        final boolean includeDefsFinal = includeDefs;

        // file → list of "lineNo: text" hits
        Map<Path, List<String>> hitsByFile = new java.util.LinkedHashMap<>();
        int[] totalCount = {0};

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
                    if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    String ext = SymbolSearchTool.extensionOf(file);
                    if (!allowedExts.contains(ext)) return FileVisitResult.CONTINUE;

                    List<Pattern> defPatterns = includeDefsFinal
                            ? List.of()
                            : SymbolSearchTool.patternsFor(ext, sym);

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            Matcher m = useRegex.matcher(line);
                            if (!m.find()) continue;
                            if (!includeDefsFinal && isDefinitionLine(line, defPatterns)) continue;

                            hitsByFile.computeIfAbsent(file, k -> new ArrayList<>())
                                      .add((i + 1) + ":  " + line.stripLeading());
                            totalCount[0]++;
                            if (totalCount[0] >= limit) return FileVisitResult.TERMINATE;
                        }
                    } catch (IOException ignored) {
                        // skip unreadable
                    }
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

        if (hitsByFile.isEmpty()) {
            return "(no references to '" + symbol + "' found under " + relRoot + ")";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(totalCount[0]).append(" reference")
          .append(totalCount[0] == 1 ? "" : "s").append(" to '").append(symbol)
          .append("' across ").append(hitsByFile.size()).append(" file")
          .append(hitsByFile.size() == 1 ? "" : "s")
          .append(includeDefs ? " (incl. definitions):\n\n" : ":\n\n");
        for (var entry : hitsByFile.entrySet()) {
            sb.append(projectRootForRel.relativize(entry.getKey())).append('\n');
            for (String h : entry.getValue()) sb.append("  ").append(h).append('\n');
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean isDefinitionLine(String line, List<Pattern> defPatterns) {
        for (Pattern p : defPatterns) {
            if (p.matcher(line).find()) return true;
        }
        return false;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", Map.of("type", "string",
                "description", "Identifier to find usages of."));
        props.put("lang", Map.of("type", "string",
                "description", "Optional: scope to one language."));
        props.put("path", Map.of("type", "string",
                "description", "Subdirectory to search (default '.').",
                "default", "."));
        props.put("include_definitions", Map.of("type", "boolean",
                "description", "Also include declaration sites (default false).",
                "default", false));
        props.put("limit", Map.of("type", "integer",
                "description", "Max results (default 200)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"symbol"});
        return schema;
    }
}
