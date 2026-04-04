package ai.intelliswarm.swarmai.enterprise.rl.deep.config;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import ai.intelliswarm.swarmai.enterprise.rl.deep.DeepRLPolicy;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.RewardTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

/**
 * Enterprise auto-configuration for Deep RL policy.
 * Requires a valid enterprise license with the "deep-rl" feature.
 */
@AutoConfiguration
@Order(1)
@ConditionalOnBean(LicenseManager.class)
@ConditionalOnClass(DeepRLPolicy.class)
@EnableConfigurationProperties(DeepRLProperties.class)
public class DeepRLAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(DeepRLAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(PolicyEngine.class)
    public PolicyEngine deepRLPolicyEngine(DeepRLProperties props, LicenseManager licenseManager) {
        if (!licenseManager.hasFeature("deep-rl")) {
            logger.info("Deep RL feature not included in license (edition={})", licenseManager.getEdition());
            return null;
        }
        logger.info("Initializing enterprise DeepRLPolicy (DQN-based neural network)");
        DeepRLPolicy.DeepRLConfig config = new DeepRLPolicy.DeepRLConfig(
                props.getLearningRate(),
                0.99f,
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
    @ConditionalOnBean(PolicyEngine.class)
    public RewardTracker deepRLRewardTracker(PolicyEngine policyEngine) {
        return new RewardTracker(policyEngine);
    }
}
