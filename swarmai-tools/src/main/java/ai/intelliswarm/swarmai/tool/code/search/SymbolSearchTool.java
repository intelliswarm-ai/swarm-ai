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
 * Locates the <em>definition</em> of a symbol (class, function, method, etc.)
 * across the project using language-aware regex heuristics.
 *
 * <p>This is intentionally not a full LSP or tree-sitter parser — those are
 * heavy and require per-language servers/bindings. Instead, we recognise the
 * dominant definition syntax for the languages an agent is most likely to
 * touch: Java, Kotlin, Scala, Python, JS/TS, Go, Rust, C/C++, Ruby.
 *
 * <p>Pair with {@link SymbolReferencesTool} to find where the symbol is
 * <em>used</em>; this one only finds where it is <em>declared</em>.
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
@Component
public class SymbolSearchTool implements BaseTool {

    private static final int DEFAULT_LIMIT = 100;
    private static final long MAX_FILE_BYTES = 2_000_000;

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".gradle", ".mvn", ".idea", ".vscode", "__pycache__", ".swarmcode",
            "venv", ".venv", "vendor");

    private final Path projectRoot;

    public SymbolSearchTool() { this(null); }

    public SymbolSearchTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "symbol_search"; }

    @Override
    public String getDescription() {
        return "Find where a symbol (class/function/method/variable) is DEFINED across the project. "
             + "Language-aware: recognises class/def/func/fn/type patterns for Java, Kotlin, Scala, "
             + "Python, JS/TS, Go, Rust, C/C++ and Ruby. Use 'lang' to scope to one language, or omit to search all.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.READ_ONLY; }
    @Override public String getCategory() { return "code-search"; }
    @Override public List<String> getTags() { return List.of("symbol", "definition", "find"); }
    @Override public boolean isAsync() { return false; }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String symbol = (String) parameters.get("symbol");
        if (symbol == null || symbol.isBlank()) return "Error: 'symbol' is required";
        symbol = symbol.trim();
        if (!IDENTIFIER.matcher(symbol).matches()) {
            return "Error: 'symbol' must be a plain identifier (letters/digits/_), got: '" + symbol + "'";
        }

        String relRoot = (String) parameters.getOrDefault("path", ".");
        int limit = parameters.get("limit") instanceof Number n ? n.intValue() : DEFAULT_LIMIT;
        String langHint = (String) parameters.get("lang");

        Path root = projectRoot != null ? projectRoot : Path.of(".").toAbsolutePath().normalize();
        Path searchRoot = root.resolve(relRoot).normalize();
        if (!searchRoot.startsWith(root)) return "Error: refusing to search outside project root";
        if (!Files.isDirectory(searchRoot)) return "Error: not a directory: " + relRoot;

        Set<String> allowedExts = langHint == null ? null : extensionsFor(langHint);
        if (langHint != null && allowedExts.isEmpty()) {
            return "Error: unsupported lang '" + langHint + "'. "
                 + "Supported: java, kotlin, scala, python, javascript, typescript, go, rust, c, cpp, ruby.";
        }

        List<String> hits = new ArrayList<>();
        final Path projectRootForRel = root;
        final String sym = symbol;

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
                    String ext = extensionOf(file);
                    if (allowedExts != null && !allowedExts.contains(ext)) return FileVisitResult.CONTINUE;
                    List<Pattern> patterns = patternsFor(ext, sym);
                    if (patterns.isEmpty()) return FileVisitResult.CONTINUE;

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            for (Pattern p : patterns) {
                                if (p.matcher(line).find()) {
                                    hits.add(projectRootForRel.relativize(file)
                                            + ":" + (i + 1) + ":  " + line.stripLeading());
                                    break;
                                }
                            }
                            if (hits.size() >= limit) return FileVisitResult.TERMINATE;
                        }
                    } catch (IOException ignored) {
                        // Treat unreadable / non-UTF-8 files as misses.
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

        if (hits.isEmpty()) return "(no definition of '" + symbol + "' found under " + relRoot + ")";

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(hits.size()).append(" definition")
          .append(hits.size() == 1 ? "" : "s").append(" of '").append(symbol).append("':\n\n");
        for (String h : hits) sb.append(h).append('\n');
        return sb.toString();
    }

    // --- pattern + extension dispatch -----------------------------------

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    static String extensionOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    static Set<String> extensionsFor(String lang) {
        return switch (lang.toLowerCase()) {
            case "java" -> Set.of("java");
            case "kotlin", "kt" -> Set.of("kt", "kts");
            case "scala" -> Set.of("scala", "sc");
            case "python", "py" -> Set.of("py", "pyi");
            case "javascript", "js" -> Set.of("js", "mjs", "cjs", "jsx");
            case "typescript", "ts" -> Set.of("ts", "tsx");
            case "go" -> Set.of("go");
            case "rust", "rs" -> Set.of("rs");
            case "c" -> Set.of("c", "h");
            case "cpp", "c++", "cxx" -> Set.of("cpp", "cc", "cxx", "hpp", "hh", "hxx", "h");
            case "ruby", "rb" -> Set.of("rb");
            default -> Set.of();
        };
    }

    /** Returns the definition patterns that apply to a given file extension. */
    static List<Pattern> patternsFor(String ext, String sym) {
        String s = Pattern.quote(sym);
        return switch (ext) {
            case "java" -> List.of(
                    // class/interface/enum/record/annotation declarations
                    Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+" + s + "\\b"),
                    // method declarations: optional modifiers, optional generics, return type, name(
                    Pattern.compile("(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+"
                                  + "[\\w<>,\\?\\[\\]\\s\\.]+\\s+" + s + "\\s*\\("),
                    // constructor: same name as class, capitalized → typically uppercase first letter
                    Pattern.compile("(?:public|protected|private)\\s+" + s + "\\s*\\("),
                    // field declarations
                    Pattern.compile("(?:public|protected|private|static|final)\\s+[\\w<>,\\?\\[\\]\\s\\.]+\\s+" + s + "\\s*[=;]"));
            case "kt", "kts" -> List.of(
                    Pattern.compile("\\b(?:class|interface|object|enum class|data class|sealed class)\\s+" + s + "\\b"),
                    Pattern.compile("\\bfun\\s+(?:<[^>]+>\\s+)?" + s + "\\s*\\("),
                    Pattern.compile("\\b(?:val|var)\\s+" + s + "\\b"));
            case "scala", "sc" -> List.of(
                    Pattern.compile("\\b(?:class|trait|object|case class|case object)\\s+" + s + "\\b"),
                    Pattern.compile("\\bdef\\s+" + s + "\\b"),
                    Pattern.compile("\\b(?:val|var)\\s+" + s + "\\s*[:=]"));
            case "py", "pyi" -> List.of(
                    Pattern.compile("^\\s*def\\s+" + s + "\\s*\\("),
                    Pattern.compile("^\\s*async\\s+def\\s+" + s + "\\s*\\("),
                    Pattern.compile("^\\s*class\\s+" + s + "\\b"));
            case "js", "mjs", "cjs", "jsx", "ts", "tsx" -> List.of(
                    Pattern.compile("\\b(?:function|class|interface|type|enum)\\s+" + s + "\\b"),
                    Pattern.compile("\\b(?:const|let|var)\\s+" + s + "\\s*[=:]"),
                    Pattern.compile("\\bexport\\s+(?:default\\s+)?(?:function|class|const|let|var|interface|type|enum)\\s+" + s + "\\b"),
                    Pattern.compile("\\b" + s + "\\s*[:=]\\s*(?:async\\s*)?\\("));
            case "go" -> List.of(
                    Pattern.compile("\\bfunc\\s+(?:\\([^)]+\\)\\s+)?" + s + "\\s*\\("),
                    Pattern.compile("\\btype\\s+" + s + "\\b"));
            case "rs" -> List.of(
                    Pattern.compile("\\bfn\\s+" + s + "\\s*[(<]"),
                    Pattern.compile("\\b(?:struct|enum|trait|union|type|mod)\\s+" + s + "\\b"),
                    Pattern.compile("\\bimpl(?:\\s*<[^>]+>)?\\s+" + s + "\\b"));
            case "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> List.of(
                    Pattern.compile("\\b(?:class|struct|union|enum)\\s+" + s + "\\b"),
                    Pattern.compile("\\btypedef\\s+[\\w\\s,\\*<>]+\\s+" + s + "\\s*[;\\(]"),
                    // function declarations: return_type name( — rough heuristic
                    Pattern.compile("^[\\w\\s\\*&<>:]+\\s+" + s + "\\s*\\([^;]*\\)\\s*\\{?\\s*$"));
            case "rb" -> List.of(
                    Pattern.compile("\\bdef\\s+(?:self\\.)?" + s + "\\b"),
                    Pattern.compile("\\b(?:class|module)\\s+" + s + "\\b"));
            default -> List.of();
        };
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("symbol", Map.of("type", "string",
                "description", "Identifier to locate (e.g. 'FileEditTool', 'parse_config')."));
        props.put("lang", Map.of("type", "string",
                "description", "Optional: scope to one language. "
                             + "java|kotlin|scala|python|javascript|typescript|go|rust|c|cpp|ruby."));
        props.put("path", Map.of("type", "string",
                "description", "Subdirectory to search (default '.').",
                "default", "."));
        props.put("limit", Map.of("type", "integer",
                "description", "Max results (default 100)."));
        schema.put("properties", props);
        schema.put("required", new String[]{"symbol"});
        return schema;
    }
}
