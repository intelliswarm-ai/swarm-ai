package ai.intelliswarm.swarmai.governance.config;

import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.InMemoryApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.WorkflowGovernanceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the SwarmAI Workflow Governance Engine.
 *
 * This configuration is only activated when {@code swarmai.governance.enabled=true}.
 * It provides:
 * - An in-memory {@link ApprovalGateHandler} for managing approval requests
 * - A {@link WorkflowGovernanceEngine} for orchestrating gate checks
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.governance", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GovernanceProperties.class)
public class GovernanceAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceAutoConfiguration.class);

    /**
     * Creates the default in-memory approval gate handler.
     * Can be overridden by providing a custom {@link ApprovalGateHandler} bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalGateHandler approvalGateHandler(ApplicationEventPublisher eventPublisher) {
        logger.info("Initializing InMemoryApprovalGateHandler for workflow governance");
        return new InMemoryApprovalGateHandler(eventPublisher);
    }

    /**
     * Creates the workflow governance engine.
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowGovernanceEngine workflowGovernanceEngine(
            ApprovalGateHandler gateHandler,
            ApplicationEventPublisher eventPublisher) {
        logger.info("Initializing WorkflowGovernanceEngine");
        return new WorkflowGovernanceEngine(gateHandler, eventPublisher);
    }
}
