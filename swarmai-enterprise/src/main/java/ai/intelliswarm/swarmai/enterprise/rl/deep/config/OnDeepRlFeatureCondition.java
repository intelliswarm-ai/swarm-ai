package ai.intelliswarm.swarmai.enterprise.rl.deep.config;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers Deep RL beans only when the active enterprise license includes the deep-rl feature.
 */
public class OnDeepRlFeatureCondition implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(OnDeepRlFeatureCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (context.getBeanFactory() == null) {
            return false;
        }

        try {
            LicenseManager licenseManager = context.getBeanFactory().getBean(LicenseManager.class);
            boolean enabled = licenseManager.hasFeature("deep-rl");
            if (!enabled) {
                logger.info("Deep RL feature not included in license (edition={})", licenseManager.getEdition());
            }
            return enabled;
        } catch (Exception ex) {
            logger.debug("Deep RL feature check skipped because LicenseManager is unavailable", ex);
            return false;
        }
    }
}
