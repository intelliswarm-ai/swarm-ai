package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import ai.intelliswarm.swarmai.tool.os.shared.BashRunner;
import ai.intelliswarm.swarmai.tool.os.shared.CommandRunner;
import ai.intelliswarm.swarmai.tool.os.shared.OsContext;
import ai.intelliswarm.swarmai.tool.os.shared.OsType;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Inspect, start, and kill processes on the host. Cross-platform — picks the right
 * backend at runtime via {@link OsContext}: PowerShell on Windows (delegated to the
 * existing {@code WindowsProcessTool}'s runner), {@code ps}/{@code kill}/{@code open}
 * on macOS, {@code ps}/{@code kill}/launcher on Linux.
 *
 * <p>{@code list} is read-only and runs without approval. {@code start} and {@code kill}
 * route through {@link MutationGuard}. {@code kill} additionally checks a configurable
 * denylist of system processes (winlogon, kernel_task, systemd, …) and refuses outright,
 * even if a human approves.
 *
 * <p>Operations:
 * <ul>
 *   <li>{@code list(filter?)} — running processes (optional name substring filter)</li>
 *   <li>{@code start(path, args?)} — launch an executable</li>
 *   <li>{@code kill(pid? | name?)} — stop a process by PID or name</li>
 * </ul>
 */
public class ProcessTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ProcessTool.class);

    private final OsContext os;
    private final BashRunner bash;
    private final MutationGuard mutationGuard;
    private final List<String> denylist;
    private final Duration timeout;
    private final boolean dryRunDefault;

    public ProcessTool(OsContext os,
                       BashRunner bash,
                       MutationGuard mutationGuard,
                       List<String> denylist,
                       Duration timeout,
                       boolean dryRunDefault) {
        this.os = Objects.requireNonNull(os);
        this.bash = Objects.requireNonNull(bash);
        this.mutationGuard = Objects.requireNonNull(mutationGuard);
        this.denylist = denylist == null ? List.of() : denylist.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
        this.dryRunDefault = dryRunDefault;
    }

    public record Request(String operation, String filter, String path, String args, Integer pid, String name, Boolean apply) {}

    @Override
    public String getFunctionName() { return "os_process"; }

    @Override
    public String getDescription() {
        return "List, start, or kill processes. Cross-platform: uses ps/kill on macOS and Linux, " +
               "PowerShell on Windows. 'list' is read-only; 'start' and 'kill' route through the " +
               "approval gate. 'kill' refuses denylisted system processes outright.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String op = asLower(parameters.get("operation"));
        if (op == null) return error("missing 'operation' (one of: list, start, kill)");
        boolean apply = parameters.get("apply") instanceof Boolean b ? b : !dryRunDefault;
        try {
            return switch (op) {
                case "list"  -> doList(parameters);
                case "start" -> doStart(parameters, apply);
                case "kill"  -> doKill(parameters, apply);
                default      -> error("unknown operation '" + op + "'");
            };
        } catch (Exception e) {
            logger.warn("ProcessTool {} failed: {}", op, e.getMessage());
            return error(op + " failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", Map.of("type", "string",
                "enum", List.of("list", "start", "kill"),
                "description", "Process operation."));
        props.put("filter", Map.of("type", "string", "description", "Substring filter for 'list'."));
        props.put("path",   Map.of("type", "string", "description", "Executable path for 'start'."));
        props.put("args",   Map.of("type", "string", "description", "Space-separated args for 'start'."));
        props.put("pid",    Map.of("type", "integer", "description", "PID for 'kill'."));
        props.put("name",   Map.of("type", "string", "description", "Process name for 'kill' (alternative to pid)."));
        props.put("apply",  Map.of("type", "boolean", "description", "true to actually execute; false returns the plan."));
        return Map.of("type", "object", "properties", props, "required", List.of("operation"));
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public String getCategory() { return "os-control"; }

    @Override
    public List<String> getTags() { return List.of("os-control", "process", "cross-platform"); }

    @Override
    public ToolRequirements getRequirements() {
        return new ToolRequirements(List.of(), List.of(), List.of(), List.of("windows", "darwin", "linux"));
    }

    @Override
    public String getTriggerWhen() {
        return "User asks what processes are running, or wants to start/stop a specific application by name or PID.";
    }

    @Override
    public String getAvoidWhen() {
        return "Operation targets a system process (kernel, init, winlogon, etc.) — those are denylisted and will refuse.";
    }

    // ---------- list ----------

    private Object doList(Map<String, Object> p) {
        String filter = asString(p.get("filter"));
        return switch (os.type()) {
            case WINDOWS         -> listWindows(filter);
            case MACOS, LINUX    -> listPosix(filter);
            default              -> error("unsupported OS: " + os.type());
        };
    }

    private Object listPosix(String filter) {
        // -e = all processes, -o pid,comm,etime — minimal columns for a stable parse
        CommandRunner.Result r = bash.run(List.of("ps", "-eo", "pid,comm,etime"), timeout);
        if (!r.ok()) return error("ps failed: " + r.stderr());
        List<Map<String, Object>> entries = parsePsOutput(r.stdout(), filter);
        return Map.of("processes", entries, "count", entries.size(), "os", os.type().name());
    }

    private List<Map<String, Object>> parsePsOutput(String out, String filter) {
        List<Map<String, Object>> entries = new ArrayList<>();
        String[] lines = out.split("\\R");
        boolean header = true;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            if (header) { header = false; continue; }
            String[] parts = line.trim().split("\\s+", 3);
            if (parts.length < 2) continue;
            String pid = parts[0];
            String name = parts[1];
            if (filter != null && !name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            try { e.put("pid", Integer.parseInt(pid)); } catch (NumberFormatException nfe) { e.put("pid", pid); }
            e.put("name", name);
            if (parts.length >= 3) e.put("etime", parts[2]);
            entries.add(e);
        }
        return entries;
    }

    private Object listWindows(String filter) {
        // We deliberately avoid hard-coupling to PowerShellRunner here so this tool
        // compiles on macOS/Linux JDKs without the Windows internal classes on the
        // path. Use Java ProcessHandle as a portable fallback.
        return listViaProcessHandle(filter);
    }

    private Object listViaProcessHandle(String filter) {
        List<Map<String, Object>> entries = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            String name = ph.info().command().orElse("");
            String shortName = name.isEmpty() ? "" : name.substring(name.lastIndexOf(java.io.File.separatorChar) + 1);
            if (filter != null && !shortName.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) return;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("pid", ph.pid());
            e.put("name", shortName.isEmpty() ? "(unknown)" : shortName);
            ph.info().startInstant().ifPresent(t -> e.put("startedAt", t.toString()));
            entries.add(e);
        });
        return Map.of("processes", entries, "count", entries.size(), "os", os.type().name());
    }

    // ---------- start ----------

    private Object doStart(Map<String, Object> p, boolean apply) {
        String path = asString(p.get("path"));
        if (path == null) return error("missing 'path'");
        String args = asString(p.get("args"));
        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Start " + path + (args == null ? "" : " " + args))
                .riskLevel(MutationPlan.RiskLevel.MEDIUM)
                .op("start", path, Map.of("args", args == null ? "" : args))
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        MutationGuard.Decision decision = requireApproved(plan);
        if (decision == null) return rejected(plan);

        List<String> argv = new ArrayList<>();
        if (os.isMacOs() && !path.startsWith("/")) {
            // 'open -a' for app names; 'open' for paths/URLs
            argv.add("open");
            argv.add("-a");
            argv.add(path);
        } else if (os.isLinux()) {
            argv.add("xdg-open");
            argv.add(path);
        } else {
            argv.add(path);
        }
        if (args != null) for (String a : args.trim().split("\\s+")) argv.add(a);
        try {
            new ProcessBuilder(argv).start();
            mutationGuard.observe(decision.correlationId(), true, "started: " + path);
            return Map.of("ok", true, "op", "start", "argv", argv);
        } catch (Exception e) {
            mutationGuard.observe(decision.correlationId(), false,
                    "start failed: " + e.getMessage());
            return error("start failed: " + e.getMessage());
        }
    }

    // ---------- kill ----------

    private Object doKill(Map<String, Object> p, boolean apply) {
        Integer pid = (p.get("pid") instanceof Number n) ? n.intValue() : tryParseInt(p.get("pid"));
        String name = asString(p.get("name"));
        if (pid == null && name == null) return error("kill requires 'pid' or 'name'");

        // Resolve the target name for denylist check (final because it's captured in lambdas below)
        final String resolvedName = (name != null) ? name :
                ProcessHandle.of(pid)
                        .flatMap(h -> h.info().command())
                        .map(s -> s.substring(s.lastIndexOf(java.io.File.separatorChar) + 1))
                        .orElse("(pid " + pid + ")");
        if (isDenylisted(resolvedName)) {
            return error("denied: '" + resolvedName + "' is on the system-process denylist and cannot be killed");
        }

        MutationPlan plan = MutationPlan.builder()
                .toolName(getFunctionName())
                .summary("Kill " + resolvedName + (pid != null ? " (pid " + pid + ")" : ""))
                .riskLevel(MutationPlan.RiskLevel.HIGH)
                .op("kill", resolvedName, pid != null ? Map.of("pid", pid) : Map.of())
                .metadata("apply", apply)
                .build();
        if (!apply) return planResponse(plan);
        MutationGuard.Decision decision = requireApproved(plan);
        if (decision == null) return rejected(plan);

        // Use ProcessHandle for portability — works the same on Windows and POSIX.
        if (pid != null) {
            return ProcessHandle.of(pid).<Object>map(h -> {
                boolean killed = h.destroy();
                mutationGuard.observe(decision.correlationId(), killed,
                        killed ? "killed pid " + pid
                               : "destroy returned false for pid " + pid);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", killed);
                m.put("op", "kill");
                m.put("pid", pid);
                m.put("name", resolvedName);
                return m;
            }).orElseGet(() -> {
                mutationGuard.observe(decision.correlationId(), false,
                        "no process with pid " + pid);
                return error("no process with pid " + pid);
            });
        }
        // Kill by name: target command substrings, but enforce denylist per matched process too.
        final String needle = resolvedName.toLowerCase(Locale.ROOT);
        long killed = 0;
        long skippedDenylisted = 0;
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            String command = handle.info().command().orElse(null);
            if (command == null) continue;
            String commandLower = command.toLowerCase(Locale.ROOT);
            if (!commandLower.contains(needle)) continue;
            if (isDenylisted(commandLower)) {
                skippedDenylisted++;
                continue;
            }
            if (handle.destroy()) killed++;
        }

        mutationGuard.observe(decision.correlationId(), killed > 0,
                "killed " + killed + " processes matching '" + resolvedName + "'");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", killed > 0);
        out.put("op", "kill");
        out.put("matched", killed);
        out.put("name", resolvedName);
        if (skippedDenylisted > 0) {
            out.put("skippedDenylisted", skippedDenylisted);
        }
        return out;
    }

    private boolean isDenylisted(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String d : denylist) if (lower.contains(d)) return true;
        return false;
    }

    // ---------- safety helpers ----------

    /**
     * Run the plan past the guard. Returns the {@link MutationGuard.Decision}
     * when approved (callers use its {@code correlationId} to {@code observe}
     * the outcome later), or {@code null} when rejected.
     */
    private MutationGuard.Decision requireApproved(MutationPlan plan) {
        MutationGuard.Decision d = mutationGuard.check(plan);
        if (!d.approved()) {
            logger.info("ProcessTool plan rejected by {}: {}", d.decidedBy(), d.reason());
            return null;
        }
        return d;
    }

    private static Object planResponse(MutationPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planned", true);
        m.put("summary", plan.summary());
        m.put("risk", plan.riskLevel().name());
        m.put("hint", "re-invoke with apply=true to execute");
        return m;
    }

    private Object rejected(MutationPlan plan) {
        return error("mutation rejected: " + plan.summary());
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

    private static Integer tryParseInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Map<String, Object> error(String msg) {
        return Map.of("error", msg);
    }
}
