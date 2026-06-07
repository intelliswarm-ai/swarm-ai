package ai.intelliswarm.swarmai.agent.subagent;

import java.nio.file.Path;

/**
 * Per-subagent filesystem area. Created by a
 * {@link java.util.function.Supplier} on the {@link SubagentSpec} and reaped
 * by the spawner when the subagent reaches a terminal status.
 *
 * <p>The simplest implementation is a fresh directory under the system temp
 * folder — see {@link EphemeralDirectoryWorkspace}. Future implementations
 * may back the workspace with a git worktree or a container mount; the
 * abstraction is the same: a {@link #root()} path scoped to one subagent
 * and an {@link #close()} that returns it to nothing.
 *
 * <p>Workspaces are intentionally ephemeral. Any artefact a subagent should
 * surface to the parent must be returned via the
 * {@link SubagentResult#output()} or recorded into a shared channel
 * <em>before</em> the workspace is closed.
 */
public interface IsolatedWorkspace extends AutoCloseable {

    /** Root directory of this workspace; always exists between create and close. */
    Path root();

    /**
     * Whether this workspace is currently materialised on disk. False after
     * {@link #close()} or for workspaces that haven't been initialised.
     */
    boolean isOpen();

    /**
     * Reap the workspace. Implementations should be tolerant of double-close
     * and of partial materialisation (e.g. when the spawner errors before
     * starting the subagent).
     */
    @Override
    void close();
}
