package ai.intelliswarm.swarmai.tool.finance.mcp;

import ai.intelliswarm.swarmai.spi.LicenseProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpConnectorRegistryTest {

    @Test
    void catalogContainsExpectedConnectors() {
        var catalog = FinancialMcpConnectors.all();
        assertThat(catalog).extracting(McpConnectorDefinition::name)
                .contains("lseg-lfa", "sp-capital-iq", "factset", "pitchbook",
                        "daloopa", "morningstar", "moodys", "egnyte");
    }

    @Test
    void communityLicenseActivatesNothing() {
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(LicenseProvider.COMMUNITY);
        assertThat(registry.activeConnectors()).isEmpty();
    }

    @Test
    void teamLicenseUnlocksOnlyTeamTierWithFeatureFlag() {
        LicenseProvider team = fakeLicense(LicenseProvider.Edition.TEAM, Set.of("mcp.egnyte"));
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(team);
        assertThat(registry.activeConnectors())
                .extracting(McpConnectorDefinition::name)
                .containsExactly("egnyte");
    }

    @Test
    void businessLicenseUnlocksTeamAndBusinessTiers() {
        LicenseProvider business = fakeLicense(LicenseProvider.Edition.BUSINESS,
                Set.of("mcp.daloopa", "mcp.morningstar", "mcp.egnyte"));
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(business);
        assertThat(registry.activeConnectors())
                .extracting(McpConnectorDefinition::name)
                .containsExactlyInAnyOrder("daloopa", "morningstar", "egnyte");
    }

    @Test
    void enterpriseLicenseWithAllFlagsUnlocksEverything() {
        LicenseProvider ent = fakeLicense(LicenseProvider.Edition.ENTERPRISE, Set.of(
                "mcp.lseg", "mcp.sp_capital_iq", "mcp.factset", "mcp.pitchbook",
                "mcp.chronograph", "mcp.daloopa", "mcp.morningstar", "mcp.moodys",
                "mcp.mt_newswires", "mcp.aiera", "mcp.egnyte"));
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(ent);
        assertThat(registry.activeConnectors()).hasSize(11);
    }

    @Test
    void licensedConnectorsIgnoresExplicitOverrides() {
        LicenseProvider ent = fakeLicense(LicenseProvider.Edition.ENTERPRISE, Set.of(
                "mcp.lseg", "mcp.factset"));
        McpConnectorRegistry registry = new McpConnectorRegistry(
                FinancialMcpConnectors.all(), ent,
                Set.of(), Set.of("factset"));
        assertThat(registry.licensedConnectors())
                .extracting(McpConnectorDefinition::name)
                .contains("lseg-lfa", "factset");
        // activeConnectors excludes the explicitly-disabled factset
        assertThat(registry.activeConnectors())
                .extracting(McpConnectorDefinition::name)
                .contains("lseg-lfa")
                .doesNotContain("factset");
    }

    @Test
    void explicitlyEnabledActsAsAllowlist() {
        LicenseProvider ent = fakeLicense(LicenseProvider.Edition.ENTERPRISE, Set.of(
                "mcp.lseg", "mcp.factset", "mcp.pitchbook"));
        McpConnectorRegistry registry = new McpConnectorRegistry(
                FinancialMcpConnectors.all(), ent,
                Set.of("factset"), Set.of());
        // Only factset is activated, even though three are licensed
        assertThat(registry.activeConnectors())
                .extracting(McpConnectorDefinition::name)
                .containsExactly("factset");
    }

    @Test
    void statusReportExplainsWhyEachConnectorIsOrIsntActive() {
        LicenseProvider business = fakeLicense(LicenseProvider.Edition.BUSINESS,
                Set.of("mcp.daloopa")); // morningstar/moodys/etc. lack the flag
        McpConnectorRegistry registry = McpConnectorRegistry.defaultRegistry(business);
        var report = registry.statusReport();

        var lseg = report.get("lseg-lfa");
        assertThat(lseg.status()).isEqualTo(McpConnectorRegistry.Status.LICENSE_FLOOR_NOT_MET);
        assertThat(lseg.reason()).contains("ENTERPRISE");

        var daloopa = report.get("daloopa");
        assertThat(daloopa.status()).isEqualTo(McpConnectorRegistry.Status.ACTIVE);

        var morningstar = report.get("morningstar");
        assertThat(morningstar.status()).isEqualTo(McpConnectorRegistry.Status.FEATURE_FLAG_MISSING);
        assertThat(morningstar.reason()).contains("mcp.morningstar");
    }

    @Test
    void featureFlagNotRequiredWhenDefinitionDoesntDeclareOne() {
        var def = McpConnectorDefinition.builder("free-fred")
                .displayName("FRED")
                .vendor("Federal Reserve")
                .endpoint("https://example/fred/mcp")
                .authScheme(McpConnectorDefinition.AuthScheme.NONE)
                .requiredEdition(LicenseProvider.Edition.COMMUNITY)
                .requiredFeature(null) // no flag required
                .build();
        McpConnectorRegistry registry = new McpConnectorRegistry(
                java.util.List.of(def), LicenseProvider.COMMUNITY, Set.of(), Set.of());
        assertThat(registry.activeConnectors()).hasSize(1);
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
