package ai.intelliswarm.swarmai.tool.os.shared;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allowlist for filesystem paths the OS-control tools may touch. A candidate path
 * is permitted only if its absolute, normalised form starts with one of the
 * configured allowed roots.
 *
 * <p>Roots and candidates are both normalised with {@link Path#normalize()}
 * after {@link Path#toAbsolutePath()}, which is enough to defeat plain {@code ..}
 * traversal. Symlink-based escapes are <em>not</em> defended against here — if
 * your allowed root contains a symlink that points outside it, this guard will
 * follow it. Avoid that or restrict to directories you control.
 *
 * <p>Strings may contain {@code ${user.home}}, {@code ${java.io.tmpdir}}, etc.,
 * which are expanded against the corresponding system properties — making the
 * same allowlist syntax work cross-platform (no hard-coded {@code C:\Users\…}).
 *
 * <p>Originally lived under {@code tool.windows.internal}; relocated here when
 * the cross-platform OS tool category was introduced. The Windows package
 * re-exports it from this location.
 */
public class PathGuard {

    private final List<Path> allowedRoots;

    public PathGuard(List<String> allowedRootsRaw) {
        Objects.requireNonNull(allowedRootsRaw, "allowedRootsRaw");
        List<Path> roots = new ArrayList<>();
        for (String raw : allowedRootsRaw) {
            String expanded = expand(raw);
            if (expanded == null || expanded.isBlank()) continue;
            roots.add(Paths.get(expanded).toAbsolutePath().normalize());
        }
        this.allowedRoots = List.copyOf(roots);
    }

    /** Returns true if the candidate sits under any allowed root. */
    public boolean isAllowed(Path candidate) {
        if (candidate == null || allowedRoots.isEmpty()) return false;
        Path normalised = candidate.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalised.equals(root) || normalised.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    public boolean isAllowed(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        return isAllowed(Paths.get(candidate));
    }

    /**
     * Returns the absolute, normalised form of {@code candidate} if it sits
     * under an allowed root. Throws otherwise.
     */
    public Path require(Path candidate) {
        if (candidate == null) {
            throw new PathNotAllowedException("path is null");
        }
        Path normalised = candidate.toAbsolutePath().normalize();
        for (Path root : allowedRoots) {
            if (normalised.equals(root) || normalised.startsWith(root)) {
                return normalised;
            }
        }
        throw new PathNotAllowedException(
                "path is outside allowlist: " + normalised
                        + " (allowed roots: " + allowedRoots + ")");
    }

    public Path require(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new PathNotAllowedException("path is empty");
        }
        return require(Paths.get(candidate));
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    /** Expand {@code ${prop}} placeholders against {@link System#getProperty(String)}. */
    public static String expand(String raw) {
        if (raw == null) return null;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int open = raw.indexOf("${", i);
            if (open < 0) {
                out.append(raw, i, raw.length());
                break;
            }
            out.append(raw, i, open);
            int close = raw.indexOf('}', open + 2);
            if (close < 0) {
                out.append(raw, open, raw.length());
                break;
            }
            String key = raw.substring(open + 2, close);
            String val = System.getProperty(key);
            out.append(val != null ? val : "${" + key + "}");
            i = close + 1;
        }
        return out.toString();
    }
}
