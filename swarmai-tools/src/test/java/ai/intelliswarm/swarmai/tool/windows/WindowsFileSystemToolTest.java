package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationPlan;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("WindowsFileSystemTool Unit Tests")
class WindowsFileSystemToolTest {

    private Path root;
    private PathGuard guard;
    private MutationGuard mutationGuard;
    private WindowsFileSystemTool tool;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = Files.createDirectory(tmp.resolve("desktop"));
        guard = new PathGuard(List.of(root.toString()));
        mutationGuard = mock(MutationGuard.class);
        // Default: every plan approved
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        tool = new WindowsFileSystemTool(guard, mutationGuard, /* dryRunDefault */ true);
    }

    private static Map<String, Object> params(Object... kvs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    // ---------- list ----------

    @Test
    @DisplayName("list with no path returns the configured allowlist roots")
    void listWithoutPathReturnsAllowedRoots() {
        Object out = tool.execute(Map.of("operation", "list"));
        assertTrue(out.toString().contains("Allowed roots"), out.toString());
        assertTrue(out.toString().contains(root.toString()), out.toString());
    }

    @Test
    @DisplayName("list of an allowed directory shows its entries with file/dir markers")
    void listOfAllowedDirectory() throws Exception {
        Files.createFile(root.resolve("a.txt"));
        Files.createDirectory(root.resolve("sub"));

        String out = tool.execute(params("operation", "list", "path", root.toString())).toString();

        assertTrue(out.contains("Listing of"), out);
        assertTrue(out.contains("a.txt"), out);
        assertTrue(out.contains("sub"), out);
        assertTrue(out.contains("DIR"), out);
        assertTrue(out.contains("FILE"), out);
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("list of a path outside the allowlist returns an Error and never calls the mutation guard")
    void listOutsideAllowlistRejected(@TempDir Path other) {
        String out = tool.execute(params("operation", "list", "path", other.toString())).toString();
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("outside allowlist"), out);
        verify(mutationGuard, never()).check(any());
    }

    // ---------- dry-run vs apply ----------

    @Test
    @DisplayName("a mutation without apply=true returns a [DRY-RUN] plan and does not invoke the guard")
    void dryRunByDefault() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = root.resolve("b.txt");

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString())).toString();

        assertTrue(out.startsWith("[DRY-RUN]"), out);
        assertTrue(Files.exists(src));
        assertFalse(Files.exists(dst));
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("apply=true asks the mutation guard for approval and only mutates when approved")
    void applyAsksGuardAndMutatesWhenApproved() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = root.resolve("b.txt");

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Moved "), out);
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(dst));

        ArgumentCaptor<MutationPlan> planCap = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(planCap.capture());
        MutationPlan plan = planCap.getValue();
        assertEquals("windows_filesystem", plan.toolName());
        assertEquals(MutationPlan.RiskLevel.MEDIUM, plan.riskLevel());
        assertEquals("move", plan.ops().get(0).type());
    }

    @Test
    @DisplayName("a rejected plan does not mutate and surfaces the rejector and reason")
    void rejectionShortCircuitsTheMutation() throws Exception {
        when(mutationGuard.check(any())).thenReturn(
                MutationGuard.Decision.reject("alice", "looks risky"));

        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = root.resolve("b.txt");

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Rejected by alice"), out);
        assertTrue(out.contains("looks risky"), out);
        assertTrue(Files.exists(src));
        assertFalse(Files.exists(dst));
    }

    // ---------- mkdir ----------

    @Test
    @DisplayName("mkdir creates a directory under the allowlist when applied")
    void mkdirCreatesDirectory() {
        Path dir = root.resolve("new-dir");
        String out = tool.execute(params(
                "operation", "mkdir",
                "path", dir.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Created directory:"), out);
        assertTrue(Files.isDirectory(dir));
    }

    @Test
    @DisplayName("mkdir on an existing directory is a no-op (no approval consumed)")
    void mkdirOnExistingDirectoryIsNoOp() throws Exception {
        Files.createDirectory(root.resolve("exists"));

        String out = tool.execute(params(
                "operation", "mkdir",
                "path", root.resolve("exists").toString(),
                "apply", true)).toString();

        assertTrue(out.contains("Already exists"), out);
        verify(mutationGuard, never()).check(any());
    }

    // ---------- move / copy ----------

    @Test
    @DisplayName("move refuses to overwrite an existing destination")
    void moveRefusesOverwrite() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = Files.createFile(root.resolve("b.txt"));

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("already exists"), out);
        verify(mutationGuard, never()).check(any());
    }

    @Test
    @DisplayName("copy duplicates a file and leaves the source intact")
    void copyDuplicatesFile() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Files.writeString(src, "hello");
        Path dst = root.resolve("a-copy.txt");

        String out = tool.execute(params(
                "operation", "copy",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Copied "), out);
        assertTrue(Files.exists(src));
        assertEquals("hello", Files.readString(dst));
    }

    @Test
    @DisplayName("move into a sub-directory creates intermediate parents automatically")
    void moveCreatesParents() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = root.resolve("nested/dir/a.txt");

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Moved "), out);
        assertTrue(Files.exists(dst));
    }

    @Test
    @DisplayName("move where the destination is outside the allowlist is rejected before approval")
    void moveOutsideAllowlistRejected(@TempDir Path other) throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = other.resolve("a.txt");

        String out = tool.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("outside allowlist"), out);
        verify(mutationGuard, never()).check(any());
    }

    // ---------- rename ----------

    @Test
    @DisplayName("rename changes a file's name within its own directory")
    void renameSimple() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        String out = tool.execute(params(
                "operation", "rename",
                "path", src.toString(),
                "name", "b.txt",
                "apply", true)).toString();

        assertTrue(out.startsWith("Renamed "), out);
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(root.resolve("b.txt")));
    }

    @Test
    @DisplayName("rename rejects a name with path separators or '..'")
    void renameRejectsPathInName() throws Exception {
        Path src = Files.createFile(root.resolve("a.txt"));
        for (String bad : List.of("../b.txt", "sub/b.txt", "x\\b.txt")) {
            String out = tool.execute(params(
                    "operation", "rename",
                    "path", src.toString(),
                    "name", bad,
                    "apply", true)).toString();
            assertTrue(out.startsWith("Error:"), "for input " + bad + ": " + out);
        }
        assertTrue(Files.exists(src));
        verify(mutationGuard, never()).check(any());
    }

    // ---------- delete ----------

    @Test
    @DisplayName("delete removes a single file when approved and is HIGH risk")
    void deleteRemovesFile() throws Exception {
        Path src = Files.createFile(root.resolve("trash.txt"));

        String out = tool.execute(params(
                "operation", "delete",
                "path", src.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Deleted "), out);
        assertFalse(Files.exists(src));

        ArgumentCaptor<MutationPlan> cap = ArgumentCaptor.forClass(MutationPlan.class);
        verify(mutationGuard).check(cap.capture());
        assertEquals(MutationPlan.RiskLevel.HIGH, cap.getValue().riskLevel());
    }

    @Test
    @DisplayName("delete refuses directories in v1")
    void deleteDirectoryRefused() throws Exception {
        Path dir = Files.createDirectory(root.resolve("a-dir"));

        String out = tool.execute(params(
                "operation", "delete",
                "path", dir.toString(),
                "apply", true)).toString();

        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("not supported"), out);
        verify(mutationGuard, never()).check(any());
    }

    // ---------- misc ----------

    @Test
    @DisplayName("an unknown operation returns a clear Error string")
    void unknownOperation() {
        String out = tool.execute(params("operation", "frobnicate")).toString();
        assertTrue(out.startsWith("Error:"), out);
        assertTrue(out.contains("frobnicate"), out);
    }

    @Test
    @DisplayName("smokeTest returns null when at least one allowed root is configured")
    void smokeTestHealthyWhenRootsConfigured() {
        assertEquals(null, tool.smokeTest());
    }

    @Test
    @DisplayName("smokeTest reports unhealthy when no allowed roots are configured")
    void smokeTestUnhealthyWhenEmpty() {
        WindowsFileSystemTool empty = new WindowsFileSystemTool(
                new PathGuard(List.of()), mutationGuard, true);
        String result = empty.smokeTest();
        assertTrue(result != null && result.contains("no allowed roots"), String.valueOf(result));
    }

    @Test
    @DisplayName("when dryRunDefault=false, the tool executes mutations without apply being explicitly set")
    void dryRunDefaultFalseExecutes() throws Exception {
        WindowsFileSystemTool eager = new WindowsFileSystemTool(guard, mutationGuard, false);
        Path src = Files.createFile(root.resolve("a.txt"));
        Path dst = root.resolve("b.txt");

        String out = eager.execute(params(
                "operation", "move",
                "path", src.toString(),
                "to", dst.toString())).toString();

        assertTrue(out.startsWith("Moved "), out);
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(dst));
    }
}
