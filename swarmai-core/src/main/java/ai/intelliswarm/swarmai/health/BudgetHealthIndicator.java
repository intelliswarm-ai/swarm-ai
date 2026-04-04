package ai.intelliswarm.swarmai.health;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health indicator for the Budget subsystem.
 * Reports budget tracker status and implementation type.
 */
public class BudgetHealthIndicator implements HealthIndicator {

    private final BudgetTracker budgetTracker;

    public BudgetHealthIndicator(BudgetTracker budgetTracker) {
        this.budgetTracker = budgetTracker;
    }

    @Override
    public Health health() {
        try {
            return Health.up()
                    .withDetail("provider", budgetTracker.getClass().getSimpleName())
                    .withDetail("status", "active")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("provider", budgetTracker.getClass().getSimpleName())
                    .withException(e)
                    .build();
        }
    }
}
