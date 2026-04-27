package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.os.shared.PathGuard;
import ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Filesystem operations on user-owned directories — runs unchanged on Windows,
 * macOS, and Linux because the underlying {@link java.nio.file.Files} API is
 * itself cross-platform. The Windows-specific {@code WindowsFileSystemTool} is
 * preserved for backward compatibility; new code should depend on this one
 * via the {@code os_filesystem} bean.
 *
 * <p>{@code list}/{@code stat} are read-only and run freely. {@code mkdir},
 * {@code copy}, {@code move}, {@code rename}, {@code delete}, {@code write_text},
 * {@code append_text} mutate state and route through the shared
 * {@link MutationGuard} (kill-switch + audit log + approval gate).
 *
 * <p>Mutating ops accept an {@code apply} flag — {@code false} (the default unless
 * the deployment opts in to {@code dryRunDefault=false}) returns the planned action
 * without executing, mirroring the Windows tool's pattern.
 */
public class FileSystemTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemTool.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());

    private final PathGuard pathGuard;
    private final MutationGuard mutationGuard;
    private final boolean dryRunDefault;

    public FileSystemTool(PathGuard pathGuard, MutationGuard mutationGuard, boolean dryRunDefault) {
        this.pathGuard = Objects.requireNonNull(pathGuard, "pathGuard");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.dryRunDefault = dryRunDefault;
    }

    public record Request(String operation, String path, String to, String content, Boolean apply) {}

    @Override
    public String getFunctionName() { return "os_filesystem"; }

    @Override
    public String getDescription() {
        return "Read or modify files in user-owned directories. Cross-platform — runs the same " +
               "on Windows, macOS, and Linux. Read ops (list, stat) run freely; mutations " +
               "(mkdir, copy, move, rename, delete, write_text, append_text) route through the " +
               "approval gate. Mutations require apply=true; without it the tool returns the " +
               "planned action only.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String op = asLower(parameters.get("operation"));
        if (op == null) {
            return error("missing 'operation' (one of: list, stat, mkdir, copy, move, rename, delete, write_text, append_text)");
        }
        boolean apply = parameters.get("apply") instanceof Boolean b ? b : !dryRunDefault;
        try {
            return switch (op) {
                case "list"        -> doList(parameters);
                case "stat"        -> doStat(parameters);
                case "mkdir"       -> doMkdir(parameters, apply);
                case "copy"        -> doCopy(parameters, apply);
                case "move"        -> doMove(parameters, apply);
                case "rename"      -> doRename(parameters, apply);
                case "delete"      -> doDelete(parameters, apply);
                case "write_text"  -> doWriteText(parameters, apply, false);
                case "append_text" -> doWriteText(parameters, apply, true);
                default            -> error("unknown operation '" + op + "'");
            };
        } catch (PathNotAllowedException e) {
            return error("path-not-allowed: " + e.getMessage());
        } catch (Exception e) {
            logger.warn("FileSystemTool {} failed: {}", op, e.getMessage());
            return error(op + " failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", Map.of("type", "string",
                "enum", List.of("list", "stat", "mkdir", "copy", "move", "rename", "delete", "write_text", "append_text"),
                "description", "Filesystem operation to perform."));
        props.put("path", Map.of("type", "string", "description", "Target path (and source for copy/move/rename)."));
        props.put("to",   Map.of("type", "string", "description", "Destination path (copy/move/rename)."));
        props.put("content", Map.of("type", "string", "description", "Text content (write_text / append_text)."));
        props.put("apply",   Map.of("type", "boolean",
                "description", "true to actually execute the mutation; false (default) returns the plan only."));
        return Map.of("type", "object", "properties", props, "required", List.of("operation"));
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public String getCategory() { return "os-control"; }

    @Override
    public List<String> getTags() { return List.of("os-control", "filesystem", "files", "cross-platform"); }

    @Override
    public ToolRequirements getRequirements() {
        return new ToolRequirements(List.of(), List.of(), List.of(), List.of("windows", "darwin", "linux"));
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to list, copy, move, create, or delete files in their own directories.";
    }

    @Override
    public String getAvoidWhen() {
        return "Operation needs to read or modify a file outside the configured allowlist roots, " +
               "or is a system-level path (Program Files, /etc, /System, etc.).";
    }

    // ---------- read ops (no approval) ----------

    private Object doList(Map<String, Object> p) throws IOException {
        Path target = pathGuard.require(requireArg(p.get("path"), "path"));
        if (!Files.exists(target)) return error("does not exist: " + target);
        if (!Files.isDirectory(target)) return doStatPath(target);
        List<Map<String, Object>> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
            for (Path entry : ds) {
                BasicFileAttributes a = Files.readAttributes(entry, BasicFileAttributes.class);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", entry.getFileName().toString());
                e.put("type", a.isDirectory() ? "dir" : a.isRegularFile() ? "file" : "other");
                e.put("size", a.size());
                e.put("mtime", ISO.format(a.lastModifiedTime().toInstant()));
                entries.add(e);
            }
        }
        return Map.of("path", target.toString(), "entries", entries, "count", entries.size());
    }

    private Object doStat(Map<String, Object> p) throws IOException {
        Path target = pathGuard.require(requireArg(p.get("path"), "path"));
        if (!Files.exists(target)) return error("does not exist: " + target);
        return doStatPath(target);
    }

    private Map<String, Object> doStatPath(Path target) throws IOException {
        BasicFileAttributes a = Files.readAttributes(target, BasicFileAttributes.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", target.toString());
        out.put("type", a.isDirectory() ? "dir" : a.isRegularFile() ? "file" : "other");
        out.put("size", a.size());
        out.put("mtime", ISO.format(a.lastModifiedTime().toInstant()));
        out.put("ctime", ISO.format(a.creationTime().toInstant()));
        return out;
    }

    // ---------- mutating ops (planned + approved) ----------

    private Object doMkdir(Map<String, Object> p, boolean apply) throws IOException {
        Path target = pathGuard.require(requireArg(p.get("path"), "path"));
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Create directory " + target)
                .riskLevel(MutationPlan.RiskLevel.LOW)
                .op("mkdir", target.toString())
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        if (!approve(plan)) return rejected(plan);
        Files.createDirectories(target);
        return Map.of("ok", true, "op", "mkdir", "path", target.toString());
    }

    private Object doCopy(Map<String, Object> p, boolean apply) throws IOException {
        Path src = pathGuard.require(requireArg(p.get("path"), "path"));
        Path dst = pathGuard.require(requireArg(p.get("to"), "to"));
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Copy " + src + " → " + dst)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("copy", src.toString(), Map.of("to", dst.toString()))
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        if (!approve(plan)) return rejected(plan);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("ok", true, "op", "copy", "source", src.toString(), "target", dst.toString());
    }

    private Object doMove(Map<String, Object> p, boolean apply) throws IOException {
        Path src = pathGuard.require(requireArg(p.get("path"), "path"));
        Path dst = pathGuard.require(requireArg(p.get("to"), "to"));
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Move " + src + " → " + dst)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", src.toString(), Map.of("to", dst.toString()))
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        if (!approve(plan)) return rejected(plan);
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return Map.of("ok", true, "op", "move", "source", src.toString(), "target", dst.toString());
    }

    private Object doRename(Map<String, Object> p, boolean apply) throws IOException {
        // Rename is move-in-same-directory; same risk profile.
        return doMove(p, apply);
    }

    private Object doDelete(Map<String, Object> p, boolean apply) throws IOException {
        Path target = pathGuard.require(requireArg(p.get("path"), "path"));
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Delete " + target)
                .riskLevel(MutationPlan.RiskLevel.HIGH)
                .op("delete", target.toString())
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        if (!approve(plan)) return rejected(plan);
        Files.delete(target);
        return Map.of("ok", true, "op", "delete", "path", target.toString());
    }

    private Object doWriteText(Map<String, Object> p, boolean apply, boolean append) throws IOException {
        Path target = pathGuard.require(requireArg(p.get("path"), "path"));
        // Don't trim content — leading/trailing whitespace (incl. newlines) is meaningful
        // for the file body, unlike the path/operation fields.
        Object raw = p.get("content");
        String content = raw == null ? null : raw.toString();
        if (content == null) return error("missing 'content'");
        String op = append ? "append_text" : "write_text";
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary(op + " (" + content.length() + " chars) → " + target)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op(op, target.toString(), Map.of("bytes", content.getBytes().length))
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        if (!approve(plan)) return rejected(plan);
        if (append) {
            Files.writeString(target, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(target, content);
        }
        return Map.of("ok", true, "op", op, "path", target.toString(), "bytes", content.getBytes().length);
    }

    // ---------- safety helpers ----------

    private boolean approve(MutationPlan plan) {
        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) {
            logger.info("FileSystemTool plan rejected by {}: {}", d.decidedBy(), d.reason());
        }
        return d.approved();
    }

    private static Object planResponse(MutationPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planned", true);
        m.put("summary", plan.summary());
        m.put("risk", plan.riskLevel().name());
        m.put("ops", plan.ops().stream().map(o -> Map.of("type", o.type(), "target", o.target())).toList());
        m.put("hint", "re-invoke with apply=true to execute");
        return m;
    }

    private Object rejected(MutationPlan plan) {
        return error("mutation rejected: " + plan.summary());
    }

    private static String requireArg(Object v, String name) {
        String s = asString(v);
        if (s == null) throw new IllegalArgumentException("missing '" + name + "'");
        return s;
    }

    private static String asLower(Object v) {
        if (v == null) return null;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
