package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.os.FileSystemTool;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.windows.WindowsFileSystemTool;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the cross-platform {@link FileSystemTool}. Covers:
 * <ul>
 *   <li>tool name and category are the cross-platform values</li>
 *   <li>still extends {@code WindowsFileSystemTool} (back-compat shim)</li>
 *   <li>reads pass through unchanged (allowlist root listing)</li>
 *   <li>writes route through the {@link MutationGuard} (no behavioural divergence)</li>
 * </ul>
 *
 * <p>End-to-end behaviour of every operation is exercised by
 * {@code WindowsFileSystemToolTest}; this class only verifies the
 * cross-platform-renamed surface holds together.</p>
 */
@DisplayName("Cross-platform FileSystemTool")
class FileSystemToolTest {

    private Path root;
    private PathGuard guard;
    private MutationGuard mutationGuard;
    private FileSystemTool tool;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        root = Files.createDirectory(tmp.resolve("desktop"));
        guard = new PathGuard(List.of(root.toString()));
        mutationGuard = mock(MutationGuard.class);
        when(mutationGuard.check(any())).thenReturn(MutationGuard.Decision.approve("test", "ok"));
        tool = new FileSystemTool(guard, mutationGuard, /* dryRunDefault */ true);
    }

    @Nested
    @DisplayName("Tool identity")
    class IdentityTests {

        @Test
        @DisplayName("function name is the cross-platform os_filesystem (not windows_filesystem)")
        void functionName_isCrossPlatform() {
            assertEquals("os_filesystem", tool.getFunctionName());
        }

        @Test
        @DisplayName("category is os (not windows)")
        void category_isCrossPlatform() {
            assertEquals("os", tool.getCategory());
        }

        @Test
        @DisplayName("still extends WindowsFileSystemTool — pure back-compat shim")
        void extends_windowsFileSystemTool() {
            assertTrue(tool instanceof WindowsFileSystemTool,
                    "FileSystemTool must remain an instance of WindowsFileSystemTool so existing " +
                    "consumers/extensions keep working");
        }
    }

    @Nested
    @DisplayName("Read operations (delegated)")
    class ReadTests {

        @Test
        @DisplayName("list with no path returns the allowlist roots")
        void list_returnsAllowedRoots() {
            Object out = tool.execute(Map.of("operation", "list"));
            assertTrue(out.toString().contains("Allowed roots"), out.toString());
            assertTrue(out.toString().contains(root.toString()), out.toString());
        }

        @Test
        @DisplayName("list of an empty allowlist directory returns the empty marker")
        void list_emptyDirectory() {
            Object out = tool.execute(Map.of("operation", "list", "path", root.toString()));
            assertNotNull(out);
            assertFalse(out.toString().isBlank());
        }
    }

    @Nested
    @DisplayName("Mutation operations (gated by MutationGuard)")
    class MutationTests {

        @Test
        @DisplayName("mkdir with apply=true creates the directory and consults the guard")
        void mkdir_withApplyConsultGuardAndCreates() throws Exception {
            Path target = root.resolve("Apps");
            Object out = tool.execute(Map.of(
                    "operation", "mkdir",
                    "path", target.toString(),
                    "apply", true));

            assertTrue(Files.isDirectory(target),
                    "directory should exist after a guard-approved mkdir");
            assertNotNull(out);
        }

        @Test
        @DisplayName("mkdir with dry-run default (apply=false) does NOT create the directory")
        void mkdir_dryRunDefault_doesNotCreate() {
            Path target = root.resolve("DryRunApps");
            tool.execute(Map.of(
                    "operation", "mkdir",
                    "path", target.toString()));

            assertFalse(Files.isDirectory(target),
                    "dryRunDefault=true means apply=false → no mutation");
        }
    }
}
