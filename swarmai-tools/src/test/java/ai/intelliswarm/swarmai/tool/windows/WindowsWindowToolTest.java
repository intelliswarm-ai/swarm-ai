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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WindowsWindowTool Unit Tests")
class WindowsWindowToolTest {

    private PowerShellRunner ps;
    private MutationGuard mutationGuard;
    private WindowsWindowTool tool;

    @BeforeEach
    void setUp() {
        ps = mock(PowerShellRunner.class);
        mutationGuard = mock(MutationGuard.class);
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        tool = new WindowsWindowTool(ps, mutationGuard, Duration.ofSeconds(2), true);
    }

    private static Map<String, Object> params(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    // ---------- list ----------

    @Test
    @DisplayName("list with no filter enumerates windows via Get-Process and never asks for approval")
    void listEnumerates() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0,
                "\"Name\",\"Id\",\"MainWindowTitle\"\n\"notepad\",\"123\",\"Untitled - Notepad\"\n", ""));

        String out = tool.execute(Map.of("operation", "list")).toString();

        assertTrue(out.contains("notepad"), out);
        verify(mutationGuard, never()).check(any());

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("Get-Process"), script.getValue());
        assertTrue(script.getValue().contains("MainWindowTitle"), script.getValue());
    }

    @Test
    @DisplayName("list with a filter applies a -like substring to MainWindowTitle")
    void listWithFilter() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "Name,Id,MainWindowTitle\n", ""));

        tool.execute(params("operation", "list", "filter", "Notepad"));

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("*Notepad*"), script.getValue());
    }

    // ---------- focus / minimize / maximize / restore ----------

    @Test
    @DisplayName("focus without apply returns a [DRY-RUN] plan and never invokes PowerShell")
    void focusDryRun() {
        String out = tool.execute(params("operation", "focus", "title", "Notepad")).toString();
        assertTrue(out.startsWith("[DRY-RUN]"), out);
        verify(ps, never()).run(any(), any());
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("focus with apply=true loads User32, calls SetForegroundWindow and reports the matched process")
    void focusApplied() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "ok:notepad:123\n", ""));

        String out = tool.execute(params("operation", "focus", "title", "Notepad", "apply", true)).toString();

        assertTrue(out.startsWith("Focus:"), out);
        assertTrue(out.contains("notepad"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("Add-Type"), script.getValue());
        assertTrue(script.getValue().contains("SetForegroundWindow"), script.getValue());

        ArgumentCaptor<MutationPlan> plan = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(plan.capture());
        assertEquals(MutationPlan.RiskLevel.LOW, plan.getValue().riskLevel());
    }

    @Test
    @DisplayName("minimize uses ShowWindow with SW_MINIMIZE (6)")
    void minimizeUsesShowWindow6() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "ok:notepad:1\n", ""));
        tool.execute(params("operation", "minimize", "title", "Notepad", "apply", true));
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("ShowWindow"), script.getValue());
        assertTrue(script.getValue().contains(",6)"), script.getValue());
    }

    @Test
    @DisplayName("maximize uses ShowWindow with SW_MAXIMIZE (3)")
    void maximizeUsesShowWindow3() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "ok:notepad:1\n", ""));
        tool.execute(params("operation", "maximize", "title", "Notepad", "apply", true));
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains(",3)"), script.getValue());
    }

    @Test
    @DisplayName("restore uses ShowWindow with SW_RESTORE (9)")
    void restoreUsesShowWindow9() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "ok:notepad:1\n", ""));
        tool.execute(params("operation", "restore", "title", "Notepad", "apply", true));
        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains(",9)"), script.getValue());
    }

    // ---------- close ----------

    @Test
    @DisplayName("close is HIGH risk and uses PostMessage with WM_CLOSE (0x0010)")
    void closeUsesWmClose() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "ok:notepad:1\n", ""));

        String out = tool.execute(params("operation", "close", "title", "Notepad", "apply", true)).toString();
        assertTrue(out.startsWith("Close:"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), eq(Duration.ofSeconds(2)));
        assertTrue(script.getValue().contains("PostMessage"), script.getValue());
        assertTrue(script.getValue().contains("0x0010"), script.getValue());

        ArgumentCaptor<MutationPlan> plan = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(plan.capture());
        assertEquals(MutationPlan.RiskLevel.HIGH, plan.getValue().riskLevel());
    }

    @Test
    @DisplayName("a state op without 'title' returns an Error and does not call PowerShell or the guard")
    void stateOpWithoutTitleErrors() {
        String out = tool.execute(params("operation", "focus", "apply", true)).toString();
        assertTrue(out.startsWith("Error:"), out);
        verify(ps, never()).run(any(), any());
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("a 'no-match' result from PowerShell is reported as a plain message")
    void noMatchReported() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "no-match\n", ""));
        String out = tool.execute(params("operation", "focus", "title", "ZZ", "apply", true)).toString();
        assertTrue(out.startsWith("No window matched"), out);
    }

    @Test
    @DisplayName("a rejected state op short-circuits before any PowerShell call")
    void rejectedShortCircuits() {
        when(mutationGuard.check(any())).thenReturn(
                MutationGuard.Decision.reject("alice", "no thanks"));

        String out = tool.execute(params("operation", "close", "title", "Notepad", "apply", true)).toString();

        assertTrue(out.startsWith("Rejected by alice"), out);
        verify(ps, never()).run(any(), any());
    }

    @Test
    @DisplayName("escape() doubles single quotes for PowerShell")
    void escapeDoublesSingleQuotes() {
        assertEquals("a''b", WindowsWindowTool.escape("a'b"));
    }

    @Test
    @DisplayName("an unknown operation returns a clear Error")
    void unknownOperation() {
        String out = tool.execute(params("operation", "frobnicate")).toString();
        assertTrue(out.startsWith("Error:"), out);
    }
}
