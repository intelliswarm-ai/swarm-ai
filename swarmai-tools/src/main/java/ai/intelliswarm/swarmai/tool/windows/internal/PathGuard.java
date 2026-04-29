package ai.intelliswarm.swarmai.tool.windows.internal;

import java.nio.file.Path;
import java.util.List;

/**
 * Backwards-compatible alias. The implementation moved to
 * {@link ai.intelliswarm.swarmai.tool.os.shared.PathGuard} when the cross-platform
 * OS tool category was introduced — see that class for behavior. This subclass
 * preserves the {@code tool.windows.internal.PathGuard} import path that the
 * existing Windows tools depend on.
 */
public final class PathGuard extends ai.intelliswarm.swarmai.tool.os.shared.PathGuard {

    public PathGuard(List<String> allowedRootsRaw) {
        super(allowedRootsRaw);
    }

    /** Re-exported for callers that imported the static expand() from this package. */
    public static String expand(String raw) {
        return ai.intelliswarm.swarmai.tool.os.shared.PathGuard.expand(raw);
    }

    /**
     * Re-throws the parent's {@link ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException}
     * as the legacy {@link PathNotAllowedException} subclass so existing tests and callers that
     * {@code catch} the legacy type still match. New code should catch the shared parent type.
     */
    @Override
    public java.nio.file.Path require(java.nio.file.Path candidate) {
        try {
            return super.require(candidate);
        } catch (ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException e) {
            throw new PathNotAllowedException(e.getMessage());
        }
    }

    @Override
    public java.nio.file.Path require(String candidate) {
        try {
            return super.require(candidate);
        } catch (ai.intelliswarm.swarmai.tool.os.shared.PathNotAllowedException e) {
            throw new PathNotAllowedException(e.getMessage());
        }
    }
}
