package ai.intelliswarm.swarmai.tool.os.shared;

/**
 * The host operating system the JVM is running on, normalized to a small enum
 * the cross-platform OS tools can switch on without re-parsing
 * {@code System.getProperty("os.name")} every time.
 */
public enum OsType {
    WINDOWS, MACOS, LINUX, UNKNOWN;

    /** Returns true for the POSIX-shaped OSes — useful when a tool can share one Unix code path. */
    public boolean isPosix() {
        return this == MACOS || this == LINUX;
    }
}
