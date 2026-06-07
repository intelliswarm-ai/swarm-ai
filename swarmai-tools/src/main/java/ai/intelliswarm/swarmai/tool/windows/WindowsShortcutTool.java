package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.safety.GuardedOperation;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PathNotAllowedException;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Read and create Windows {@code .lnk} shortcuts via the
 * {@code WScript.Shell} COM object exposed through PowerShell.
 *
 * <p>The {@code path} of the shortcut file itself is checked against the
 * allowlist. The {@code target} (and {@code workingDir}) of the shortcut
 * may point anywhere — a desktop shortcut routinely targets
 * {@code C:\Program Files\…\app.exe}; we do not treat that as a mutation
 * outside the allowlist because the .lnk is just a stored string until
 * the user double-clicks it.
 *
 * <p>Operations: {@code list}, {@code inspect}, {@code create}.
 */
public class WindowsShortcutTool implements BaseTool {

    private final PathGuard pathGuard;
    private final PowerShellRunner ps;
    private final MutationGuard mutationGuard;
    private final Duration timeout;
    private final boolean dryRunDefault;

    public WindowsShortcutTool(PathGuard pathGuard, PowerShellRunner ps, MutationGuard mutationGuard,
                               Duration timeout, boolean dryRunDefault) {
        this.pathGuard = Objects.requireNonNull(pathGuard, "pathGuard");
        this.ps = Objects.requireNonNull(ps, "ps");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.dryRunDefault = dryRunDefault;
    }

    @Override public String getFunctionName() { return "windows_shortcut"; }

    @Override
    public String getDescription() {
        return "List, inspect, or create Windows .lnk shortcuts via WScript.Shell. Shortcut files "
                + "must live under the allowlist roots; their stored target paths may be anywhere "
                + "on the filesystem (we only write the .lnk file).";
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "windows"; }
    @Override public List<String> getTags() { return List.of("windows", "shortcut", "lnk", "desktop"); }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().os("windows").bins("powershell.exe").build();
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (parameters == null) parameters = Map.of();
        String op = asString(parameters.get("operation"), "list").toLowerCase(Locale.ROOT);
        boolean apply = asBoolean(parameters.get("apply"), !dryRunDefault);

