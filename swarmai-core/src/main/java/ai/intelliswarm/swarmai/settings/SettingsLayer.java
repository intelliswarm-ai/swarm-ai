package ai.intelliswarm.swarmai.settings;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Configuration precedence tier. Settings from a higher-priority layer override
 * the same key in lower-priority layers. Read order: PROJECT &gt; USER &gt; MACHINE.
 *
 * <p>Mirrors how editor / shell / git config files compose: the most-specific
 * layer wins, with sensible system-wide defaults at the bottom.
 *
 * <ul>
 *   <li>{@link #PROJECT} — repo-local config (e.g. {@code ./.swarmai/settings.yml}).
 *       Tracked in version control. Authoritative for project-scoped behaviour
 *       (model selection, tool allow-list, governance gates).</li>
 *   <li>{@link #USER} — per-user config (e.g. {@code ~/.swarmai/settings.yml}).
 *       Not in version control. Authoritative for personal preferences
 *       (verbose mode, custom tool aliases, skill caches).</li>
 *   <li>{@link #MACHINE} — host-wide config (e.g. {@code /etc/swarmai/settings.yml}).
 *       Set by ops. Authoritative for site-wide defaults (proxy URL, telemetry
 *       endpoint, fallback API base).</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum SettingsLayer {
    /** Highest priority. */
    PROJECT,
    USER,
    /** Lowest priority. */
    MACHINE;

    /**
     * Default search path for this layer's settings file. Implementations may
     * override per platform; this is the conventional UNIX layout.
     */
    public java.nio.file.Path defaultPath() {
        return switch (this) {
            case PROJECT -> java.nio.file.Path.of(".swarmai", "settings.yml");
            case USER -> java.nio.file.Path.of(System.getProperty("user.home"),
                    ".swarmai", "settings.yml");
            case MACHINE -> java.nio.file.Path.of("/etc", "swarmai", "settings.yml");
        };
    }
}
