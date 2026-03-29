package ai.intelliswarm.swarmai.budget.config;

import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for the SwarmAI Budget Tracker.
 *
 * Activated when {@code swarmai.budget.enabled=true}. Creates an
 * {@link InMemoryBudgetTracker} bean with a default policy derived
 * from {@link BudgetProperties}.
 */
@Configuration
@EnableConfigurationProperties(BudgetProperties.class)
@ConditionalOnProperty(prefix = "swarmai.budget", name = "enabled", havingValue = "true")
public class BudgetAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(BudgetAutoConfiguration.class);

    /**
     * Creates the default {@link BudgetTracker} bean backed by an in-memory implementation.
     * The default policy is built from application properties.
     */
    @Bean
    @ConditionalOnMissingBean
    public BudgetTracker budgetTracker(BudgetProperties properties) {
        BudgetPolicy.BudgetAction action;
        try {
            action = BudgetPolicy.BudgetAction.valueOf(properties.getDefaultAction().toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid budget action '{}', falling back to WARN", properties.getDefaultAction());
            action = BudgetPolicy.BudgetAction.WARN;
        }

        BudgetPolicy defaultPolicy = BudgetPolicy.builder()
                .maxTotalTokens(properties.getDefaultMaxTokens())
                .maxCostUsd(properties.getDefaultMaxCostUsd())
                .onExceeded(action)
                .warningThresholdPercent(properties.getWarningThresholdPercent())
                .build();

        logger.info("Budget tracking enabled: maxTokens={}, maxCost=${}, action={}, warningAt={}%",
                defaultPolicy.maxTotalTokens(),
                defaultPolicy.maxCostUsd(),
                defaultPolicy.onExceeded(),
                defaultPolicy.warningThresholdPercent());

        return new InMemoryBudgetTracker(defaultPolicy);
    }
}