        try {
            return switch (op) {
                case "list"    -> doList(asString(parameters.get("folder"), null));
                case "inspect" -> doInspect(asString(parameters.get("path"), null));
                case "create"  -> doCreate(
                        asString(parameters.get("path"), null),
                        asString(parameters.get("target"), null),
                        asString(parameters.get("args"), null),
                        asString(parameters.get("workingDir"), null),
                        asString(parameters.get("icon"), null),
                        apply);
                default -> "Error: unknown operation '" + op + "'. Expected: list, inspect, create.";
            };
        } catch (PathNotAllowedException | IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String doList(String folderArg) {
        Path folder = folderArg == null || folderArg.isBlank()
                ? defaultListFolder()
                : pathGuard.require(folderArg);
        if (folder == null) {
            return "Error: no allowed roots configured; cannot list shortcuts";
        }
        String script = "Get-ChildItem -Path '" + escape(folder.toString())
                + "' -Filter *.lnk -ErrorAction SilentlyContinue "
                + "| Select-Object Name,LastWriteTime,Length "
                + "| ConvertTo-Csv -NoTypeInformation";
        PowerShellRunner.Result r = ps.run(script, timeout);
        if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
        return r.stdout().isBlank() ? "(no .lnk files in " + folder + ")" : r.stdout();
    }

    private String doInspect(String pathArg) {
        Path file = pathGuard.require(requireArg(pathArg, "path"));
        String script = "$wsh = New-Object -ComObject WScript.Shell; "
                + "$s = $wsh.CreateShortcut('" + escape(file.toString()) + "'); "
                + "@{TargetPath=$s.TargetPath; Arguments=$s.Arguments; WorkingDirectory=$s.WorkingDirectory; "
                + "IconLocation=$s.IconLocation; Description=$s.Description} "
                + "| ConvertTo-Json -Compress";
        PowerShellRunner.Result r = ps.run(script, timeout);
        if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
        return r.stdout();
    }

    private String doCreate(String pathArg, String target, String args,
                            String workingDir, String icon, boolean apply) {
        Path file = pathGuard.require(requireArg(pathArg, "path"));
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("'target' is required for create");
        }
        if (!file.toString().toLowerCase(Locale.ROOT).endsWith(".lnk")) {
            throw new IllegalArgumentException("'path' must end in .lnk");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("target", target);
        if (args != null) details.put("args", args);
        if (workingDir != null) details.put("workingDir", workingDir);
        if (icon != null) details.put("icon", icon);

        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Create shortcut " + file + " → " + target)
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("create-shortcut", file.toString(), details)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Created shortcut " + file,
                () -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("$wsh = New-Object -ComObject WScript.Shell; ");
                    sb.append("$s = $wsh.CreateShortcut('").append(escape(file.toString())).append("'); ");
                    sb.append("$s.TargetPath = '").append(escape(target)).append("'; ");
                    if (args != null && !args.isBlank()) {
                        sb.append("$s.Arguments = '").append(escape(args)).append("'; ");
                    }
                    if (workingDir != null && !workingDir.isBlank()) {
                        sb.append("$s.WorkingDirectory = '").append(escape(workingDir)).append("'; ");
                    }
                    if (icon != null && !icon.isBlank()) {
                        sb.append("$s.IconLocation = '").append(escape(icon)).append("'; ");
                    }
                    sb.append("$s.Save(); Write-Output 'created'");

                    PowerShellRunner.Result r = ps.run(sb.toString(), timeout);
                    if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                            + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
                    return "Created shortcut: " + file;
                });
    }

    // ---------- helpers ----------

    private Path defaultListFolder() {
        for (Path root : pathGuard.allowedRoots()) {
            if (root.endsWith("Desktop")) return root;
        }
        return pathGuard.allowedRoots().isEmpty() ? null : pathGuard.allowedRoots().get(0);
    }

    private static String requireArg(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + name + "' is required");
        }
        return value;
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    private String dryRun(MutationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DRY-RUN] ").append(plan.summary()).append("\n");
        sb.append("Risk: ").append(plan.riskLevel()).append("\n");
        for (MutationPlan.Op op : plan.ops()) {
            sb.append("  - ").append(op.type()).append(' ').append(op.target())
                    .append(' ').append(op.details()).append('\n');
        }
        sb.append("Pass apply=true to execute (will require approval).");
        return sb.toString();
    }

    private static String rejected(MutationPlan plan, MutationGuard.Decision d) {
        return "Rejected by " + d.decidedBy()
                + (d.reason().isBlank() ? "" : ": " + d.reason())
                + "\nPlan was: " + plan.summary();
    }

    private static String asString(Object v, String def) { return v == null ? def : v.toString(); }

    private static boolean asBoolean(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y");
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", schemaProp("string", "list | inspect | create (default: list)",
                List.of("list", "inspect", "create")));
        props.put("folder", schemaProp("string",
                "Folder to list .lnk files in (default: Desktop if allowlisted).", null));
        props.put("path", schemaProp("string",
                "For inspect/create: path to the .lnk file (must be under allowlist).", null));
        props.put("target", schemaProp("string",
                "For create: the target path the shortcut points to. May be anywhere.", null));
        props.put("args", schemaProp("string", "For create: arguments string for the target.", null));
        props.put("workingDir", schemaProp("string", "For create: working directory.", null));
        props.put("icon", schemaProp("string", "For create: icon location, e.g. 'C:\\Program Files\\App\\app.exe,0'.", null));
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "boolean");
        apply.put("description", "If false (default), return a dry-run plan; if true, request approval and execute.");
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
        return Map.of("type", "string",
                "description", "CSV listing, JSON shortcut details, 'Created shortcut: …', dry-run plan, error.");
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to list, inspect, or create desktop or Start-menu shortcuts (.lnk files) on Windows.";
    }

    @Override
    public String getAvoidWhen() {
        return "User wants to install software (use the OS package manager / installer instead) "
                + "or modify the registry.";
    }

    public record Request(String operation, String folder, String path,
                          String target, String args, String workingDir, String icon, Boolean apply) {}
}
