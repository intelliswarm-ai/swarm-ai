package ai.intelliswarm.swarmai.health;

import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for SwarmAI health indicators.
 * Each indicator is only created when its backing bean exists.
 */
@Configuration
@ConditionalOnClass(HealthIndicator.class)
public class HealthAutoConfiguration {

    @Bean
    @ConditionalOnBean(Memory.class)
    public MemoryHealthIndicator memoryHealthIndicator(Memory memory) {
        return new MemoryHealthIndicator(memory);
    }

    @Bean
    @ConditionalOnBean(BudgetTracker.class)
    public BudgetHealthIndicator budgetHealthIndicator(BudgetTracker budgetTracker) {
        return new BudgetHealthIndicator(budgetTracker);
    }

    @Bean
    @ConditionalOnBean(EventStore.class)
    public EventStoreHealthIndicator eventStoreHealthIndicator(EventStore eventStore) {
        return new EventStoreHealthIndicator(eventStore);
    }
}
