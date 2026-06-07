package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.safety.GuardedOperation;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Inspect and manipulate top-level OS windows on Windows via PowerShell + User32.
 *
 * <p>Windows are looked up by a substring of their title (case-insensitive).
 * Only the first match is acted on; the {@code list} op is provided to help
 * disambiguate.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code list(filter?)} — enumerate visible windows (process name, pid, title)</li>
 *   <li>{@code focus(title)} — bring a window to the foreground (LOW)</li>
 *   <li>{@code minimize(title)} / {@code maximize(title)} / {@code restore(title)} (LOW)</li>
 *   <li>{@code close(title)} — send {@code WM_CLOSE} (HIGH; can lose unsaved work)</li>
 * </ul>
 */
public class WindowsWindowTool implements BaseTool {

    private static final String USER32_HEADER =
            "Add-Type -Name U32 -Namespace W -MemberDefinition @\"\n" +
            "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] public static extern bool ShowWindow(System.IntPtr h, int n);\n" +
            "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(System.IntPtr h);\n" +
            "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] public static extern bool PostMessage(System.IntPtr h, uint m, System.IntPtr w, System.IntPtr l);\n" +
            "\"@; ";

    private static final int SW_MAXIMIZE = 3;
    private static final int SW_MINIMIZE = 6;
    private static final int SW_RESTORE  = 9;
    private static final String WM_CLOSE_HEX = "0x0010";

    private final PowerShellRunner ps;
    private final MutationGuard mutationGuard;
    private final Duration timeout;
    private final boolean dryRunDefault;

    public WindowsWindowTool(PowerShellRunner ps, MutationGuard mutationGuard,
                             Duration timeout, boolean dryRunDefault) {
        this.ps = Objects.requireNonNull(ps, "ps");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.dryRunDefault = dryRunDefault;
    }

    @Override public String getFunctionName() { return "windows_window"; }

    @Override
    public String getDescription() {
        return "List and manipulate top-level desktop windows on Windows: focus, minimize, maximize, "
                + "restore, or close. Windows are matched by a case-insensitive substring of their title. "
                + "'close' is HIGH risk (may lose unsaved work) and routed through approval.";
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "windows"; }
    @Override public List<String> getTags() { return List.of("windows", "ui", "windows-management"); }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().os("windows").bins("powershell.exe").build();
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (parameters == null) parameters = Map.of();
        String op = asString(parameters.get("operation"), "list").toLowerCase(Locale.ROOT);
        String title = asString(parameters.get("title"), null);
        boolean apply = asBoolean(parameters.get("apply"), !dryRunDefault);

        try {
            return switch (op) {
                case "list"     -> doList(asString(parameters.get("filter"), null));
                case "focus"    -> doStateOp("focus", title, MutationPlan.RiskLevel.LOW, apply,
                        h -> "[void][W.U32]::SetForegroundWindow(" + h + ")");
                case "minimize" -> doStateOp("minimize", title, MutationPlan.RiskLevel.LOW, apply,
                        h -> "[void][W.U32]::ShowWindow(" + h + "," + SW_MINIMIZE + ")");
                case "maximize" -> doStateOp("maximize", title, MutationPlan.RiskLevel.LOW, apply,
                        h -> "[void][W.U32]::ShowWindow(" + h + "," + SW_MAXIMIZE + ")");
                case "restore"  -> doStateOp("restore",  title, MutationPlan.RiskLevel.LOW, apply,
                        h -> "[void][W.U32]::ShowWindow(" + h + "," + SW_RESTORE + ")");
                case "close"    -> doStateOp("close",    title, MutationPlan.RiskLevel.HIGH, apply,
                        h -> "[void][W.U32]::PostMessage(" + h + "," + WM_CLOSE_HEX
                                + ",[System.IntPtr]::Zero,[System.IntPtr]::Zero)");
                default -> "Error: unknown operation '" + op
                        + "'. Expected: list, focus, minimize, maximize, restore, close.";
            };
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---------- list ----------

    private String doList(String filter) {
        String script;
        if (filter == null || filter.isBlank()) {
            script = "Get-Process | Where-Object { $_.MainWindowTitle } "
                    + "| Sort-Object -Property Id "
                    + "| Select-Object Name,Id,MainWindowTitle "
                    + "| ConvertTo-Csv -NoTypeInformation";
        } else {
            script = "Get-Process | Where-Object { $_.MainWindowTitle -like '*" + escape(filter) + "*' } "
                    + "| Sort-Object -Property Id "
                    + "| Select-Object Name,Id,MainWindowTitle "
                    + "| ConvertTo-Csv -NoTypeInformation";
        }
        PowerShellRunner.Result r = ps.run(script, timeout);
        if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
        return r.stdout().isBlank() ? "(no matching windows)" : r.stdout();
    }

    // ---------- state ops ----------

    @FunctionalInterface
    private interface ActionScript { String render(String hwndExpression); }

    private String doStateOp(String opName, String title, MutationPlan.RiskLevel risk,
                             boolean apply, ActionScript action) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("'title' is required for " + opName);
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary(capitalise(opName) + " window matching '" + title + "'")
                .riskLevel(risk)
                .op(opName, "title:" + title)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);

        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                capitalise(opName) + " window matching '" + title + "'",
                () -> {
                    String script = USER32_HEADER
                            + "$p = Get-Process | Where-Object { $_.MainWindowTitle -like '*" + escape(title)
                            + "*' } | Select-Object -First 1; "
                            + "if (-not $p) { Write-Output 'no-match' } "
                            + "else { " + action.render("$p.MainWindowHandle")
                            + "; Write-Output ('ok:' + $p.Name + ':' + $p.Id) }";

                    PowerShellRunner.Result r = ps.run(script, timeout);
                    if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                            + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
                    String out = r.stdout().trim();
                    if ("no-match".equals(out)) return "No window matched title containing '" + title + "'";
                    return capitalise(opName) + ": " + out;
                });
    }

    // ---------- helpers ----------

    static String escape(String input) {
        if (input == null) return "";
        return input.replace("'", "''");
    }

    private String dryRun(MutationPlan plan) {
        return "[DRY-RUN] " + plan.summary() + "\nRisk: " + plan.riskLevel()
                + "\nPass apply=true to execute (will require approval).";
    }

    private static String rejected(MutationPlan plan, MutationGuard.Decision d) {
        return "Rejected by " + d.decidedBy()
                + (d.reason().isBlank() ? "" : ": " + d.reason())
                + "\nPlan was: " + plan.summary();
    }

    private static String capitalise(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
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
        props.put("operation", schemaProp("string",
                "list | focus | minimize | maximize | restore | close (default: list)",
                List.of("list", "focus", "minimize", "maximize", "restore", "close")));
        props.put("filter", schemaProp("string", "Optional title substring for 'list'.", null));
        props.put("title", schemaProp("string",
                "Window title substring (case-insensitive). Required for focus/min/max/restore/close. First match wins.", null));
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "boolean");
        apply.put("description", "If false (default), return a dry-run plan instead of executing.");
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
                "description", "CSV window listing or 'Focused/Closed/...: Name:PID' confirmation, dry-run, error.");
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to focus, minimize, maximize, restore, close, or list open windows on Windows.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks to move/resize windows by pixel coordinates (not supported in v1) "
                + "or to interact with windows on Linux/macOS.";
    }

    public record Request(String operation, String filter, String title, Boolean apply) {}
}
