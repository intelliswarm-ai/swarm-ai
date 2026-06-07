package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.safety.GuardedOperation;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PathNotAllowedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Filesystem operations on user-owned directories on a Windows machine,
 * gated by a {@link PathGuard} allowlist and a supervised {@link MutationGuard}.
 *
 * <p>Only single files are operated on by mutating ops in v1. Recursive
 * directory move/copy/delete is intentionally not supported here yet — the
 * blast radius of a misjudged agent is too high.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code list}    — directory listing or single-file info (read-only, no approval)</li>
 *   <li>{@code mkdir}   — create a directory (LOW risk)</li>
 *   <li>{@code move}    — move a single file (MEDIUM)</li>
 *   <li>{@code copy}    — copy a single file (MEDIUM)</li>
 *   <li>{@code rename}  — rename a single file in place (MEDIUM)</li>
 *   <li>{@code delete}  — permanently delete a single file (HIGH)</li>
 * </ul>
 */
public class WindowsFileSystemTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WindowsFileSystemTool.class);
    private static final DateTimeFormatter ISO_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PathGuard pathGuard;
    private final MutationGuard mutationGuard;
    private final boolean dryRunDefault;

    public WindowsFileSystemTool(PathGuard pathGuard, MutationGuard mutationGuard, boolean dryRunDefault) {
        this.pathGuard = Objects.requireNonNull(pathGuard, "pathGuard");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.dryRunDefault = dryRunDefault;
    }

    @Override public String getFunctionName() { return "windows_filesystem"; }

    @Override
    public String getDescription() {
        return "Read or modify files in user-owned directories (Desktop, Downloads, Documents by default) on Windows. "
                + "Read ops (list) run freely; mutations (move/copy/rename/delete/mkdir) are routed through "
                + "an approval gate. Set apply=true to actually run a mutation; otherwise the tool returns a dry-run plan.";
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "windows"; }
    @Override public List<String> getTags() { return List.of("windows", "filesystem", "files", "desktop"); }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().os("windows").build();
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (parameters == null) parameters = Map.of();
        String op = asString(parameters.get("operation"), "list").toLowerCase(Locale.ROOT);
        String pathArg = asString(parameters.get("path"), null);
        String toArg = asString(parameters.get("to"), null);
        String nameArg = asString(parameters.get("name"), null);
        boolean apply = asBoolean(parameters.get("apply"), !dryRunDefault);

        try {
            return switch (op) {
                case "list"   -> doList(pathArg);
                case "mkdir"  -> doMkdir(pathArg, apply);
                case "move"   -> doMove(pathArg, toArg, apply);
                case "copy"   -> doCopy(pathArg, toArg, apply);
                case "rename" -> doRename(pathArg, nameArg, apply);
                case "delete" -> doDelete(pathArg, apply);
                default -> "Error: unknown operation '" + op
                        + "'. Expected one of: list, mkdir, move, copy, rename, delete.";
            };
        } catch (PathNotAllowedException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            logger.warn("Filesystem op failed: op={}, path={}, err={}", op, pathArg, e.getMessage());
            return "Error: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String doList(String pathArg) throws IOException {
        if (pathArg == null || pathArg.isBlank()) {
            return "Allowed roots:\n" + pathGuard.allowedRoots().stream()
                    .map(p -> "- " + p)
                    .collect(Collectors.joining("\n"));
        }
        Path p = pathGuard.require(pathArg);
        if (!Files.exists(p)) {
            return "Error: path does not exist: " + p;
        }
        if (Files.isRegularFile(p)) {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
            return formatFileLine(p, a);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Listing of ").append(p).append("\n\n");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            entries.sort((x, y) -> {
                boolean dx = Files.isDirectory(x), dy = Files.isDirectory(y);
                if (dx != dy) return dx ? -1 : 1;
                return x.getFileName().toString()
                        .compareToIgnoreCase(y.getFileName().toString());
            });
            for (Path e : entries) {
                BasicFileAttributes a;
                try { a = Files.readAttributes(e, BasicFileAttributes.class); }
                catch (IOException ex) { sb.append("- ? ").append(e.getFileName()).append("\n"); continue; }
                sb.append(formatFileLine(e, a)).append("\n");
            }
            if (entries.isEmpty()) sb.append("(empty directory)\n");
        }
        return sb.toString();
    }

    private String doMkdir(String pathArg, boolean apply) throws IOException {
        Path target = pathGuard.require(requireArg(pathArg, "path"));
        if (Files.exists(target)) {
            return Files.isDirectory(target)
                    ? "Already exists (no change): " + target
                    : "Error: a file already exists at " + target;
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Create directory " + target)
                .riskLevel(MutationPlan.RiskLevel.LOW)
                .op("mkdir", target.toString())
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObserved(mutationGuard, d,
                "Created directory: " + target,
                () -> {
                    Files.createDirectories(target);
                    return "Created directory: " + target;
                });
    }

    private String doMove(String pathArg, String toArg, boolean apply) throws IOException {
        Path src = pathGuard.require(requireArg(pathArg, "path"));
        Path dst = pathGuard.require(requireArg(toArg, "to"));
        requireRegularFile(src);
        if (Files.exists(dst)) {
            return "Error: destination already exists: " + dst + " (refusing to overwrite)";
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Move " + src + " → " + dst)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("move", src.toString(), Map.of("to", dst.toString()))
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObserved(mutationGuard, d,
                "Moved " + src + " → " + dst,
                () -> {
                    ensureParentDir(dst);
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
                    return "Moved " + src + " → " + dst;
                });
    }

    private String doCopy(String pathArg, String toArg, boolean apply) throws IOException {
        Path src = pathGuard.require(requireArg(pathArg, "path"));
        Path dst = pathGuard.require(requireArg(toArg, "to"));
        requireRegularFile(src);
        if (Files.exists(dst)) {
            return "Error: destination already exists: " + dst + " (refusing to overwrite)";
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Copy " + src + " → " + dst)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("copy", src.toString(), Map.of("to", dst.toString()))
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObserved(mutationGuard, d,
                "Copied " + src + " → " + dst,
                () -> {
                    ensureParentDir(dst);
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    return "Copied " + src + " → " + dst;
                });
    }

    private String doRename(String pathArg, String name, boolean apply) throws IOException {
        Path src = pathGuard.require(requireArg(pathArg, "path"));
        requireRegularFile(src);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("'name' is required for rename");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException(
                    "'name' must be a plain filename without path separators or '..'");
        }
        Path dst = pathGuard.require(src.resolveSibling(name));
        if (Files.exists(dst)) {
            return "Error: destination already exists: " + dst + " (refusing to overwrite)";
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Rename " + src.getFileName() + " → " + dst.getFileName())
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("rename", src.toString(), Map.of("to", dst.toString()))
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObserved(mutationGuard, d,
                "Renamed " + src + " → " + dst,
                () -> {
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
                    return "Renamed " + src + " → " + dst;
                });
    }

    private String doDelete(String pathArg, boolean apply) throws IOException {
        Path target = pathGuard.require(requireArg(pathArg, "path"));
        if (!Files.exists(target)) {
            return "Error: path does not exist: " + target;
        }
        if (Files.isDirectory(target)) {
            return "Error: directory delete is not supported in v1; remove contents first";
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Permanently delete " + target)
                .riskLevel(MutationPlan.RiskLevel.HIGH)
                .op("delete", target.toString(),
                        Map.of("size", Files.size(target), "permanent", true))
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObserved(mutationGuard, d,
                "Deleted " + target,
                () -> {
                    Files.delete(target);
                    return "Deleted " + target;
                });
    }

    // ---------- helpers ----------

    private static String requireArg(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    private static void requireRegularFile(Path p) throws IOException {
        if (!Files.exists(p)) throw new IOException("path does not exist: " + p);
        if (!Files.isRegularFile(p)) {
            throw new IOException("not a regular file (directory ops not supported in v1): " + p);
        }
    }

    private static void ensureParentDir(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    private String dryRun(MutationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DRY-RUN] ").append(plan.summary()).append("\n");
        sb.append("Risk: ").append(plan.riskLevel()).append("\n");
        sb.append("Ops (").append(plan.opCount()).append("):\n");
        for (MutationPlan.Op op : plan.ops()) {
            sb.append("  - ").append(op.type()).append(' ').append(op.target());
            if (!op.details().isEmpty()) sb.append(' ').append(op.details());
            sb.append('\n');
        }
        sb.append("Pass apply=true to execute (will require approval).");
        return sb.toString();
    }

    private static String rejected(MutationPlan plan, MutationGuard.Decision d) {
        return "Rejected by " + d.decidedBy()
                + (d.reason().isBlank() ? "" : ": " + d.reason())
                + "\nPlan was: " + plan.summary();
    }

    private static String formatFileLine(Path p, BasicFileAttributes a) {
        String type = a.isDirectory() ? "DIR " : "FILE";
        String size = a.isDirectory() ? "" : "  " + a.size() + "b";
        String when = ISO_LOCAL.format(Instant.ofEpochMilli(a.lastModifiedTime().toMillis()));
        return "- " + type + "  " + when + size + "  " + p.getFileName();
    }

    private static String asString(Object v, String def) {
        return v == null ? def : v.toString();
    }

    private static boolean asBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    // ---------- metadata for the LLM ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("operation", schemaProp("string",
                "One of: list, mkdir, move, copy, rename, delete. Defaults to 'list'.",
                List.of("list", "mkdir", "move", "copy", "rename", "delete")));
        props.put("path", schemaProp("string",
                "Source path (file or directory). Required for all ops except a top-level 'list' which returns the allowlist roots.",
                null));
        props.put("to", schemaProp("string",
                "Destination path (used by move and copy).", null));
        props.put("name", schemaProp("string",
                "New file name (used by rename).", null));
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "boolean");
        apply.put("description", "If false (default), return a dry-run plan instead of executing the mutation. "
                + "Set true to request approval and execute.");
        props.put("apply", apply);

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    private static Map<String, Object> schemaProp(String type, String desc, List<String> enums) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", desc);
        if (enums != null) m.put("enum", enums);
        return m;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
                "type", "string",
                "description", "Plain text — directory listing, mutation confirmation, dry-run plan, or 'Error: …' / 'Rejected …'."
        );
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to organise, move, copy, rename, list or delete files on their Windows machine "
                + "(Desktop, Downloads, Documents).";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks to operate on system files outside their home directory, "
                + "or wants recursive directory operations (not supported in v1).";
    }

    public record Request(String operation, String path, String to, String name, Boolean apply) {}

    @Override
    public String smokeTest() {
        if (pathGuard.allowedRoots().isEmpty()) {
            return "WindowsFileSystemTool has no allowed roots configured";
        }
        return null;
    }
}
