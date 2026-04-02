package ai.intelliswarm.swarmai.rl.config;

import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.rl.LearningPolicy;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.RewardTracker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring auto-configuration for the RL policy engine.
 *
 * <p>When {@code swarmai.rl.enabled=true}, creates a {@link LearningPolicy} bean
 * that learns from workflow execution experience. When disabled (default),
 * creates a {@link HeuristicPolicy} that reproduces the existing hardcoded logic.
 */
@Configuration
@EnableConfigurationProperties(RLProperties.class)
public class RLAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.rl", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(PolicyEngine.class)
    public PolicyEngine learningPolicyEngine(RLProperties properties) {
        return new LearningPolicy(
                properties.getColdStartDecisions(),
                properties.getLinucbAlpha(),
                properties.getExperienceBufferCapacity()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.rl", name = "enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(PolicyEngine.class)
    public PolicyEngine heuristicPolicyEngine() {
        return new HeuristicPolicy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.rl", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RewardTracker.class)
    public RewardTracker rewardTracker(PolicyEngine policyEngine) {
        return new RewardTracker(policyEngine);
    }
}
