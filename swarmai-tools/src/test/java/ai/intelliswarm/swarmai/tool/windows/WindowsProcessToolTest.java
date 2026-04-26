package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WindowsProcessTool Unit Tests")
class WindowsProcessToolTest {

    private PowerShellRunner ps;
    private MutationGuard mutationGuard;
    private WindowsProcessTool tool;

    @BeforeEach
    void setUp() {
        ps = mock(PowerShellRunner.class);
        mutationGuard = mock(MutationGuard.class);
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        tool = new WindowsProcessTool(ps, mutationGuard,
                List.of("svchost", "winlogon", "lsass"),
                Duration.ofSeconds(2),
                /* dryRunDefault */ true);
    }

    private static Map<String, Object> params(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    // ---------- list (read-only) ----------

    @Test
    @DisplayName("list runs without approval and returns the PowerShell stdout")
    void listRunsWithoutApproval() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "Name,Id\nnotepad,1234\n", ""));

        String out = tool.execute(Map.of("operation", "list")).toString();

        assertTrue(out.contains("notepad"), out);
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("list with a filter applies a Where-Object clause to the PowerShell script")
    void listWithFilter() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "Name,Id\nchrome,5678\n", ""));

        tool.execute(params("operation", "list", "filter", "chrome"));

        ArgumentCaptor<String> scriptCap = ArgumentCaptor.forClass(String.class);
        verify(ps).run(scriptCap.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(scriptCap.getValue().contains("Where-Object"), scriptCap.getValue());
        assertTrue(scriptCap.getValue().contains("*chrome*"), scriptCap.getValue());
    }

    @Test
    @DisplayName("list reports a non-zero PowerShell exit code as an Error string")
    void listSurfacesPowerShellError() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(1, "", "boom"));

        String out = tool.execute(Map.of("operation", "list")).toString();
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("boom"), out);
    }

    // ---------- start ----------

    @Test
    @DisplayName("start without apply returns a [DRY-RUN] plan and does not invoke PowerShell")
    void startDryRun() {
        String out = tool.execute(params("operation", "start", "path", "C:/Windows/System32/notepad.exe")).toString();
        assertTrue(out.startsWith("[DRY-RUN]"), out);
        verify(ps, never()).run(any(), any());
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("start with apply=true checks the guard, runs Start-Process and returns the started process info")
    void startApplied() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "Id,Name\n1234,notepad\n", ""));

        String out = tool.execute(params(
                "operation", "start",
                "path", "C:/Windows/System32/notepad.exe",
                "apply", true)).toString();

        assertTrue(out.startsWith("Started"), out);
        assertTrue(out.contains("notepad"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("Start-Process"), script.getValue());

        ArgumentCaptor<MutationPlan> plan = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(plan.capture());
        assertEquals(MutationPlan.RiskLevel.MEDIUM, plan.getValue().riskLevel());
        assertEquals("start", plan.getValue().ops().get(0).type());
    }

    @Test
    @DisplayName("start without 'path' returns an Error")
    void startRequiresPath() {
        String out = tool.execute(params("operation", "start", "apply", true)).toString();
        assertTrue(out.startsWith("Error:"), out);
    }

    // ---------- kill ----------

    @Test
    @DisplayName("kill by name in the denylist refuses immediately, never asking for approval")
    void killDenylistRefuses() {
        String out = tool.execute(params(
                "operation", "kill", "name", "svchost", "apply", true)).toString();

        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("denylist"), out);
        verify(mutationGuard, never()).check(any());
        verify(ps, never()).run(any(), any());
    }

    @Test
    @DisplayName("kill by name uses Stop-Process with the escaped process name")
    void killByName() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "killed:1\n", ""));

        String out = tool.execute(params(
                "operation", "kill", "name", "notepad", "apply", true)).toString();

        assertTrue(out.startsWith("Killed"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("Stop-Process"), script.getValue());
        assertTrue(script.getValue().contains("notepad"), script.getValue());
    }

    @Test
    @DisplayName("kill by pid validates the integer and runs Stop-Process -Id")
    void killByPid() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "killed:1234\n", ""));

        String out = tool.execute(params(
                "operation", "kill", "pid", "1234", "apply", true)).toString();

        assertTrue(out.startsWith("Killed"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("Stop-Process -Id 1234"), script.getValue());
    }

    @Test
    @DisplayName("kill with both pid and name (or neither) is rejected as ambiguous")
    void killRequiresExactlyOneOfPidOrName() {
        String both = tool.execute(params("operation", "kill",
                "pid", "1", "name", "x", "apply", true)).toString();
        String none = tool.execute(params("operation", "kill", "apply", true)).toString();

        assertTrue(both.startsWith("Error:"), both);
        assertTrue(none.startsWith("Error:"), none);
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("kill with a non-integer pid is rejected with a clear message")
    void killBadPid() {
        String out = tool.execute(params(
                "operation", "kill", "pid", "abc", "apply", true)).toString();
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("integer"), out);
    }

    @Test
    @DisplayName("a rejected kill plan does not invoke PowerShell")
    void killRejected() {
        when(mutationGuard.check(any())).thenReturn(
                MutationGuard.Decision.reject("alice", "no thanks"));

        String out = tool.execute(params(
                "operation", "kill", "pid", "999", "apply", true)).toString();

        assertTrue(out.startsWith("Rejected by alice"), out);
        verify(ps, never()).run(any(), any());
    }

    // ---------- escape & misc ----------

    @Test
    @DisplayName("escape() doubles single quotes for PowerShell single-quoted strings")
    void escapeDoublesSingleQuotes() {
        assertEquals("a''b", WindowsProcessTool.escape("a'b"));
        assertEquals("", WindowsProcessTool.escape(null));
    }

    @Test
    @DisplayName("smokeTest is healthy when PowerShell echoes back 'pong'")
    void smokeTestHealthy() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "pong\n", ""));
        assertEquals(null, tool.smokeTest());
    }

    @Test
    @DisplayName("smokeTest reports unhealthy when PowerShell exits non-zero")
    void smokeTestUnhealthy() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(1, "", "boom"));
        String result = tool.smokeTest();
        assertTrue(result != null && result.contains("unhealthy"), String.valueOf(result));
    }
}
