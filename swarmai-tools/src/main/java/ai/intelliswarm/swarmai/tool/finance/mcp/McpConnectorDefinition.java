package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.spi.LicenseProvider;

import java.util.List;
import java.util.Objects;

/**
 * Metadata for a financial MCP server connector. Pure data — no behaviour;
 * {@link McpConnectorRegistry} pairs definitions with the runtime
 * {@link ai.intelliswarm.swarmai.tool.mcp.McpToolAdapter} when license / config
 * permit activation.
 *
 * <p>Adding a new connector is a single-line entry in
 * {@link FinancialMcpConnectors}: no new class, no new component, no new
 * test scaffolding. The registry handles license-gating, the adapter handles
 * the actual HTTP plumbing.
 *
 * @param name              kebab-case identifier ({@code "lseg-lfa"})
 * @param displayName       UI/log-friendly name
 * @param vendor            data-provider organisation
 * @param endpoint          HTTPS URL the MCP client connects to
 * @param authScheme        how the connector authenticates
 * @param requiredEdition   minimum {@link LicenseProvider.Edition} that may activate this connector
 * @param requiredFeature   feature-flag id ({@code null} = no flag required beyond edition)
 * @param toolPreview       advertised tool names — informational, the live MCP handshake is authoritative
 * @param description       one-line summary for the catalog UI
 */
@PublicApi(since = "1.0.24")
public record McpConnectorDefinition(
        String name,
        String displayName,
        String vendor,
        String endpoint,
        AuthScheme authScheme,
        LicenseProvider.Edition requiredEdition,
        String requiredFeature,
        List<String> toolPreview,
        String description) {

    public McpConnectorDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(vendor, "vendor must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(authScheme, "authScheme must not be null");
        Objects.requireNonNull(requiredEdition, "requiredEdition must not be null");
        toolPreview = toolPreview == null ? List.of() : List.copyOf(toolPreview);
    }

    /**
     * Whether the supplied license satisfies this connector's gating rules.
     * Checks edition floor first (cheap), then optional feature flag.
     */
    public boolean isLicensed(LicenseProvider license) {
        if (license == null) return false;
        if (license.getLicense().edition().ordinal() < requiredEdition.ordinal()) {
            return false;
        }
        if (requiredFeature != null && !requiredFeature.isBlank()) {
            return license.hasFeature(requiredFeature);
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder(name, displayName, vendor, endpoint, authScheme,
                requiredEdition, requiredFeature, toolPreview, description);
    }

    public static Builder builder(String name) {
        return new Builder(name, name, "", "", AuthScheme.NONE,
                LicenseProvider.Edition.COMMUNITY, null, List.of(), "");
    }

    @PublicApi(since = "1.0.24")
    public enum AuthScheme {
        /** OAuth 2.0 (LSEG, Aiera). */
        OAUTH,
        /** Static bearer token issued by the vendor. */
        BEARER,
        /** API key as header value. */
        API_KEY,
        /** Open / no auth (rare; mostly dev/sandbox endpoints). */
        NONE
    }

    @PublicApi(since = "1.0.24")
    public static final class Builder {
        private String name;
        private String displayName;
        private String vendor;
        private String endpoint;
        private AuthScheme authScheme;
        private LicenseProvider.Edition requiredEdition;
        private String requiredFeature;
        private List<String> toolPreview;
        private String description;

        Builder(String name, String displayName, String vendor, String endpoint,
                AuthScheme authScheme, LicenseProvider.Edition requiredEdition,
                String requiredFeature, List<String> toolPreview, String description) {
            this.name = name;
            this.displayName = displayName;
            this.vendor = vendor;
            this.endpoint = endpoint;
            this.authScheme = authScheme;
            this.requiredEdition = requiredEdition;
            this.requiredFeature = requiredFeature;
            this.toolPreview = toolPreview;
            this.description = description;
        }

        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder vendor(String v) { this.vendor = v; return this; }
        public Builder endpoint(String v) { this.endpoint = v; return this; }
        public Builder authScheme(AuthScheme v) { this.authScheme = v; return this; }
        public Builder requiredEdition(LicenseProvider.Edition v) { this.requiredEdition = v; return this; }
        public Builder requiredFeature(String v) { this.requiredFeature = v; return this; }
        public Builder toolPreview(List<String> v) { this.toolPreview = v; return this; }
        public Builder toolPreview(String... v) { this.toolPreview = List.of(v); return this; }
        public Builder description(String v) { this.description = v; return this; }

        public McpConnectorDefinition build() {
            return new McpConnectorDefinition(name, displayName, vendor, endpoint, authScheme,
                    requiredEdition, requiredFeature, toolPreview, description);
        }
    }
}
