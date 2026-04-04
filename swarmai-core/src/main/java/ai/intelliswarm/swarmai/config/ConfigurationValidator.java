package ai.intelliswarm.swarmai.config;

import ai.intelliswarm.swarmai.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates SwarmAI configuration at startup.
 * Collects all validation errors and fails fast with an actionable error message.
 */
@Component
public class ConfigurationValidator {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationValidator.class);

    @Value("${swarmai.budget.default-max-tokens:#{null}}")
    private Long budgetMaxTokens;

    @Value("${swarmai.budget.default-max-cost-usd:#{null}}")
    private Double budgetMaxCostUsd;

    @Value("${swarmai.budget.warning-threshold-percent:#{null}}")
    private Double budgetWarningThreshold;

    @Value("${swarmai.budget.enabled:false}")
    private boolean budgetEnabled;

    @Value("${swarmai.observability.enabled:false}")
    private boolean observabilityEnabled;

    @Value("${swarmai.observability.max-events-in-memory:10000}")
    private int maxEventsInMemory;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        List<String> errors = new ArrayList<>();

        if (budgetEnabled) {
            if (budgetMaxTokens != null && budgetMaxTokens <= 0) {
                errors.add("swarmai.budget.default-max-tokens must be > 0 (got " + budgetMaxTokens + ")");
            }
            if (budgetMaxCostUsd != null && budgetMaxCostUsd <= 0) {
                errors.add("swarmai.budget.default-max-cost-usd must be > 0 (got " + budgetMaxCostUsd + ")");
            }
            if (budgetWarningThreshold != null && (budgetWarningThreshold < 0 || budgetWarningThreshold > 100)) {
                errors.add("swarmai.budget.warning-threshold-percent must be between 0 and 100 (got " + budgetWarningThreshold + ")");
            }
        }

        if (observabilityEnabled) {
            if (maxEventsInMemory <= 0) {
                errors.add("swarmai.observability.max-events-in-memory must be > 0 (got " + maxEventsInMemory + ")");
            }
        }

        if (!errors.isEmpty()) {
            String message = "SwarmAI configuration validation failed:\n  - " + String.join("\n  - ", errors);
            logger.error(message);
            throw new ConfigurationException(message);
        }

        logger.info("SwarmAI configuration validated successfully");
    }
}
