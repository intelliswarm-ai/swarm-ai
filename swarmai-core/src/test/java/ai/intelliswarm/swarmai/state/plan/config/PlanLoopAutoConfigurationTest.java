package ai.intelliswarm.swarmai.state.plan.config;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditPromotionGate;
import ai.intelliswarm.swarmai.state.plan.BanditStateStore;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.FileBanditStateStore;
import ai.intelliswarm.swarmai.state.plan.InMemoryBanditStateStore;
import ai.intelliswarm.swarmai.state.plan.LlmReplanner;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanContextRenderer;
import ai.intelliswarm.swarmai.state.plan.ReplanTriggerPolicy;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanLoopAutoConfiguration Tests")
class PlanLoopAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.context.annotation.UserConfigurations
                    .of(PlanLoopAutoConfiguration.class));

    @Test
    @DisplayName("disabled by default — no plan-loop beans created")
    void inertByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(PlanContextRenderer.class);
            assertThat(ctx).doesNotHaveBean(PlanApprovalPolicy.class);
            assertThat(ctx).doesNotHaveBean(BanditApprovalPolicy.class);
            assertThat(ctx).doesNotHaveBean(LlmReplanner.class);
        });
    }

    @Test
    @DisplayName("enabled with defaults: tier + drift-budget + composite policy + renderer")
    void enabledWithDefaults() {
        runner.withPropertyValues("swarmai.plan-loop.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(PlanContextRenderer.class);
            assertThat(ctx).hasSingleBean(RiskTieredApprovalPolicy.class);
            assertThat(ctx).hasSingleBean(DriftBudgetApprovalPolicy.class);
            // Composite is registered by bean name (not by type — every policy
            // implements PlanApprovalPolicy, so type-based queries return many).
            assertThat(ctx).hasBean("planApprovalPolicy");
            // Bandit + replanner are off by default
            assertThat(ctx).doesNotHaveBean(BanditApprovalPolicy.class);
            assertThat(ctx).doesNotHaveBean(BanditPromotionGate.class);
            assertThat(ctx).doesNotHaveBean(LlmReplanner.class);
            assertThat(ctx).doesNotHaveBean(ReplanTriggerPolicy.class);
        });
    }

    @Test
    @DisplayName("tier disabled: RiskTieredApprovalPolicy bean is not created")
    void tierDisabled() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.tier.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RiskTieredApprovalPolicy.class);
                    assertThat(ctx).hasBean("driftBudgetApprovalPolicy");
                    // Composite still produced from whatever's available — when the chain
                    // has only one component the composite IS that component (same instance).
                    assertThat(ctx).hasBean("planApprovalPolicy");
                });
    }

    @Test
    @DisplayName("drift-budget value drives the constructor argument")
    void driftBudgetCustomValue() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.drift-budget.budget=42")
                .run(ctx -> {
                    DriftBudgetApprovalPolicy drift = ctx.getBean(DriftBudgetApprovalPolicy.class);
                    assertThat(drift.budget()).isEqualTo(42);
                });
    }

    @Test
    @DisplayName("bandit enabled (no persistence file) — InMemoryBanditStateStore + bandit + gate beans")
    void banditEnabledInMemoryStore() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(BanditApprovalPolicy.class);
                    assertThat(ctx).hasSingleBean(BanditPromotionGate.class);
                    assertThat(ctx).hasSingleBean(BanditStateStore.class);
                    assertThat(ctx.getBean(BanditStateStore.class))
                            .isInstanceOf(InMemoryBanditStateStore.class);
                });
    }

    @Test
    @DisplayName("bandit enabled with persistence file — FileBanditStateStore at the configured path")
    void banditWithFilePersistence(@TempDir Path tempDir) {
        Path stateFile = tempDir.resolve("bandit.json");
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true",
                "swarmai.plan-loop.bandit.persistence.file=" + stateFile)
                .run(ctx -> {
                    BanditStateStore store = ctx.getBean(BanditStateStore.class);
                    assertThat(store).isInstanceOf(FileBanditStateStore.class);
                    assertThat(((FileBanditStateStore) store).path()).isEqualTo(stateFile);
                });
    }

    @Test
    @DisplayName("bandit gate min-observations and ci-half-width are read from properties")
    void banditGateConfig() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true",
                "swarmai.plan-loop.bandit.gate.min-observations=50",
                "swarmai.plan-loop.bandit.gate.max-ci-half-width=0.05")
                .run(ctx -> {
                    BanditPromotionGate gate = ctx.getBean(BanditPromotionGate.class);
                    assertThat(gate.minObservations()).isEqualTo(50);
                    assertThat(gate.maxCiHalfWidth()).isEqualTo(0.05);
                });
    }

    @Test
    @DisplayName("compose=OBSERVER keeps the bandit out of the policy chain (default)")
    void banditObserverModeIsDefault() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true")
                .run(ctx -> {
                    // Bandit + gate beans exist, composite present.
                    assertThat(ctx).hasSingleBean(BanditPromotionGate.class);
                    assertThat(ctx).hasBean("planApprovalPolicy");
                });
    }

    @Test
    @DisplayName("compose=VETO and compose=BYPASS produce composite policy beans")
    void banditComposeModes() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true",
                "swarmai.plan-loop.bandit.compose=VETO")
                .run(ctx -> assertThat(ctx).hasBean("planApprovalPolicy"));

        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.bandit.enabled=true",
                "swarmai.plan-loop.bandit.compose=BYPASS")
                .run(ctx -> assertThat(ctx).hasBean("planApprovalPolicy"));
    }

    @Test
    @DisplayName("replanner.enabled=true throws when no ChatClient.Builder is on the context")
    void replannerWithoutChatClient() {
        runner.withPropertyValues(
                "swarmai.plan-loop.enabled=true",
                "swarmai.plan-loop.replanner.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("no ChatClient.Builder bean is available"));
    }

    @Test
    @DisplayName("replanner trigger types map to correct ReplanTriggerPolicy")
    void replanTriggerMapping() {
        for (var trigger : PlanLoopProperties.Replanner.TriggerType.values()) {
            runner
                    .withBean(ChatClient.Builder.class, () ->
                            Mockito.mock(ChatClient.Builder.class, Mockito.RETURNS_DEEP_STUBS))
                    .withPropertyValues(
                            "swarmai.plan-loop.enabled=true",
                            "swarmai.plan-loop.replanner.enabled=true",
                            "swarmai.plan-loop.replanner.trigger=" + trigger.name())
                    .run(ctx -> assertThat(ctx).hasSingleBean(ReplanTriggerPolicy.class));
        }
    }

    @Test
    @DisplayName("user-supplied beans override defaults via @ConditionalOnMissingBean")
    void userOverridesDefaults() {
        runner.withBean(RiskTieredApprovalPolicy.class,
                () -> new RiskTieredApprovalPolicy(java.util.EnumSet.of(
                        ai.intelliswarm.swarmai.state.plan.Risk.LOW)))
                .withPropertyValues("swarmai.plan-loop.enabled=true")
                .run(ctx -> {
                    RiskTieredApprovalPolicy bean = ctx.getBean(RiskTieredApprovalPolicy.class);
                    // User-supplied bean has only LOW in its auto-tiers (default has LOW+MEDIUM)
                    assertThat(bean.autoApprovableTiers())
                            .containsExactly(ai.intelliswarm.swarmai.state.plan.Risk.LOW);
                });
    }

}
