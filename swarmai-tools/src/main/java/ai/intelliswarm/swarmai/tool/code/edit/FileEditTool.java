package ai.intelliswarm.swarmai.tool.code.edit;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact-string file editor — the small-diff alternative to {@code FileWriteTool}.
 *
 * <p>Hard rule: {@code old_string} must occur EXACTLY ONCE in the file unless
 * {@code replace_all} is true. This forces the caller to provide enough
 * context to disambiguate the target and prevents accidental collateral edits.
 *
 * <p>Safety:
 * <ul>
 *   <li>Refuses to edit outside the optional {@code projectRoot}.</li>
 *   <li>Refuses files matching common sensitive-content patterns
 *       ({@code .env}, {@code credentials}, {@code *.pem}, etc.).</li>
 * </ul>
 *
 * <p>This tool is a useful complement to {@link ai.intelliswarm.swarmai.tool.common.FileWriteTool}:
 * use {@code FileEditTool} for small, targeted modifications; use
 * {@code FileWriteTool} for new files or full rewrites.
 */
@PublicApi(since = "1.0.25")
@Component
public class FileEditTool implements BaseTool {

    private static final List<String> DENIED_PATTERNS = List.of(
            ".env", "credentials", "secret", "password", "private_key",
            ".pem", ".key", ".p12", ".jks", ".keystore", "id_rsa", "id_ed25519");

    private final Path projectRoot;

    /** No-arg ctor for Spring auto-wiring — no project-root sandbox. */
    public FileEditTool() {
        this(null);
    }

    /** Construct with an optional project root; edits outside it are refused. */
    public FileEditTool(Path projectRoot) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    @Override public String getFunctionName() { return "file_edit"; }

    @Override
    public String getDescription() {
        return "Edit an existing file by replacing an exact string. "
             + "old_string must be unique in the file (or set replace_all=true). "
             + "Preserve indentation exactly as it appears in the file. "
             + "Prefer this over file_write for modifications — it minimizes diff and prevents accidental overwrites.";
    }

    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }
    @Override public String getCategory() { return "code-edit"; }
    @Override public List<String> getTags() { return List.of("edit", "write", "patch", "code"); }
    @Override public boolean isAsync() { return false; }

    @Override
    public String getTriggerWhen() {
        return "User needs to make a precise change to an existing file, especially when only a small region is changing.";
    }

    @Override
    public String getAvoidWhen() {
        return "Creating a new file or completely rewriting one — use file_write instead.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String pathStr = (String) parameters.get("path");
        String oldString = (String) parameters.get("old_string");
        String newString = (String) parameters.get("new_string");
        boolean replaceAll = Boolean.TRUE.equals(parameters.get("replace_all"));

        if (pathStr == null || pathStr.isBlank()) return "Error: 'path' is required";
        if (oldString == null) return "Error: 'old_string' is required";
        if (newString == null) return "Error: 'new_string' is required";
        if (oldString.equals(newString)) {
            return "Error: old_string and new_string are identical — no edit needed";
        }

        Path file = projectRoot != null
                ? projectRoot.resolve(pathStr).normalize()
                : Path.of(pathStr).normalize();

        if (projectRoot != null && !file.startsWith(projectRoot)) {
            return "Error: refusing to edit outside the project root";
        }

        String lower = file.toString().toLowerCase();
        for (String pat : DENIED_PATTERNS) {
            if (lower.contains(pat)) {
                return "Error: refusing to edit sensitive file matching '" + pat + "'";
            }
        }

        if (!Files.isRegularFile(file)) {
            return "Error: not a regular file: " + pathStr;
        }

        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }

        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return "Error: old_string not found in " + pathStr + ". "
                 + "Hint: check whitespace/indentation matches the file exactly.";
        }
        if (occurrences > 1 && !replaceAll) {
            return "Error: old_string occurs " + occurrences + " times in " + pathStr + ". "
                 + "Provide more context to make it unique, or set replace_all=true.";
        }

        String updated = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirst(content, oldString, newString);

        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }

        int linesDelta = countLines(newString) - countLines(oldString);
        return String.format("Edited %s — replaced %d occurrence%s (%+d lines).",
                pathStr, occurrences, occurrences == 1 ? "" : "s",
                replaceAll ? linesDelta * occurrences : linesDelta);
    }

    static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    static String replaceFirst(String haystack, String needle, String repl) {
        int i = haystack.indexOf(needle);
        return haystack.substring(0, i) + repl + haystack.substring(i + needle.length());
    }

    static int countLines(String s) {
        if (s.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", Map.of(
                "type", "string",
                "description", "File path relative to the project root (or absolute when no root is configured)."));
        props.put("old_string", Map.of(
                "type", "string",
                "description", "Exact text to find. Must be unique in the file unless replace_all=true. Preserve indentation."));
        props.put("new_string", Map.of(
                "type", "string",
                "description", "Replacement text. Must differ from old_string."));
        props.put("replace_all", Map.of(
                "type", "boolean",
                "description", "Replace every occurrence (default false).",
                "default", false));
        schema.put("properties", props);
        schema.put("required", new String[]{"path", "old_string", "new_string"});
        return schema;
    }
}
