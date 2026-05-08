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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-configuration for the SwarmAI plan-loop. Activated when
 * {@code swarmai.plan-loop.enabled=true}. Exposes the singletons needed to
 * construct a per-session {@code PlanAwareMutationGuard} and
 * {@code ReplannerLoop}:
 *
 * <ul>
 *   <li>{@link PlanApprovalPolicy} — composite of the configured policies
 *       ({@link RiskTieredApprovalPolicy}, {@link DriftBudgetApprovalPolicy},
 *       optionally {@link BanditApprovalPolicy} via {@link BanditPromotionGate}).</li>
 *   <li>{@link BanditApprovalPolicy} — only when {@code bandit.enabled=true}.</li>
 *   <li>{@link BanditPromotionGate} — wraps the bandit when configured to participate
 *       (compose ≠ OBSERVER).</li>
 *   <li>{@link BanditStateStore} — {@link FileBanditStateStore} when a path is set,
 *       otherwise {@link InMemoryBanditStateStore}. Auto-restores on startup.</li>
 *   <li>{@link LlmReplanner} — only when {@code replanner.enabled=true} and a
 *       {@link ChatClient.Builder} is on the context.</li>
 *   <li>{@link ReplanTriggerPolicy} — built from the configured trigger type.</li>
 *   <li>{@link PlanContextRenderer} — singleton; stateless and reusable.</li>
 * </ul>
 *
 * <p>Per-session components ({@code PlanChannelAccessor},
 * {@code PlanAwareMutationGuard}, {@code BanditApprovalTrainer},
 * {@code ReplannerLoop}) are <em>not</em> auto-wired — they need a session
 * scope. Application code constructs them with the singletons above:
 *
 * <pre>
 * &#64;Service
 * class WorkflowSession {
 *     &#64;Autowired private PlanApprovalPolicy policy;
 *     &#64;Autowired private LlmReplanner replanner;
 *
 *     void run() {
 *         var channel = new InMemoryPlanChannelAccessor();
 *         var guard = new PlanAwareMutationGuard(humanDelegate, channel, policy);
 *         var loop = ReplannerLoop.start(channel, replanner,
 *             ReplanTriggerPolicy.onRejection(),
 *             ChannelDerivedReplanContextSupplier.of(channel, "the goal"));
 *         // ... drive tools through the guard ...
 *     }
 * }
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(PlanLoopProperties.class)
@ConditionalOnProperty(prefix = "swarmai.plan-loop", name = "enabled", havingValue = "true")
public class PlanLoopAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlanLoopAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public PlanContextRenderer planContextRenderer() {
        return new PlanContextRenderer();
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.tier", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public RiskTieredApprovalPolicy riskTieredApprovalPolicy() {
        return new RiskTieredApprovalPolicy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.drift-budget", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public DriftBudgetApprovalPolicy driftBudgetApprovalPolicy(PlanLoopProperties props) {
        return new DriftBudgetApprovalPolicy(props.getDriftBudget().getBudget());
    }

    // ====== Bandit + persistence ======

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.bandit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public BanditApprovalPolicy banditApprovalPolicy(PlanLoopProperties props,
                                                      BanditStateStore stateStore) {
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY,
                props.getBandit().getWarmup());
        try {
            bandit.restore(stateStore.load());
            log.info("plan-loop: bandit restored from {} buckets",
                    stateStore.load().bucketCount());
        } catch (IOException e) {
            log.warn("plan-loop: failed to load bandit state — starting fresh: {}", e.getMessage());
        }
        return bandit;
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.bandit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public BanditStateStore banditStateStore(PlanLoopProperties props) {
        String file = props.getBandit().getPersistence().getFile();
        if (file == null || file.isBlank()) {
            log.info("plan-loop: bandit persistence disabled — using InMemoryBanditStateStore");
            return new InMemoryBanditStateStore();
        }
        Path path = Path.of(file);
        log.info("plan-loop: bandit persistence at {}", path);
        return new FileBanditStateStore(path);
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.bandit", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public BanditPromotionGate banditPromotionGate(PlanLoopProperties props,
                                                    BanditApprovalPolicy bandit) {
        var gateProps = props.getBandit().getGate();
        return new BanditPromotionGate(bandit,
                PlanApprovalPolicy.alwaysHuman(),
                gateProps.getMinObservations(),
                gateProps.getMaxCiHalfWidth());
    }

    // ====== Composite policy ======

    @Bean
    @ConditionalOnMissingBean(name = "planApprovalPolicy")
    public PlanApprovalPolicy planApprovalPolicy(PlanLoopProperties props,
                                                  ObjectProvider<RiskTieredApprovalPolicy> tier,
                                                  ObjectProvider<DriftBudgetApprovalPolicy> drift,
                                                  ObjectProvider<BanditPromotionGate> banditGate) {
        List<PlanApprovalPolicy> staticChain = new ArrayList<>();
        tier.ifAvailable(staticChain::add);
        drift.ifAvailable(staticChain::add);

        PlanLoopProperties.Bandit.Compose composeMode = props.getBandit().getCompose();
        BanditPromotionGate gate = banditGate.getIfAvailable();

        if (gate == null || composeMode == PlanLoopProperties.Bandit.Compose.OBSERVER) {
            PlanApprovalPolicy result = composeAll(staticChain);
            log.info("plan-loop: policy = static chain ({} components, bandit observer-only)",
                    staticChain.size());
            return result;
        }

        return switch (composeMode) {
            case VETO -> {
                List<PlanApprovalPolicy> withGate = new ArrayList<>(staticChain);
                withGate.add(gate);
                log.info("plan-loop: policy = all(static..., banditGate) — VETO mode");
                yield composeAll(withGate);
            }
            case BYPASS -> {
                PlanApprovalPolicy staticAll = composeAll(staticChain);
                log.info("plan-loop: policy = any(all(static), banditGate) — BYPASS mode");
                yield PlanApprovalPolicy.any(staticAll, gate);
            }
            default -> composeAll(staticChain);
        };
    }

    // ====== Replanner ======

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.replanner", name = "enabled", havingValue = "true")
    @ConditionalOnClass(ChatClient.class)
    @ConditionalOnMissingBean
    public LlmReplanner llmReplanner(PlanLoopProperties props,
                                      ObjectProvider<ChatClient.Builder> chatClientBuilder,
                                      PlanContextRenderer renderer) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException(
                    "plan-loop: replanner.enabled=true but no ChatClient.Builder bean is available. " +
                    "Add a Spring AI starter (e.g. spring-ai-starter-model-openai) to the classpath.");
        }
        String prompt = props.getReplanner().getSystemPromptOverride();
        if (prompt == null || prompt.isBlank()) {
            prompt = LlmReplanner.DEFAULT_SYSTEM_PROMPT;
        }
        log.info("plan-loop: replanner enabled, trigger={}",
                props.getReplanner().getTrigger());
        return new LlmReplanner(builder.build(), renderer, prompt);
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.plan-loop.replanner", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ReplanTriggerPolicy replanTriggerPolicy(PlanLoopProperties props) {
        return switch (props.getReplanner().getTrigger()) {
            case REJECTION -> ReplanTriggerPolicy.onRejection();
            case TERMINAL -> ReplanTriggerPolicy.onTerminal();
            case EVERY_N -> ReplanTriggerPolicy.everyNSteps(props.getReplanner().getEveryN());
            case ALWAYS -> ReplanTriggerPolicy.always();
            case NEVER -> ReplanTriggerPolicy.never();
        };
    }

    // ====== Helpers ======

    private static PlanApprovalPolicy composeAll(List<PlanApprovalPolicy> chain) {
        if (chain.isEmpty()) return PlanApprovalPolicy.alwaysHuman();
        if (chain.size() == 1) return chain.get(0);
        return PlanApprovalPolicy.all(chain.toArray(new PlanApprovalPolicy[0]));
    }
}
