package ai.intelliswarm.swarmai.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("SwarmEventBus")
class SwarmEventBusTest {

    @BeforeEach
    @AfterEach
    void resetBus() {
        SwarmEventBus.setPublisher(null);
    }

    @Nested
    @DisplayName("isActive()")
    class IsActiveTests {

        @Test
        @DisplayName("returns false when no publisher registered")
        void returnsFalseWhenNoPublisher() {
            assertFalse(SwarmEventBus.isActive());
        }

        @Test
        @DisplayName("returns true after a publisher is registered")
        void returnsTrueAfterRegistration() {
            SwarmEventBus.setPublisher(mock(ApplicationEventPublisher.class));
            assertTrue(SwarmEventBus.isActive());
        }

        @Test
        @DisplayName("returns false again after being cleared")
        void returnsFalseAfterClear() {
            SwarmEventBus.setPublisher(mock(ApplicationEventPublisher.class));
            SwarmEventBus.setPublisher(null);
            assertFalse(SwarmEventBus.isActive());
        }
    }

    @Nested
    @DisplayName("publish() without metadata")
    class PublishWithoutMetadataTests {

        @Test
        @DisplayName("is a no-op when no publisher is registered")
        void isNoopWhenNoPublisher() {
            assertDoesNotThrow(() ->
                SwarmEventBus.publish(this, SwarmEvent.Type.SWARM_STARTED, "msg", "sw-1")
            );
        }

        @Test
        @DisplayName("forwards a SwarmEvent with the given fields to the publisher")
        void forwardsFieldsToPublisher() {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            SwarmEventBus.setPublisher(publisher);

            SwarmEventBus.publish(this, SwarmEvent.Type.AGENT_STARTED, "agent up", "sw-42");

            ArgumentCaptor<SwarmEvent> captor = ArgumentCaptor.forClass(SwarmEvent.class);
            verify(publisher).publishEvent(captor.capture());

            SwarmEvent event = captor.getValue();
            assertEquals(SwarmEvent.Type.AGENT_STARTED, event.getType());
            assertEquals("agent up", event.getMessage());
            assertEquals("sw-42", event.getSwarmId());
            assertSame(this, event.getSource());
        }
    }

    @Nested
    @DisplayName("publish() with metadata")
    class PublishWithMetadataTests {

        @Test
        @DisplayName("propagates metadata entries to the event")
        void propagatesMetadata() {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            SwarmEventBus.setPublisher(publisher);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("agent", "researcher");
            metadata.put("tokens", 123);

            SwarmEventBus.publish(this, SwarmEvent.Type.LLM_REQUEST,
                    "llm complete", "sw-7", metadata);

            ArgumentCaptor<SwarmEvent> captor = ArgumentCaptor.forClass(SwarmEvent.class);
            verify(publisher).publishEvent(captor.capture());

            SwarmEvent event = captor.getValue();
            assertEquals(SwarmEvent.Type.LLM_REQUEST, event.getType());
            assertEquals("researcher", event.getMetadata().get("agent"));
            assertEquals(123, event.getMetadata().get("tokens"));
        }

        @Test
        @DisplayName("tolerates null metadata and still publishes the event")
        void tolerateNullMetadata() {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            SwarmEventBus.setPublisher(publisher);

            assertDoesNotThrow(() ->
                SwarmEventBus.publish(this, SwarmEvent.Type.TOOL_STARTED,
                        "tool", "sw-1", null)
            );

            verify(publisher).publishEvent(any(SwarmEvent.class));
        }

        @Test
        @DisplayName("is a no-op when no publisher is registered")
        void isNoopWhenNoPublisher() {
            Map<String, Object> metadata = Map.of("tool", "calculator");
            assertDoesNotThrow(() ->
                SwarmEventBus.publish(this, SwarmEvent.Type.TOOL_STARTED,
                        "msg", "sw-1", metadata)
            );
        }
    }

    @Nested
    @DisplayName("listener failure isolation")
    class ListenerFailureTests {

        @Test
        @DisplayName("listener RuntimeException does not propagate — telemetry is non-fatal")
        void listenerExceptionSwallowed() {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
            doThrow(new RuntimeException("listener boom"))
                    .when(publisher).publishEvent(any());
            SwarmEventBus.setPublisher(publisher);

            assertDoesNotThrow(() ->
                SwarmEventBus.publish(this, SwarmEvent.Type.SWARM_STARTED, "msg", "sw-1"));
            assertDoesNotThrow(() ->
                SwarmEventBus.publish(this, SwarmEvent.Type.AGENT_STARTED, "msg", "sw-1",
                        Map.of("agent", "a")));

            // And the publisher was still called — we just caught what it threw.
            verify(publisher, times(2)).publishEvent(any(SwarmEvent.class));
        }
    }

    @Nested
    @DisplayName("thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("publisher writes are visible across threads")
        void publisherIsVolatile() throws InterruptedException {
            ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

            Thread t = new Thread(() -> SwarmEventBus.setPublisher(publisher));
            t.start();
            t.join();

            // Main thread must observe the publisher set by another thread
            // (volatile semantics give us this happens-before guarantee).
            assertTrue(SwarmEventBus.isActive());
            SwarmEventBus.publish(this, SwarmEvent.Type.SWARM_STARTED, "msg", "sw-x");
            verify(publisher).publishEvent(any(SwarmEvent.class));
        }
    }
}
