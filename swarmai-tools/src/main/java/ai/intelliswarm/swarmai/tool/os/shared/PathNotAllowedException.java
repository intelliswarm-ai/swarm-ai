package ai.intelliswarm.swarmai.tool.os.shared;

/**
 * Thrown when a path passes outside the configured allowlist roots — either
 * via direct reference or via a {@code ..} traversal that resolves outside.
 */
public class PathNotAllowedException extends RuntimeException {
    public PathNotAllowedException(String message) {
        super(message);
    }
}
