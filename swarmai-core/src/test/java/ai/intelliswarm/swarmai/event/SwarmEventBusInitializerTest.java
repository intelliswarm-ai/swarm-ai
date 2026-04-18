package ai.intelliswarm.swarmai.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SwarmEventBusInitializer")
class SwarmEventBusInitializerTest {

    @BeforeEach
    @AfterEach
    void resetBus() {
        SwarmEventBus.setPublisher(null);
    }

    @Test
    @DisplayName("registers the Spring ApplicationEventPublisher with SwarmEventBus")
    void registersPublisher() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SwarmEventBusInitializer initializer = new SwarmEventBusInitializer();

        assertFalse(SwarmEventBus.isActive(), "bus must start inactive");

        initializer.setApplicationEventPublisher(publisher);

        assertTrue(SwarmEventBus.isActive(), "bus must be active after initializer runs");
    }

    @Test
    @DisplayName("subsequent publishes go through the registered publisher")
    void publishesViaRegisteredPublisher() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        new SwarmEventBusInitializer().setApplicationEventPublisher(publisher);

        SwarmEventBus.publish(this, SwarmEvent.Type.AGENT_MESSAGE, "hi", "sw-1");

        ArgumentCaptor<SwarmEvent> captor = ArgumentCaptor.forClass(SwarmEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(SwarmEvent.Type.AGENT_MESSAGE, captor.getValue().getType());
    }

    @Test
    @DisplayName("re-registering the initializer with a new publisher replaces the previous one")
    void replacesPreviousPublisher() {
        ApplicationEventPublisher first = mock(ApplicationEventPublisher.class);
        ApplicationEventPublisher second = mock(ApplicationEventPublisher.class);

        SwarmEventBusInitializer initializer = new SwarmEventBusInitializer();
        initializer.setApplicationEventPublisher(first);
        initializer.setApplicationEventPublisher(second);

        SwarmEventBus.publish(this, SwarmEvent.Type.SWARM_STARTED, "msg", "sw-1");

        verify(second).publishEvent(any(SwarmEvent.class));
        verifyNoInteractions(first);
    }
}
