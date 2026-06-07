package ai.intelliswarm.swarmai.tool.os.shared;

import java.util.Locale;

/**
 * Detects the current host OS once and exposes it as an {@link OsType}.
 *
 * <p>Picked up via Spring as a singleton bean and injected into every cross-platform
 * tool. Tests can substitute a fake by constructing the bean directly with a different
 * {@link OsType}.
 */
public class OsContext {

    private final OsType type;

    public OsContext() {
        this(detect());
    }

    /** Test seam — set the OS explicitly. */
    public OsContext(OsType type) {
        this.type = type;
    }

    public OsType type() {
        return type;
    }

    public boolean isWindows() { return type == OsType.WINDOWS; }
    public boolean isMacOs()   { return type == OsType.MACOS; }
    public boolean isLinux()   { return type == OsType.LINUX; }

    static OsType detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return OsType.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return OsType.MACOS;
        if (name.contains("nux") || name.contains("nix") || name.contains("aix") || name.contains("bsd")) {
            return OsType.LINUX;
        }
        return OsType.UNKNOWN;
    }
}
