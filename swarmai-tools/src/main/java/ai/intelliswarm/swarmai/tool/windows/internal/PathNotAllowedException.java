package ai.intelliswarm.swarmai.tool.windows.internal;

/**
 * Backwards-compatible alias for the relocated exception.
 *
 * <p>The canonical type is now
 * {@link ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException}. This subclass
 * preserves the {@code tool.windows.internal.PathNotAllowedException} import path that
 * the Windows tools and their tests depend on. Newly-written code should import the
 * shared type directly.
 */
public class PathNotAllowedException
        extends ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException {

    public PathNotAllowedException(String message) {
        super(message);
    }
}
