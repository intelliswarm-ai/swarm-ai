package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import ai.intelliswarm.swarmai.tool.windows.internal.ScreenCapturer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WindowsScreenshotTool Unit Tests")
class WindowsScreenshotToolTest {

    private ScreenCapturer capturer;
    private PowerShellRunner ps;
    private MutationGuard mutationGuard;
    private Path outputDir;
    private WindowsScreenshotTool tool;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        capturer = mock(ScreenCapturer.class);
        ps = mock(PowerShellRunner.class);
        mutationGuard = mock(MutationGuard.class);
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        outputDir = tmp.resolve("screens");
        tool = new WindowsScreenshotTool(capturer, ps, mutationGuard, outputDir,
                Duration.ofSeconds(2), true);
    }

    private static Map<String, Object> params(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    // ---------- screen ----------

    @Test
    @DisplayName("screen without apply returns a [DRY-RUN] plan and does not capture")
    void screenDryRun() {
        String out = tool.execute(Map.of("operation", "screen")).toString();
        assertTrue(out.startsWith("[DRY-RUN]"), out);
        verify(capturer, never()).capturePrimary(any(), any());
    }

    @Test
    @DisplayName("screen with apply=true checks the guard and writes a PNG via the capturer")
    void screenApplied() {
        when(capturer.capturePrimary(any(), any())).thenReturn(outputDir.resolve("screen-1.png"));

        String out = tool.execute(params("operation", "screen", "apply", true)).toString();

        assertTrue(out.startsWith("Saved:"), out);
        assertTrue(out.contains(".png"), out);
        verify(mutationGuard).check(any());

        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        verify(capturer).capturePrimary(eq(outputDir), nameCap.capture());
        assertTrue(nameCap.getValue().startsWith("screen-"), nameCap.getValue());
    }

    // ---------- monitor ----------

    @Test
    @DisplayName("monitor requires an integer index")
    void monitorRequiresIndex() {
        String out = tool.execute(params("operation", "monitor", "apply", true)).toString();
        assertTrue(out.startsWith("Error:"), out);
    }

    @Test
    @DisplayName("monitor delegates to captureMonitor with the supplied index and a derived name")
    void monitorApplied() {
        when(capturer.captureMonitor(anyInt(), any(), any()))
                .thenReturn(outputDir.resolve("monitor1-1.png"));

        String out = tool.execute(params(
                "operation", "monitor", "index", 1, "apply", true)).toString();

        assertTrue(out.startsWith("Saved:"), out);

        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        verify(capturer).captureMonitor(eq(1), eq(outputDir), nameCap.capture());
        assertTrue(nameCap.getValue().startsWith("monitor1-"), nameCap.getValue());
    }

    // ---------- window ----------

    @Test
    @DisplayName("window without title returns an Error")
    void windowRequiresTitle() {
        String out = tool.execute(params("operation", "window", "apply", true)).toString();
        assertTrue(out.startsWith("Error:"), out);
    }

    @Test
    @DisplayName("window resolves bounds via PowerShell GetWindowRect and captures the rectangle")
    void windowApplied() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "10,20,110,220\n", ""));
        when(capturer.captureRect(anyInt(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenReturn(outputDir.resolve("window-Notepad-1.png"));

        String out = tool.execute(params(
                "operation", "window", "title", "Notepad", "apply", true)).toString();

        assertTrue(out.startsWith("Saved:"), out);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        verify(ps).run(script.capture(), any());
        assertTrue(script.getValue().contains("GetWindowRect"), script.getValue());

        verify(capturer).captureRect(eq(10), eq(20), eq(100), eq(200), eq(outputDir), any());
    }

    @Test
    @DisplayName("window with no matching process reports 'No window matched' and never captures")
    void windowNoMatch() {
        when(ps.run(any(), any())).thenReturn(new PowerShellRunner.Result(0, "no-match\n", ""));

        String out = tool.execute(params(
                "operation", "window", "title", "ZZ", "apply", true)).toString();

        assertTrue(out.startsWith("No window matched"), out);
        verify(capturer, never()).captureRect(anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("a rejected screenshot does not invoke the capturer")
    void rejectedShortCircuits() {
        when(mutationGuard.check(any())).thenReturn(
                MutationGuard.Decision.reject("alice", "no thanks"));

        String out = tool.execute(params("operation", "screen", "apply", true)).toString();

        assertTrue(out.startsWith("Rejected by alice"), out);
        verify(capturer, never()).capturePrimary(any(), any());
    }

    // ---------- helpers ----------

    @Test
    @DisplayName("parseRect splits a 4-integer CSV; bad input throws IllegalArgumentException")
    void parseRect() {
        assertArrayEquals(new int[]{1, 2, 3, 4}, WindowsScreenshotTool.parseRect("1,2,3,4"));
        assertThrows(IllegalArgumentException.class, () -> WindowsScreenshotTool.parseRect("1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> WindowsScreenshotTool.parseRect("a,b,c,d"));
    }

    @Test
    @DisplayName("safeName replaces every non-alphanumeric character with an underscore")
    void safeName() {
        assertEquals("Untitled_-_Notepad", WindowsScreenshotTool.safeName("Untitled - Notepad"));
        assertEquals("a.b-c_d", WindowsScreenshotTool.safeName("a.b-c_d"));
    }

    @Test
    @DisplayName("an unknown operation returns a clear Error")
    void unknownOperation() {
        String out = tool.execute(params("operation", "frobnicate")).toString();
        assertTrue(out.startsWith("Error:"), out);
    }
}
