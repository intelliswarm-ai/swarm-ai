package ai.intelliswarm.swarmai.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Component;

/**
 * Bootstraps {@link SwarmEventBus} with the application's event publisher so
 * non-bean classes (Agent, tool invocations) can publish events without being
 * reconstructed with dependency injection.
 */
@Component
public class SwarmEventBusInitializer implements ApplicationEventPublisherAware {

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        SwarmEventBus.setPublisher(publisher);
    }
}
