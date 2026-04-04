package ai.intelliswarm.swarmai.health;

import ai.intelliswarm.swarmai.memory.Memory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health indicator for the Memory subsystem.
 * Reports the memory provider type and current entry count.
 */
public class MemoryHealthIndicator implements HealthIndicator {

    private final Memory memory;

    public MemoryHealthIndicator(Memory memory) {
        this.memory = memory;
    }

    @Override
    public Health health() {
        try {
            int size = memory.size();
            return Health.up()
                    .withDetail("provider", memory.getClass().getSimpleName())
                    .withDetail("entries", size)
                    .withDetail("empty", memory.isEmpty())
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("provider", memory.getClass().getSimpleName())
                    .withException(e)
                    .build();
        }
    }
}
