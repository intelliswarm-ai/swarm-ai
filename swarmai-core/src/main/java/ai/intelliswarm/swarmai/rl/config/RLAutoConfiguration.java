package ai.intelliswarm.swarmai.rl.config;

import ai.intelliswarm.swarmai.rl.HeuristicPolicy;
import ai.intelliswarm.swarmai.rl.LearningPolicy;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.RewardTracker;
import ai.intelliswarm.swarmai.rl.SkillGenerationContext;
import ai.intelliswarm.swarmai.rl.bandit.NeuralLinUCBBandit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger logger = LoggerFactory.getLogger(RLAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "swarmai.rl", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(PolicyEngine.class)
    public PolicyEngine learningPolicyEngine(RLProperties properties) {
        NeuralLinUCBBandit neuralBandit = null;

        if (properties.isNeuralLinucbEnabled()) {
            neuralBandit = new NeuralLinUCBBandit(
                    4, // SKILL_GEN_ACTIONS
                    SkillGenerationContext.featureDimension(),
                    properties.getNeuralLinucbHidden(),
                    properties.getNeuralLinucbFeatures(),
                    properties.getLinucbAlpha(),
                    properties.getNeuralLinucbLearningRate(),
                    properties.getNeuralLinucbTrainInterval(),
                    properties.getExperienceBufferCapacity()
            );
            logger.info("[RL] NeuralLinUCB enabled (hidden={}, features={}, lr={})",
                    properties.getNeuralLinucbHidden(),
                    properties.getNeuralLinucbFeatures(),
                    properties.getNeuralLinucbLearningRate());
        }

        return new LearningPolicy(
                properties.getColdStartDecisions(),
                properties.getLinucbAlpha(),
                properties.getExperienceBufferCapacity(),
                neuralBandit
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
