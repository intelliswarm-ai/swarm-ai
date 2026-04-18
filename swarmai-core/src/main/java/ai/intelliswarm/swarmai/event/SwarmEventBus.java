package ai.intelliswarm.swarmai.event;

import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

/**
 * Static bridge to the Spring {@link ApplicationEventPublisher} for classes
 * (e.g. {@link ai.intelliswarm.swarmai.agent.Agent}) that are constructed
 * programmatically rather than as Spring beans.
 *
 * <p>Null-safe: when no publisher is registered (unit tests, non-Spring usage),
 * {@link #publish(Object, SwarmEvent.Type, String, String)} is a no-op.</p>
 *
 * <p>The bridge is populated by {@link SwarmEventBusInitializer} at application
 * startup. Applications bypassing that initializer can call
 * {@link #setPublisher(ApplicationEventPublisher)} directly.</p>
 */
public final class SwarmEventBus {

    private static volatile ApplicationEventPublisher publisher;

    private SwarmEventBus() {}

    public static void setPublisher(ApplicationEventPublisher p) {
        publisher = p;
    }

    public static boolean isActive() {
        return publisher != null;
    }

    public static void publish(Object source, SwarmEvent.Type type, String message, String swarmId) {
        ApplicationEventPublisher p = publisher;
        if (p != null) {
            p.publishEvent(new SwarmEvent(source, type, message, swarmId));
        }
    }

    public static void publish(Object source, SwarmEvent.Type type, String message,
                               String swarmId, Map<String, Object> metadata) {
        ApplicationEventPublisher p = publisher;
        if (p != null) {
            p.publishEvent(new SwarmEvent(source, type, message, swarmId,
                    metadata != null ? metadata : Map.of()));
        }
    }
}
