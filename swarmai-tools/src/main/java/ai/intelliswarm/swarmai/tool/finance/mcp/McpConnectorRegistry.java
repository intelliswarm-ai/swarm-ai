package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.spi.LicenseProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Filters a catalog of {@link McpConnectorDefinition}s by:
 * <ol>
 *   <li>Active license — edition floor + feature flag</li>
 *   <li>Caller-supplied opt-in/opt-out configuration — even an
 *       enterprise-licensed connector stays inactive if config disables it</li>
 * </ol>
 *
 * <p>The registry is build-once-read-many; instantiate at startup with the
 * full catalog from {@link FinancialMcpConnectors#all()} (or a custom list)
 * plus the live {@link LicenseProvider}, then call {@link #activeConnectors()}.
 *
 * <p>For production wiring, pass to the existing MCP adapter machinery in
 * {@code swarmai-core/tool/mcp/} — each active connector becomes a
 * {@code BaseTool} via {@code McpToolAdapter}. Phase 8 stops here at the
 * data + filter layer; the wiring step is a follow-up under task #15.
 */
@PublicApi(since = "1.0.24")
public final class McpConnectorRegistry {

    private final List<McpConnectorDefinition> catalog;
    private final LicenseProvider license;
    private final Set<String> explicitlyEnabled;
    private final Set<String> explicitlyDisabled;

    public McpConnectorRegistry(Collection<McpConnectorDefinition> catalog,
                                LicenseProvider license,
                                Set<String> explicitlyEnabled,
                                Set<String> explicitlyDisabled) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        this.catalog = List.copyOf(catalog);
        this.license = license != null ? license : LicenseProvider.COMMUNITY;
        this.explicitlyEnabled = explicitlyEnabled == null ? Set.of() : Set.copyOf(explicitlyEnabled);
        this.explicitlyDisabled = explicitlyDisabled == null ? Set.of() : Set.copyOf(explicitlyDisabled);
    }

    /** Convenience: full curated catalog, current license, no explicit overrides. */
    public static McpConnectorRegistry defaultRegistry(LicenseProvider license) {
        return new McpConnectorRegistry(
                FinancialMcpConnectors.all(), license, Set.of(), Set.of());
    }

    public List<McpConnectorDefinition> catalog() {
        return catalog;
    }

    /** Connectors that may be activated under the current license. */
    public List<McpConnectorDefinition> licensedConnectors() {
        List<McpConnectorDefinition> out = new ArrayList<>();
        for (McpConnectorDefinition def : catalog) {
            if (def.isLicensed(license)) out.add(def);
        }
        return List.copyOf(out);
    }

    /**
     * Final activation list: licensed AND not explicitly disabled. If the
     * {@code explicitlyEnabled} set is non-empty, it acts as an allowlist
     * (only those names activate, the rest stay dormant even if licensed).
     */
    public List<McpConnectorDefinition> activeConnectors() {
        List<McpConnectorDefinition> out = new ArrayList<>();
        for (McpConnectorDefinition def : catalog) {
            if (explicitlyDisabled.contains(def.name())) continue;
            if (!explicitlyEnabled.isEmpty() && !explicitlyEnabled.contains(def.name())) continue;
            if (!def.isLicensed(license)) continue;
            out.add(def);
        }
        return List.copyOf(out);
    }

    /** Why each catalog entry is or isn't active — useful for the {@code /mcp status} UI. */
    public Map<String, Status.Result> statusReport() {
        Map<String, Status.Result> report = new LinkedHashMap<>();
        for (McpConnectorDefinition def : catalog) {
            Status status;
            String reason;
            if (explicitlyDisabled.contains(def.name())) {
                status = Status.DISABLED;
                reason = "Explicitly disabled by configuration";
            } else if (!explicitlyEnabled.isEmpty() && !explicitlyEnabled.contains(def.name())) {
                status = Status.DISABLED;
                reason = "Not in explicit enable list";
            } else if (license.getLicense().edition().ordinal() < def.requiredEdition().ordinal()) {
                status = Status.LICENSE_FLOOR_NOT_MET;
                reason = "Requires edition " + def.requiredEdition()
                        + " (current: " + license.getLicense().edition() + ")";
            } else if (def.requiredFeature() != null && !license.hasFeature(def.requiredFeature())) {
                status = Status.FEATURE_FLAG_MISSING;
                reason = "Feature flag not enabled: " + def.requiredFeature();
            } else {
                status = Status.ACTIVE;
                reason = "Licensed and enabled";
            }
            report.put(def.name(), new Status.Result(status, reason, def));
        }
        return Map.copyOf(report);
    }

    @PublicApi(since = "1.0.24")
    public enum Status {
        ACTIVE, LICENSE_FLOOR_NOT_MET, FEATURE_FLAG_MISSING, DISABLED;

        @PublicApi(since = "1.0.24")
        public record Result(Status status, String reason, McpConnectorDefinition definition) {}
    }
}
