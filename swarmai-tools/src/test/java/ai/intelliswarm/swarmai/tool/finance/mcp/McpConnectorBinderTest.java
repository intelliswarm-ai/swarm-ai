package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.spi.LicenseProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectorBinderTest {

    @Test
    void environmentResolverReadsConnectorSpecificVars() {
        Map<String, String> env = new HashMap<>();
        env.put("SWARM_MCP_LSEG_LFA_BEARER", "abc123");
        env.put("SWARM_MCP_FACTSET_API_KEY", "xyz");
        env.put("SWARM_MCP_FACTSET_API_KEY_HEADER", "X-FactSet-Key");

        McpCredentialResolver resolver = new McpCredentialResolver.FromEnvironment(env::get);
        Optional<McpCredentials> lseg = resolver.resolve(FinancialMcpConnectors.lsegLfa());
        assertThat(lseg).isPresent();
        assertThat(lseg.get().bearerToken()).isEqualTo("abc123");
        assertThat(lseg.get().authorizationHeaderValue()).isEqualTo("Bearer abc123");

        Optional<McpCredentials> fs = resolver.resolve(FinancialMcpConnectors.factset());
        assertThat(fs).isPresent();
        assertThat(fs.get().apiKey()).isEqualTo("xyz");
        assertThat(fs.get().apiKeyHeader()).isEqualTo("X-FactSet-Key");
    }

    @Test
    void inMemoryResolverReturnsConfiguredCredentials() {
        var creds = new McpCredentialResolver.InMemory(Map.of(
                "lseg-lfa", McpCredentials.bearer("token123"),
                "factset", McpCredentials.apiKey("apikey")));
        assertThat(creds.resolve(FinancialMcpConnectors.lsegLfa()).get().bearerToken())
                .isEqualTo("token123");
        assertThat(creds.resolve(FinancialMcpConnectors.factset()).get().apiKey()).isEqualTo("apikey");
        assertThat(creds.resolve(FinancialMcpConnectors.aiera())).isEmpty();
    }

    @Test
    void statusReportSurfacesEachStateClearly() {
        LicenseProvider entLicense = fakeLicense(LicenseProvider.Edition.ENTERPRISE,
                Set.of("mcp.lseg", "mcp.factset", "mcp.pitchbook"));
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(entLicense);

        // Credentials configured for lseg + factset, missing for pitchbook
        McpCredentialResolver resolver = new McpCredentialResolver.InMemory(Map.of(
                "lseg-lfa", McpCredentials.bearer("token"),
                "factset", McpCredentials.apiKey("key")));

        McpConnectorBinder binder = new McpConnectorBinder(registry, resolver);
        var report = binder.statusReport();

        assertThat(report.get("lseg-lfa").result()).isEqualTo(McpConnectorBinder.BindResult.READY);
        assertThat(report.get("factset").result()).isEqualTo(McpConnectorBinder.BindResult.READY);
        assertThat(report.get("pitchbook").result())
                .isEqualTo(McpConnectorBinder.BindResult.CREDENTIALS_MISSING);
        assertThat(report.get("daloopa").result())
                .isEqualTo(McpConnectorBinder.BindResult.NOT_ACTIVE);  // feature flag missing
    }

    @Test
    void credentialsAuthHeaderForBearerOrOAuth() {
        assertThat(McpCredentials.bearer("t").authorizationHeaderValue()).isEqualTo("Bearer t");
        assertThat(McpCredentials.oauth("o").authorizationHeaderValue()).isEqualTo("Bearer o");
        assertThat(McpCredentials.apiKey("k").authorizationHeaderValue()).isNull();
        assertThat(McpCredentials.NONE.authorizationHeaderValue()).isNull();
    }

    @Test
    void noneResolverAlwaysReturnsEmpty() {
        Optional<McpCredentials> result = McpCredentialResolver.NONE.resolve(
                FinancialMcpConnectors.lsegLfa());
        assertThat(result).isEmpty();
    }

    private static LicenseProvider fakeLicense(LicenseProvider.Edition edition, Set<String> features) {
        return new LicenseProvider() {
            @Override public LicenseInfo getLicense() {
                return new LicenseInfo("test", edition, Instant.now().plusSeconds(86400), 100, features);
            }
            @Override public boolean hasFeature(String f) { return features.contains(f); }
            @Override public boolean isValid() { return true; }
        };
    }
}
