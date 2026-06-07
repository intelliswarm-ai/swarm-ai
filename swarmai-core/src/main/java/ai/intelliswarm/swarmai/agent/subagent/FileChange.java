package ai.intelliswarm.swarmai.agent.subagent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One file's worth of difference between a workspace's current state and the
 * base ref it forked from. Produced by {@link GitWorktreeWorkspace#diff()}
 * and consumed by skill-curation, self-improvement, and test-evaluation
 * code that needs to know *what changed*.
 *
 * @param path          repo-relative path of the changed file
 * @param kind          how the file changed
 * @param linesAdded    lines added (numstat); -1 when not parseable (binary files etc.)
 * @param linesRemoved  lines removed (numstat); -1 when not parseable
 * @param renamedFrom   for {@link ChangeKind#RENAMED}, the previous path; empty otherwise
 */
public record FileChange(
        Path path,
        ChangeKind kind,
        int linesAdded,
        int linesRemoved,
        Path renamedFrom) {

    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        // renamedFrom may be null
    }

    /** Convenience: path-only change without numstat. */
    public static FileChange of(Path path, ChangeKind kind) {
        return new FileChange(path, kind, -1, -1, null);
    }

    /** Convenience: with numstat lines. */
    public static FileChange of(Path path, ChangeKind kind, int added, int removed) {
        return new FileChange(path, kind, added, removed, null);
    }

    /** Convenience: rename change. */
    public static FileChange renamed(Path from, Path to, int added, int removed) {
        return new FileChange(to, ChangeKind.RENAMED, added, removed, from);
    }

    public Optional<Path> renamedFromOpt() {
        return Optional.ofNullable(renamedFrom);
    }

    /** True when numstat output was parseable (non-binary file). */
    public boolean hasLineCounts() {
        return linesAdded >= 0 && linesRemoved >= 0;
    }
}
