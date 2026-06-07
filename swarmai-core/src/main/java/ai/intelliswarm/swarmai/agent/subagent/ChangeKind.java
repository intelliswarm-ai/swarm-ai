package ai.intelliswarm.swarmai.agent.subagent;

/**
 * Categorical kinds of file changes a {@link GitWorktreeWorkspace} can
 * surface via {@code git diff --name-status}. Maps directly to git's
 * single-letter status codes.
 */
public enum ChangeKind {

    /** New file in the workspace that didn't exist at the base ref ({@code A}). */
    ADDED,

    /** File existed in the base and was modified ({@code M}). */
    MODIFIED,

    /** File existed in the base and was deleted in the workspace ({@code D}). */
    DELETED,

    /** File was moved/renamed; both old and new paths are recorded ({@code R}). */
    RENAMED,

    /** Anything git classifies as something else (type changes, copies, etc.). */
    OTHER;

    /** Parse a single-letter git status code. Multi-letter codes (R100) reduce to RENAMED. */
    public static ChangeKind fromGitCode(String code) {
        if (code == null || code.isEmpty()) return OTHER;
        char first = code.charAt(0);
        return switch (first) {
            case 'A' -> ADDED;
            case 'M' -> MODIFIED;
            case 'D' -> DELETED;
            case 'R' -> RENAMED;
            default -> OTHER;
        };
    }
}
