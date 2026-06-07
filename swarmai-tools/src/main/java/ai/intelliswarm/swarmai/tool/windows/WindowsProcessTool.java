package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.safety.GuardedOperation;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Inspect, start and kill processes on a Windows machine via PowerShell.
 *
 * <p>{@code list} is read-only and runs without approval. {@code start} and
 * {@code kill} are routed through a {@link MutationGuard}. {@code kill} also
 * checks a configurable denylist (system processes) and refuses outright,
 * even if the user approves.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code list(filter?)} — list running processes (optional name substring filter)</li>
 *   <li>{@code start(path, args?)} — launch an executable</li>
 *   <li>{@code kill(pid? | name?)} — stop a process by PID or name</li>
 * </ul>
 */
public class WindowsProcessTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WindowsProcessTool.class);

    private final PowerShellRunner ps;
    private final MutationGuard mutationGuard;
    private final List<String> denylist;
    private final Duration timeout;
    private final boolean dryRunDefault;

    public WindowsProcessTool(PowerShellRunner ps, MutationGuard mutationGuard,
                              List<String> denylist, Duration timeout, boolean dryRunDefault) {
        this.ps = Objects.requireNonNull(ps, "ps");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.denylist = denylist == null ? List.of()
                : denylist.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.dryRunDefault = dryRunDefault;
    }

    @Override public String getFunctionName() { return "windows_process"; }

    @Override
    public String getDescription() {
        return "List, start, or kill processes on Windows. 'list' is read-only; 'start' and 'kill' "
                + "require approval. 'kill' also refuses processes in the configured denylist "
                + "(critical system processes such as svchost, lsass, winlogon).";
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "windows"; }
    @Override public List<String> getTags() { return List.of("windows", "process", "system"); }

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
                case "list"  -> doList(asString(parameters.get("filter"), null));
                case "start" -> doStart(
                        asString(parameters.get("path"), null),
                        asString(parameters.get("args"), null),
                        apply);
                case "kill"  -> doKill(
                        asString(parameters.get("pid"), null),
                        asString(parameters.get("name"), null),
                        apply);
                default -> "Error: unknown operation '" + op + "'. Expected: list, start, kill.";
            };
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String doList(String filter) {
        String filterEscaped = filter == null ? "" : escape(filter);
        String script = filter == null || filter.isBlank()
                ? "Get-Process | Sort-Object -Property Id | Select-Object Name,Id,CPU,WS | ConvertTo-Csv -NoTypeInformation"
                : "Get-Process | Where-Object { $_.ProcessName -like '*" + filterEscaped + "*' } "
                        + "| Sort-Object -Property Id | Select-Object Name,Id,CPU,WS | ConvertTo-Csv -NoTypeInformation";

        PowerShellRunner.Result r = ps.run(script, timeout);
        if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
        return r.stdout().isBlank() ? "(no matching processes)" : r.stdout();
    }

    private String doStart(String path, String args, boolean apply) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("'path' is required for start");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("path", path);
        if (args != null && !args.isBlank()) details.put("args", args);

        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Start process " + path + (args == null || args.isBlank() ? "" : " " + args))
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("start", path, details)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Started process " + path,
                () -> {
                    String script = (args == null || args.isBlank())
                            ? "Start-Process -FilePath '" + escape(path) + "' -PassThru | Select-Object Id,Name | ConvertTo-Csv -NoTypeInformation"
                            : "Start-Process -FilePath '" + escape(path) + "' -ArgumentList '" + escape(args)
                                    + "' -PassThru | Select-Object Id,Name | ConvertTo-Csv -NoTypeInformation";

                    PowerShellRunner.Result r = ps.run(script, timeout);
                    if (!r.ok()) return "Error: failed to start, PowerShell exit=" + r.exitCode()
                            + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
                    return "Started:\n" + r.stdout();
                });
    }

    private String doKill(String pidArg, String name, boolean apply) {
        boolean hasPid = pidArg != null && !pidArg.isBlank();
        boolean hasName = name != null && !name.isBlank();
        if (hasPid == hasName) {
            throw new IllegalArgumentException("Provide exactly one of 'pid' or 'name' for kill");
        }
        if (hasName && isDenied(name)) {
            return "Error: process '" + name + "' is in the denylist; refusing to kill";
        }

        Map<String, Object> details = new LinkedHashMap<>();
        if (hasPid) details.put("pid", pidArg);
        if (hasName) details.put("name", name);

        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Kill process " + (hasPid ? "pid=" + pidArg : "name=" + name))
                .riskLevel(MutationPlan.RiskLevel.HIGH)
                .op("kill", hasPid ? "pid:" + pidArg : "name:" + name, details)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Killed process " + (hasPid ? "pid=" + pidArg : "name=" + name),
                () -> {
                    String script;
                    if (hasPid) {
                        int pid = parsePidOrThrow(pidArg);
                        script = "Stop-Process -Id " + pid + " -Force; "
                                + "Write-Output 'killed:" + pid + "'";
                    } else {
                        // Re-check denylist post-resolution by name pattern to be safe.
                        script = "$matched = Get-Process -Name '" + escape(name) + "' -ErrorAction SilentlyContinue; "
                                + "if (-not $matched) { Write-Output 'no-match' } "
                                + "else { $matched | Stop-Process -Force; "
                                + "Write-Output ('killed:' + ($matched | Measure-Object).Count) }";
                    }

                    PowerShellRunner.Result r = ps.run(script, timeout);
                    if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                            + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
                    String out = r.stdout().trim();
                    if ("no-match".equals(out)) return "No process matched name '" + name + "'";
                    return "Killed: " + out;
                });
    }

    // ---------- helpers ----------

    private boolean isDenied(String name) {
        String lc = name.toLowerCase(Locale.ROOT);
        return denylist.contains(lc) || denylist.stream().anyMatch(d -> lc.contains(d));
    }

    /** Escape single quotes for PowerShell single-quoted strings (PS doubles them). */
    static String escape(String input) {
        if (input == null) return "";
        return input.replace("'", "''");
    }

    private static int parsePidOrThrow(String pidArg) {
        try { return Integer.parseInt(pidArg.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'pid' must be an integer: " + pidArg); }
    }

    private String dryRun(MutationPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("[DRY-RUN] ").append(plan.summary()).append("\n");
        sb.append("Risk: ").append(plan.riskLevel()).append("\n");
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
        props.put("operation", schemaProp("string", "list | start | kill (default: list)",
                List.of("list", "start", "kill")));
        props.put("filter", schemaProp("string", "Optional name substring for 'list'.", null));
        props.put("path", schemaProp("string", "Executable path (required for start).", null));
        props.put("args", schemaProp("string", "Arguments string for 'start' (optional).", null));
        props.put("pid",  schemaProp("string", "Process ID for 'kill' (provide exactly one of pid or name).", null));
        props.put("name", schemaProp("string", "Process name for 'kill' (provide exactly one of pid or name).", null));
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
                "description", "CSV process listing, start/kill confirmation, dry-run plan, or 'Error: …' / 'Rejected …'.");
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to list, launch, or kill a process / application on Windows.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks about Linux/macOS processes, or wants kernel/driver-level control.";
    }

    @Override
    public String smokeTest() {
        try {
            PowerShellRunner.Result r = ps.run("Write-Output 'pong'", Duration.ofSeconds(5));
            if (!r.ok()) return "PowerShell unhealthy: exit=" + r.exitCode();
            if (!r.stdout().contains("pong")) return "PowerShell did not echo expected output";
            return null;
        } catch (Exception e) {
            return "PowerShell unreachable: " + e.getMessage();
        }
    }

    public record Request(String operation, String filter, String path, String args,
                          String pid, String name, Boolean apply) {}
}
