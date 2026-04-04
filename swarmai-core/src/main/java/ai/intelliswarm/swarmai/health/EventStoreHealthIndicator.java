package ai.intelliswarm.swarmai.health;

import ai.intelliswarm.swarmai.observability.replay.EventStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health indicator for the EventStore (observability replay).
 * Reports event count and correlation tracking status.
 */
public class EventStoreHealthIndicator implements HealthIndicator {

    private final EventStore eventStore;

    public EventStoreHealthIndicator(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public Health health() {
        try {
            int totalEvents = eventStore.getTotalEventCount();
            int correlations = eventStore.getAllCorrelationIds().size();
            return Health.up()
                    .withDetail("provider", eventStore.getClass().getSimpleName())
                    .withDetail("totalEvents", totalEvents)
                    .withDetail("activeCorrelations", correlations)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("provider", eventStore.getClass().getSimpleName())
                    .withException(e)
                    .build();
        }
    }
}
