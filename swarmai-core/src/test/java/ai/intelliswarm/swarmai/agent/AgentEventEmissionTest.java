package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.event.SwarmEventBus;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@link Agent} publishes the expected lifecycle, LLM, and tool
 * events through the static {@link SwarmEventBus}. Events are captured via the
 * {@code mockEventPublisher} provided by {@link BaseSwarmTest}.
 */
@DisplayName("Agent event emission")
class AgentEventEmissionTest extends BaseSwarmTest {

    @BeforeEach
    void wireBus() {
        // Route Agent's events through the base test's capturing publisher.
        SwarmEventBus.setPublisher(mockEventPublisher);
    }

    @AfterEach
    void unwireBus() {
        SwarmEventBus.setPublisher(null);
    }

    @Nested
    @DisplayName("single-shot execution")
    class SingleShot {

        @Test
        @DisplayName("emits AGENT_STARTED, LLM_REQUEST, AGENT_COMPLETED in order")
        void emitsLifecycleAndLlmEvents() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            agent.executeTask(task, Collections.emptyList());

            List<SwarmEvent.Type> types = typesOf(capturedEvents);
            assertEquals(
                    List.of(
                            SwarmEvent.Type.AGENT_STARTED,
                            SwarmEvent.Type.LLM_REQUEST,
                            SwarmEvent.Type.AGENT_COMPLETED
                    ),
                    types,
                    "single-shot should emit started → llm_request → completed"
            );
        }

        @Test
        @DisplayName("LLM_REQUEST metadata carries model, tokens, and content")
        void llmRequestMetadata() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            agent.executeTask(task, Collections.emptyList());

