package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.windows.WindowsFileSystemTool;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;

/**
 * Cross-platform filesystem tool. Same operations as
 * {@link WindowsFileSystemTool} (list / mkdir / move / copy / rename / delete),
 * gated by the same {@link PathGuard} allowlist and {@link MutationGuard} approval
 * chain — but exposed under a platform-neutral package and tool name so examples
 * and documentation no longer pretend the toolbox is Windows-only.
 *
 * <p>Implementation note: {@code WindowsFileSystemTool} has been pure
 * {@code java.nio.file} since its introduction; the "Windows" label is
 * historical. This subclass is a thin renamed surface — no behavioural change.
 *
 * <p>Activate with {@code swarmai.tools.os.enabled=true} (see
 * {@link OsToolsConfiguration}).
 */
public class FileSystemTool extends WindowsFileSystemTool {

    public FileSystemTool(PathGuard pathGuard, MutationGuard mutationGuard, boolean dryRunDefault) {
        super(pathGuard, mutationGuard, dryRunDefault);
    }

    /** Cross-platform tool name surfaced to the LLM and Spring AI bindings. */
    @Override
    public String getFunctionName() {
        return "os_filesystem";
    }

    /** Cross-platform category, distinct from the historical "windows" bucket. */
    @Override
    public String getCategory() {
        return "os";
    }
}
