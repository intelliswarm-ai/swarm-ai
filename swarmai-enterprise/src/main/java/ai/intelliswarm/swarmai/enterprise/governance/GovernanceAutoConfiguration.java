package ai.intelliswarm.swarmai.enterprise.governance;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.WorkflowGovernanceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/**
 * Enterprise auto-configuration for the Workflow Governance Engine.
 * Activates only when a valid enterprise license is present.
 */
@AutoConfiguration
@ConditionalOnBean(LicenseManager.class)
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ApprovalGateHandler approvalGateHandler(ApplicationEventPublisher eventPublisher,
                                                    LicenseManager licenseManager) {
        if (!licenseManager.hasFeature("governance")) {
            logger.info("Governance feature not included in license (edition={})", licenseManager.getEdition());
            return null;
        }
        logger.info("Initializing enterprise InMemoryApprovalGateHandler for workflow governance");
        return new InMemoryApprovalGateHandler(eventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ApprovalGateHandler.class)
    public WorkflowGovernanceEngine workflowGovernanceEngine(
            ApprovalGateHandler gateHandler,
            ApplicationEventPublisher eventPublisher) {
        logger.info("Initializing enterprise WorkflowGovernanceEngine");
        return new WorkflowGovernanceEngine(gateHandler, eventPublisher);
    }
}
