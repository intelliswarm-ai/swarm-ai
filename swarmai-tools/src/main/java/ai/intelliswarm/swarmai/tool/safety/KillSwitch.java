package ai.intelliswarm.swarmai.tool.safety;

/**
 * Cheap, always-checked global off-switch for the local-control tools.
 *
 * <p>Truthy values: {@code true}, {@code 1}, {@code yes}, {@code y} (case-insensitive).
 * Anything else (including {@code null}) is treated as "not disabled".
 *
 * <p>Two layers, checked in order — the first {@code true} wins:
 * <ol>
 *   <li>Environment variable {@code SWARMAI_TOOLS_DISABLED} — designed for an
 *       operator to flip without restarting Spring.</li>
 *   <li>JVM system property {@code swarmai.tools.disabled} — designed for tests
 *       and per-run overrides.</li>
 * </ol>
 *
 * <p>Tools that touch external state should call {@link #checkOrThrow()} (or
 * test {@link #isDisabled()} themselves) at the top of their entry points.
 */
public final class KillSwitch {

    public static final String ENV = "SWARMAI_TOOLS_DISABLED";
    public static final String PROP = "swarmai.tools.disabled";

    private KillSwitch() {}

    /** True if the global kill-switch is currently set. */
    public static boolean isDisabled() {
        return isTruthy(System.getenv(ENV)) || isTruthy(System.getProperty(PROP));
    }

    /** Throws {@link KillSwitchEngagedException} if the kill-switch is set. */
    public static void checkOrThrow() {
        if (isDisabled()) {
            throw new KillSwitchEngagedException(
                    "SwarmAI tools are disabled by " + ENV + " / " + PROP);
        }
    }

    private static boolean isTruthy(String s) {
        if (s == null) return false;
        String lc = s.trim().toLowerCase(java.util.Locale.ROOT);
        return lc.equals("true") || lc.equals("1") || lc.equals("yes") || lc.equals("y");
    }
}