            SwarmEvent llm = firstOfType(SwarmEvent.Type.LLM_REQUEST);
            assertNotNull(llm.getMetadata().get("durationMs"), "durationMs recorded");
            assertTrue(llm.getMetadata().containsKey("promptTokens"));
            assertTrue(llm.getMetadata().containsKey("completionTokens"));
            assertTrue(llm.getMetadata().containsKey("content"));
            assertTrue(llm.getMetadata().containsKey("agent"));
        }

        @Test
        @DisplayName("AGENT_COMPLETED carries the final output and duration")
        void agentCompletedMetadata() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            agent.executeTask(task, Collections.emptyList());

            SwarmEvent completed = firstOfType(SwarmEvent.Type.AGENT_COMPLETED);
            assertNotNull(completed.getMetadata().get("output"));
            assertNotNull(completed.getMetadata().get("durationMs"));
            assertNotNull(completed.getMetadata().get("totalTokens"));
        }
    }

    @Nested
    @DisplayName("reactive multi-turn execution")
    class Reactive {

        @Test
        @DisplayName("emits AGENT_STARTED, LLM_REQUEST, AGENT_MESSAGE, AGENT_COMPLETED per turn")
        void reactiveEmitsAgentMessagePerTurn() {
            Agent agent = Agent.builder()
                    .role("Reactive Tester")
                    .goal("test")
                    .backstory("test")
                    .chatClient(mockChatClient)
                    .maxTurns(3)
                    .build();

            Task task = createTask(agent);

            agent.executeTask(task, Collections.emptyList());

            Set<SwarmEvent.Type> seen = capturedEvents.stream()
                    .map(SwarmEvent::getType)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(SwarmEvent.Type.class)));

            // Reactive path must emit at least these four kinds.
            assertTrue(seen.contains(SwarmEvent.Type.AGENT_STARTED));
            assertTrue(seen.contains(SwarmEvent.Type.LLM_REQUEST));
            assertTrue(seen.contains(SwarmEvent.Type.AGENT_MESSAGE));
            assertTrue(seen.contains(SwarmEvent.Type.AGENT_COMPLETED));

            // The first and last events are the bookends.
            assertEquals(SwarmEvent.Type.AGENT_STARTED, capturedEvents.get(0).getType());
            assertEquals(SwarmEvent.Type.AGENT_COMPLETED,
                    capturedEvents.get(capturedEvents.size() - 1).getType());
        }
    }

    @Nested
    @DisplayName("failure path")
    class Failure {

        @Test
        @DisplayName("emits AGENT_FAILED when the LLM throws")
        void emitsAgentFailedOnException() {
            ChatClient failing = MockChatClientFactory.withError(
                    new RuntimeException("boom"));
            Agent agent = Agent.builder()
                    .role("Failing Agent")
                    .goal("test")
                    .backstory("test")
                    .chatClient(failing)
                    .build();
            Task task = createTask(agent);

            assertThrows(RuntimeException.class,
                    () -> agent.executeTask(task, Collections.emptyList()));

            Set<SwarmEvent.Type> seen = capturedEvents.stream()
                    .map(SwarmEvent::getType)
                    .collect(Collectors.toSet());

            assertTrue(seen.contains(SwarmEvent.Type.AGENT_STARTED));
            assertTrue(seen.contains(SwarmEvent.Type.AGENT_FAILED),
                    "AGENT_FAILED must be published on exception");
            assertFalse(seen.contains(SwarmEvent.Type.AGENT_COMPLETED),
                    "AGENT_COMPLETED must NOT be emitted on failure");
        }

        @Test
        @DisplayName("AGENT_FAILED metadata carries a non-blank error message")
        void agentFailedIncludesError() {
            // NOTE: the Agent's retry layer wraps the underlying LLM exception with
            // "LLM call failed after N attempts", so we don't assert on a specific
            // root-cause string. We only require that the error field is present and
            // non-blank — whatever the agent actually surfaced.
            ChatClient failing = MockChatClientFactory.withError(
                    new IllegalStateException("token exceeded"));
            Agent agent = Agent.builder()
                    .role("Failing Agent")
                    .goal("test")
                    .backstory("test")
                    .chatClient(failing)
                    .build();
            Task task = createTask(agent);

            assertThrows(RuntimeException.class,
                    () -> agent.executeTask(task, Collections.emptyList()));

            SwarmEvent failed = firstOfType(SwarmEvent.Type.AGENT_FAILED);
            Object error = failed.getMetadata().get("error");
            assertNotNull(error, "error field must be present");
            assertTrue(error instanceof String && !((String) error).isBlank(),
                    "error field must be a non-blank string, was: " + error);
        }
    }

    @Nested
    @DisplayName("listener failure does not abort execution")
    class ListenerFailureIsolation {

        @Test
        @DisplayName("agent task completes even when the AGENT_STARTED listener throws")
        void agentCompletesDespiteListenerFailure() {
            ApplicationEventPublisher throwing = mock(ApplicationEventPublisher.class);
            doAnswer(invocation -> {
                SwarmEvent event = invocation.getArgument(0);
                if (event.getType() == SwarmEvent.Type.AGENT_STARTED) {
                    throw new RuntimeException("observability listener failure");
                }
                capturedEvents.add(event);
                return null;
            }).when(throwing).publishEvent(any(SwarmEvent.class));
            SwarmEventBus.setPublisher(throwing);

            Agent agent = createAgent();
            Task task = createTask(agent);

            // Must not propagate — telemetry failure can't kill an agent run before the LLM call.
            TaskOutput output = agent.executeTask(task, Collections.emptyList());
            assertNotNull(output);
            assertTrue(output.isSuccessful());

            // And the rest of the lifecycle still made it to the event stream.
            Set<SwarmEvent.Type> seen = capturedEvents.stream()
                    .map(SwarmEvent::getType)
                    .collect(Collectors.toSet());
            assertTrue(seen.contains(SwarmEvent.Type.LLM_REQUEST));
            assertTrue(seen.contains(SwarmEvent.Type.AGENT_COMPLETED));
        }
    }

    @Nested
    @DisplayName("bus is not active")
    class BusInactive {

        @Test
        @DisplayName("Agent does not crash and publishes nothing when the bus has no publisher")
        void noopWhenBusInactive() {
            SwarmEventBus.setPublisher(null); // override @BeforeEach wiring
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertDoesNotThrow(() -> agent.executeTask(task, Collections.emptyList()));
            assertTrue(capturedEvents.isEmpty(),
                    "no events should reach the captured list when bus is inactive");
        }
    }

    // ---------- helpers ----------

    private List<SwarmEvent.Type> typesOf(List<SwarmEvent> events) {
        return events.stream().map(SwarmEvent::getType).collect(Collectors.toList());
    }

    private SwarmEvent firstOfType(SwarmEvent.Type type) {
        return capturedEvents.stream()
                .filter(e -> e.getType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event of type " + type + " captured: " + typesOf(capturedEvents)));
    }
}
