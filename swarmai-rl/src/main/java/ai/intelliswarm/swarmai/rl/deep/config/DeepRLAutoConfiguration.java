package ai.intelliswarm.swarmai.rl.deep.config;

import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.RewardTracker;
import ai.intelliswarm.swarmai.rl.deep.DeepRLPolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Spring auto-configuration for Deep RL policy.
 * Takes priority over the lightweight bandit RL when both are on the classpath.
 *
 * <p>Activation priority:
 * <ol>
 *   <li>{@code swarmai.deep-rl.enabled=true} → DeepRLPolicy (this config, Order 1)</li>
 *   <li>{@code swarmai.rl.enabled=true} → LearningPolicy (core RL config, Order 2)</li>
 *   <li>Neither → HeuristicPolicy (default)</li>
 * </ol>
 */
@AutoConfiguration
@Order(1)
@ConditionalOnClass(DeepRLPolicy.class)
@ConditionalOnProperty(prefix = "swarmai.deep-rl", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DeepRLProperties.class)
public class DeepRLAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PolicyEngine.class)
    public PolicyEngine deepRLPolicyEngine(DeepRLProperties props) {
        DeepRLPolicy.DeepRLConfig config = new DeepRLPolicy.DeepRLConfig(
                props.getLearningRate(),
                0.99f, // gamma (discount factor)
                props.getEpsilonStart(),
                props.getEpsilonEnd(),
                props.getEpsilonDecaySteps(),
                props.getTrainInterval(),
                props.getTargetUpdateInterval(),
                props.getHiddenSize(),
                props.getBufferCapacity(),
                props.getColdStartDecisions()
        );
        return new DeepRLPolicy(config);
    }

    @Bean
    @ConditionalOnMissingBean(RewardTracker.class)
    public RewardTracker deepRLRewardTracker(PolicyEngine policyEngine) {
        return new RewardTracker(policyEngine);
    }
}
