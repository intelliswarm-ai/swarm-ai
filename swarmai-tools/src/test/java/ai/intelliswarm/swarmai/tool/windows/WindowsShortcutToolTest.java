package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WindowsShortcutTool Unit Tests")
class WindowsShortcutToolTest {

    private Path desktop;
    private PathGuard guard;
    private PowerShellRunner ps;
    private MutationGuard mutationGuard;
    private WindowsShortcutTool tool;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        desktop = Files.createDirectory(tmp.resolve("Desktop"));
        guard = new PathGuard(List.of(desktop.toString()));
        ps = mock(PowerShellRunner.class);
        mutationGuard = mock(MutationGuard.class);
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        tool = new WindowsShortcutTool(guard, ps, mutationGuard, Duration.ofSeconds(2), true);
    }

    private static Map<String, Object> params(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    // ---------- list ----------

    @Test
    @DisplayName("list with no folder defaults to the Desktop allowlist root")
    void listDefaultsToDesktop() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "Name,LastWriteTime,Length\n", ""));

        tool.execute(Map.of("operation", "list"));

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), any());
        assertTrue(script.getValue().contains(desktop.toString()), script.getValue());
        assertTrue(script.getValue().contains("*.lnk"), script.getValue());
    }

    @Test
    @DisplayName("list with a folder outside the allowlist returns an Error")
    void listOutsideAllowlistRejected(@TempDir Path other) {
        String out = tool.execute(params("operation", "list", "folder", other.toString())).toString();
        assertTrue(out.startsWith("Error:"), out);
        verify(ps, never()).run(any(), any());
    }

    // ---------- inspect ----------

    @Test
    @DisplayName("inspect builds a WScript.Shell.CreateShortcut script and returns its JSON output")
    void inspectReturnsJson() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0,
                "{\"TargetPath\":\"C:\\\\Windows\\\\notepad.exe\",\"Arguments\":\"\"}\n", ""));

        String out = tool.execute(params(
                "operation", "inspect",
                "path", desktop.resolve("Notepad.lnk").toString())).toString();

        assertTrue(out.contains("notepad.exe"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), any());
        assertTrue(script.getValue().contains("WScript.Shell"), script.getValue());
        assertTrue(script.getValue().contains("CreateShortcut"), script.getValue());
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("inspect with a path outside the allowlist returns an Error")
    void inspectOutsideAllowlist(@TempDir Path other) {
        String out = tool.execute(params(
                "operation", "inspect",
                "path", other.resolve("x.lnk").toString())).toString();
        assertTrue(out.startsWith("Error:"), out);
        verify(ps, never()).run(any(), any());
    }

    // ---------- create ----------

    @Test
    @DisplayName("create requires path, target, and a .lnk extension")
    void createInputValidation() {
        String missingPath = tool.execute(params(
                "operation", "create", "target", "C:/x.exe", "apply", true)).toString();
        String missingTarget = tool.execute(params(
                "operation", "create", "path", desktop.resolve("x.lnk").toString(),
                "apply", true)).toString();
        String wrongExt = tool.execute(params(
                "operation", "create", "path", desktop.resolve("x.txt").toString(),
                "target", "C:/x.exe", "apply", true)).toString();

        assertTrue(missingPath.startsWith("Error:"), missingPath);
        assertTrue(missingTarget.startsWith("Error:"), missingTarget);
        assertTrue(wrongExt.startsWith("Error:"), wrongExt);
        verify(mutationGuard, never()).check(any());
        verify(ps, never()).run(any(), any());
    }

    @Test
    @DisplayName("create without apply returns a [DRY-RUN] plan and never invokes PowerShell")
    void createDryRun() {
        String out = tool.execute(params(
                "operation", "create",
                "path", desktop.resolve("Notepad.lnk").toString(),
                "target", "C:/Windows/System32/notepad.exe")).toString();

        assertTrue(out.startsWith("[DRY-RUN]"), out);
        verify(ps, never()).run(any(), any());
    }

    @Test
    @DisplayName("create with apply=true checks the guard, runs PowerShell with the .Save() call and returns success")
    void createApplied() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "created\n", ""));

        String out = tool.execute(params(
                "operation", "create",
                "path", desktop.resolve("Notepad.lnk").toString(),
                "target", "C:/Windows/System32/notepad.exe",
                "args", "C:/tmp/foo.txt",
                "workingDir", "C:/tmp",
                "icon", "C:/Windows/System32/notepad.exe,0",
                "apply", true)).toString();

        assertTrue(out.startsWith("Created shortcut:"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), any());
        assertTrue(script.getValue().contains(".Save()"), script.getValue());
        assertTrue(script.getValue().contains("notepad.exe"), script.getValue());
        assertTrue(script.getValue().contains("foo.txt"), script.getValue());
        assertTrue(script.getValue().contains("WorkingDirectory"), script.getValue());
        assertTrue(script.getValue().contains("IconLocation"), script.getValue());

        ArgumentCaptor<MutationPlan> plan = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(plan.capture());
        assertEquals(MutationPlan.RiskLevel.MEDIUM, plan.getValue().riskLevel());
    }

    @Test
    @DisplayName("a rejected create plan does not invoke PowerShell")
    void createRejected() {
        when(mutationGuard.check(any())).thenReturn(
                MutationGuard.Decision.reject("alice", "no thanks"));

        String out = tool.execute(params(
                "operation", "create",
                "path", desktop.resolve("Notepad.lnk").toString(),
                "target", "C:/Windows/System32/notepad.exe",
                "apply", true)).toString();

        assertTrue(out.startsWith("Rejected by alice"), out);
        verify(ps, never()).run(any(), any());
    }

    @Test
    @DisplayName("escape() doubles single quotes for PowerShell")
    void escapeDoublesSingleQuotes() {
        assertEquals("a''b", WindowsShortcutTool.escape("a'b"));
    }

    @Test
    @DisplayName("an unknown operation returns a clear Error")
    void unknownOperation() {
        String out = tool.execute(params("operation", "frobnicate")).toString();
        assertTrue(out.startsWith("Error:"), out);
    }
}
