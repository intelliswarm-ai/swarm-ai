package ai.intelliswarm.swarmai.agent.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies a (toolName, arguments) pair into a risk tier so permission
 * gates and audit hooks can branch without re-deriving the category every
 * time. Pure function — no I/O, no state.
 *
 * <p>Tiers are ordered: {@link Tier#READ_ONLY} &lt; {@link Tier#NETWORK}
 * &lt; {@link Tier#MUTATION} &lt; {@link Tier#DESTRUCTIVE}. Higher tier
 * implies stricter permission gates (audit log entry, approval prompt,
 * dry-run preview, …).
 *
 * <p>The classifier uses three signals:
 * <ol>
 *   <li>Tool-name keyword matches against bundled lexicons (read/list/get for
 *       READ_ONLY, fetch/post for NETWORK, write/update for MUTATION, delete/
 *       drop/rm for DESTRUCTIVE).</li>
 *   <li>Argument-key inspection — a {@code path} or {@code url} arg promotes
 *       the tier when the tool name is ambiguous.</li>
 *   <li>Caller-supplied overrides via the constructor.</li>
 * </ol>
 *
 * <p>Conservative: when uncertain, returns the higher tier rather than the
 * lower one (false positives are cheaper than false negatives for safety).
 */
@PublicApi(since = "1.0.24")
public final class ToolCallRiskClassifier {

    @PublicApi(since = "1.0.24")
    public enum Tier {
        READ_ONLY,
        NETWORK,
        MUTATION,
        DESTRUCTIVE;

        public boolean atLeast(Tier other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    private static final Set<String> READ_KEYWORDS = Set.of(
            "read", "get", "list", "find", "search", "grep", "glob",
            "view", "show", "fetch_metadata", "describe", "info");
    private static final Set<String> NETWORK_KEYWORDS = Set.of(
            "fetch", "download", "scrape", "browse", "request", "call",
            "http", "rest", "api");
    private static final Set<String> MUTATION_KEYWORDS = Set.of(
            "write", "create", "update", "edit", "set", "put", "patch",
            "rename", "move", "save", "publish", "send");
    private static final Set<String> DESTRUCTIVE_KEYWORDS = Set.of(
            "delete", "drop", "remove", "rm", "destroy", "purge", "wipe",
            "truncate", "force", "cancel");

    private final Map<String, Tier> overrides;

    public ToolCallRiskClassifier() {
        this(Map.of());
    }

    /** Caller-supplied overrides keyed by exact tool name (case-insensitive). */
    public ToolCallRiskClassifier(Map<String, Tier> overrides) {
        Map<String, Tier> normalized = new java.util.HashMap<>();
        for (Map.Entry<String, Tier> e : overrides.entrySet()) {
            normalized.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.overrides = Map.copyOf(normalized);
    }

    public Tier classify(String toolName, Map<String, Object> args) {
        if (toolName == null) return Tier.MUTATION;
        String lower = toolName.toLowerCase(Locale.ROOT);

        Tier override = overrides.get(lower);
        if (override != null) return override;

        // Highest specificity first — destructive wins over mutation wins over network wins over read.
        if (containsAny(lower, DESTRUCTIVE_KEYWORDS)) return Tier.DESTRUCTIVE;
        if (containsAny(lower, MUTATION_KEYWORDS))    return Tier.MUTATION;
        if (containsAny(lower, NETWORK_KEYWORDS))     return Tier.NETWORK;
        if (containsAny(lower, READ_KEYWORDS))        return Tier.READ_ONLY;

        // Tool name didn't classify — argument-key inspection. A "path" arg
        // suggests local-FS interaction; absent any read keyword we conservatively
        // call it MUTATION. A "url" arg without a network keyword suggests NETWORK.
        if (args != null) {
            for (String key : args.keySet()) {
                String k = key.toLowerCase(Locale.ROOT);
                if (k.equals("path") || k.equals("filename") || k.equals("file_path")) {
                    return Tier.MUTATION;
                }
                if (k.equals("url") || k.equals("endpoint") || k.equals("uri")) {
                    return Tier.NETWORK;
                }
            }
        }
        // Total unknown — default to NETWORK (the safer assumption; READ_ONLY
        // would let arbitrary unknown tools through gates that protect against
        // exfiltration).
        return Tier.NETWORK;
    }

    private static boolean containsAny(String name, Set<String> keywords) {
        for (String kw : keywords) {
            if (name.contains(kw)) return true;
        }
        return false;
    }
}
