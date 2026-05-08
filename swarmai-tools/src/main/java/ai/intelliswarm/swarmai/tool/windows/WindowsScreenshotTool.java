package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.safety.GuardedOperation;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import ai.intelliswarm.swarmai.tool.windows.internal.ScreenCapturer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Capture the screen, a specific monitor, or the bounds of a named window
 * to a PNG file. The output goes to a single configured directory; the
 * agent never picks where the file lands.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code screen} — primary monitor</li>
 *   <li>{@code monitor(index)} — specific 0-based monitor</li>
 *   <li>{@code window(title)} — bounds of the first window matching the title substring</li>
 * </ul>
 *
 * <p>All ops are LOW risk but still routed through {@link MutationGuard} —
 * an unsupervised screenshot of a logged-in user's screen is a privacy
 * concern, not just a write to disk.
 */
public class WindowsScreenshotTool implements BaseTool {

    private final ScreenCapturer capturer;
    private final PowerShellRunner ps;
    private final MutationGuard mutationGuard;
    private final Path outputDir;
    private final Duration timeout;
    private final boolean dryRunDefault;

    public WindowsScreenshotTool(ScreenCapturer capturer, PowerShellRunner ps,
                                 MutationGuard mutationGuard, Path outputDir,
                                 Duration timeout, boolean dryRunDefault) {
        this.capturer = Objects.requireNonNull(capturer, "capturer");
        this.ps = Objects.requireNonNull(ps, "ps");
        this.mutationGuard = Objects.requireNonNull(mutationGuard, "mutationGuard");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.dryRunDefault = dryRunDefault;
    }

    @Override public String getFunctionName() { return "windows_screenshot"; }

    @Override
    public String getDescription() {
        return "Capture the primary monitor, a specific monitor, or the bounds of a named window "
                + "to a PNG file in a configured output directory.";
    }

    @Override public boolean isAsync() { return false; }
    @Override public String getCategory() { return "windows"; }
    @Override public List<String> getTags() { return List.of("windows", "screenshot", "image", "capture"); }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().os("windows").bins("powershell.exe").build();
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        if (parameters == null) parameters = Map.of();
        String op = asString(parameters.get("operation"), "screen").toLowerCase(Locale.ROOT);
        boolean apply = asBoolean(parameters.get("apply"), !dryRunDefault);

        try {
            return switch (op) {
                case "screen"  -> doScreen(apply);
                case "monitor" -> doMonitor(parseInt(parameters.get("index"), "index"), apply);
                case "window"  -> doWindow(asString(parameters.get("title"), null), apply);
                default -> "Error: unknown operation '" + op
                        + "'. Expected: screen, monitor, window.";
            };
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (RuntimeException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ---------- ops ----------

    private String doScreen(boolean apply) {
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Capture primary screen to " + outputDir)
                .riskLevel(MutationPlan.RiskLevel.LOW)
                .op("screenshot", "primary")
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);
        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Captured primary screen",
                () -> {
                    Path file = capturer.capturePrimary(outputDir, "screen-" + timestamp());
                    return "Saved: " + file;
                });
    }

    private String doMonitor(int index, boolean apply) {
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Capture monitor " + index + " to " + outputDir)
                .riskLevel(MutationPlan.RiskLevel.LOW)
                .op("screenshot", "monitor:" + index)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);
        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Captured monitor " + index,
                () -> {
                    Path file = capturer.captureMonitor(index, outputDir,
                            "monitor" + index + "-" + timestamp());
                    return "Saved: " + file;
                });
    }

    private String doWindow(String title, boolean apply) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("'title' is required for window screenshots");
        }
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Capture window matching '" + title + "' to " + outputDir)
                .riskLevel(MutationPlan.RiskLevel.LOW)
                .op("screenshot", "title:" + title)
                .metadata("apply", apply)
                .build();
        if (!apply) return dryRun(plan);
        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) return rejected(plan, d);

        return GuardedOperation.runObservedUnchecked(mutationGuard, d,
                "Captured window matching '" + title + "'",
                () -> doWindowCapture(title));
    }

    private String doWindowCapture(String title) {
        // Resolve window bounds via PowerShell + GetWindowRect.
        String script = ""
                + "Add-Type -Name R -Namespace W -MemberDefinition @\"\n"
                + "[System.Runtime.InteropServices.StructLayout(System.Runtime.InteropServices.LayoutKind.Sequential)] "
                + "public struct RECT { public int Left, Top, Right, Bottom; }\n"
                + "[System.Runtime.InteropServices.DllImport(\"user32.dll\")] "
                + "public static extern bool GetWindowRect(System.IntPtr h, out RECT r);\n"
                + "\"@; "
                + "$p = Get-Process | Where-Object { $_.MainWindowTitle -like '*" + escape(title)
                + "*' } | Select-Object -First 1; "
                + "if (-not $p) { Write-Output 'no-match' } "
                + "else { $r = New-Object W.R+RECT; "
                + "[void][W.R]::GetWindowRect($p.MainWindowHandle, [ref]$r); "
                + "Write-Output (\"$($r.Left),$($r.Top),$($r.Right),$($r.Bottom)\") }";

        PowerShellRunner.Result r = ps.run(script, timeout);
        if (!r.ok()) return "Error: PowerShell exit=" + r.exitCode()
                + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim());
        String out = r.stdout().trim();
        if ("no-match".equals(out)) return "No window matched title containing '" + title + "'";

        int[] rect = parseRect(out);
        Path file = capturer.captureRect(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1],
                outputDir, "window-" + safeName(title) + "-" + timestamp());
        return "Saved: " + file;
    }

    // ---------- helpers ----------

    static int[] parseRect(String csv) {
        String[] parts = csv.split(",");
        if (parts.length != 4) throw new IllegalArgumentException("expected 4 numbers, got: " + csv);
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad rect: " + csv);
        }
    }

    static String escape(String s) { return s == null ? "" : s.replace("'", "''"); }

    static String safeName(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String timestamp() {
        return String.valueOf(Instant.now().toEpochMilli());
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

    private static String asString(Object v, String def) { return v == null ? def : v.toString(); }

    private static int parseInt(Object v, String name) {
        if (v == null) throw new IllegalArgumentException("'" + name + "' is required");
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("'" + name + "' must be an integer"); }
    }

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
        props.put("operation", schemaProp("string", "screen | monitor | window (default: screen)",
                List.of("screen", "monitor", "window")));
        props.put("index", schemaProp("integer", "Monitor index (0-based) for 'monitor'.", null));
        props.put("title", schemaProp("string", "Window title substring for 'window'.", null));
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
                "description", "'Saved: <path-to-png>' on success, dry-run plan, or 'Error: …' / 'Rejected …'.");
    }

    @Override
    public String getTriggerWhen() {
        return "User asks to take a screenshot of their Windows desktop, a monitor, or a specific window.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks to record video or stream the screen continuously (this is a single-frame tool).";
    }

    public record Request(String operation, Integer index, String title, Boolean apply) {}

    /** Resolve a configured outputDir string (with placeholders) into a {@link Path}. */
    public static Path resolveOutputDir(String raw) {
        return Paths.get(PathGuard.expand(raw));
    }
}
