package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;
import ai.intelliswarm.swarmai.governance.*;
import ai.intelliswarm.swarmai.spi.AuditSink;
import ai.intelliswarm.swarmai.spi.LicenseProvider;
import ai.intelliswarm.swarmai.spi.MeteringSink;
import ai.intelliswarm.swarmai.tenant.TenantContext;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Enterprise capability scenarios.
 */
public class EnterpriseScenarios {

    /** Verifies tenant context isolation. */
    public static EvalScenario tenantIsolation() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "enterprise-tenant-isolation"; }
            @Override public String name() { return "Tenant Context Isolation"; }
            @Override public String category() { return "ENTERPRISE"; }
            @Override public String description() { return "Verify tenant context sets/reads correctly"; }

            @Override
            protected ScenarioResult doExecute() {
                try {
                    TenantContext.setTenantId("tenant-alpha");
                    boolean set = "tenant-alpha".equals(TenantContext.currentTenantId());
                    TenantContext.setTenantId("tenant-beta");
                    boolean overwritten = "tenant-beta".equals(TenantContext.currentTenantId());
                    ObservabilityContext.clear();
                    boolean cleared = TenantContext.currentTenantId() == null;

                    boolean valid = set && overwritten && cleared;
                    return valid
                            ? ScenarioResult.pass(id(), name(), category(), 100.0,
                            "Tenant context isolates correctly", Duration.ZERO)
                            : ScenarioResult.fail(id(), name(), category(),
                            "Tenant context not isolated", Duration.ZERO);
                } finally {
                    ObservabilityContext.clear();
                }
            }
        };
    }

    /** Verifies SPI interfaces have working defaults. */
    public static EvalScenario spiDefaults() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "enterprise-spi-defaults"; }
            @Override public String name() { return "SPI Default Implementations"; }
            @Override public String category() { return "ENTERPRISE"; }
            @Override public String description() { return "Verify AuditSink.NOOP, MeteringSink.NOOP, LicenseProvider.COMMUNITY work"; }

            @Override
            protected ScenarioResult doExecute() {
                // AuditSink.NOOP should not throw
                AuditSink.NOOP.record(new AuditSink.AuditEntry(
                        "id-1", java.time.Instant.now(), null, null, "test", null, null, null, Map.of()));

                // MeteringSink.NOOP should not throw
                MeteringSink.NOOP.recordTokenUsage(new MeteringSink.TokenUsageRecord(
                        "wf-1", null, "gpt-4o", 100, 50, 0.01));

                // LicenseProvider.COMMUNITY should be valid with no features
                LicenseProvider community = LicenseProvider.COMMUNITY;
                boolean valid = community.isValid()
                        && !community.hasFeature("multi-tenancy")
                        && community.getLicense().edition() == LicenseProvider.Edition.COMMUNITY;

                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "All SPI defaults work correctly", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "SPI default behavior incorrect", Duration.ZERO);
            }
        };
    }

    /** Verifies governance model types work correctly. */
    public static EvalScenario governanceModel() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "enterprise-governance"; }
            @Override public String name() { return "Governance Model"; }
            @Override public String category() { return "ENTERPRISE"; }
            @Override public String description() { return "Verify ApprovalGate/Policy/Request types"; }

            @Override
            protected ScenarioResult doExecute() {
                ApprovalGate gate = ApprovalGate.builder()
                        .name("Test Gate")
                        .description("Test")
                        .trigger(GateTrigger.BEFORE_TASK)
                        .timeout(Duration.ofMinutes(5))
                        .policy(new ApprovalPolicy(1, List.of(), true))
                        .build();

                boolean valid = "Test Gate".equals(gate.name())
                        && gate.trigger() == GateTrigger.BEFORE_TASK
                        && gate.policy().autoApproveOnTimeout();

                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Governance model types work correctly", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Governance type validation failed", Duration.ZERO);
            }
        };
    }

    public static List<EvalScenario> all() {
        return List.of(
                tenantIsolation(),
                spiDefaults(),
                governanceModel()
        );
    }
}
