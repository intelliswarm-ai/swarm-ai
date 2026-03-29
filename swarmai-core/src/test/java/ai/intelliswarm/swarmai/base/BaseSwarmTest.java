package ai.intelliswarm.swarmai.base;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Base class for SwarmAI tests providing common utilities,
 * mock factories, and assertion helpers.
 */
public abstract class BaseSwarmTest {

    protected ChatClient mockChatClient;
    protected ApplicationEventPublisher mockEventPublisher;
    protected List<SwarmEvent> capturedEvents;
    protected ArgumentCaptor<SwarmEvent> eventCaptor;

    @BeforeEach
    protected void setUpBase() {
        mockChatClient = MockChatClientFactory.withResponse(TestFixtures.DEFAULT_RESPONSE);
        mockEventPublisher = mock(ApplicationEventPublisher.class);
        capturedEvents = new ArrayList<>();
        eventCaptor = ArgumentCaptor.forClass(SwarmEvent.class);

        // Configure event capture
        doAnswer(invocation -> {
            Object event = invocation.getArgument(0);
            if (event instanceof SwarmEvent) {
                capturedEvents.add((SwarmEvent) event);
            }
            return null;
        }).when(mockEventPublisher).publishEvent(any());
    }

    // ============================================
    // ChatClient Helpers
    // ============================================

    /**
     * Creates a ChatClient that returns the specified response.
     */
    protected ChatClient chatClientWithResponse(String response) {
        return MockChatClientFactory.withResponse(response);
    }

    /**
     * Creates a ChatClient that returns responses sequentially.
     */
    protected ChatClient chatClientWithResponses(String... responses) {
        return MockChatClientFactory.withResponses(responses);
    }

    /**
     * Creates a ChatClient that throws an error.
     */
    protected ChatClient chatClientWithError(String errorMessage) {
        return MockChatClientFactory.withError(errorMessage);
    }

    /**
     * Creates a ChatClient that captures prompts.
     */
    protected MockChatClientFactory.CapturingChatClient capturingChatClient(String response) {
        return MockChatClientFactory.capturing(response);
    }

    // ============================================
    // Agent Helpers
    // ============================================

    /**
     * Creates a test agent with the default mock ChatClient.
     */
    protected Agent createAgent() {
        return TestFixtures.createTestAgent(mockChatClient);
    }

    /**
     * Creates a test agent with a specific role.
     */
    protected Agent createAgent(String role) {
        return TestFixtures.createTestAgent(role, mockChatClient);
    }

    /**
     * Creates a test agent with a custom ChatClient.
     */
    protected Agent createAgent(ChatClient chatClient) {
        return TestFixtures.createTestAgent(chatClient);
    }

    /**
     * Creates multiple test agents.
     */
    protected List<Agent> createAgents(int count) {
        return TestFixtures.createTestAgents(count, mockChatClient);
    }

    // ============================================
    // Task Helpers
    // ============================================

    /**
     * Creates a test task with the default agent.
     */
    protected Task createTask() {
        return TestFixtures.createTestTask(createAgent());
    }

    /**
     * Creates a test task with a specific agent.
     */
    protected Task createTask(Agent agent) {
        return TestFixtures.createTestTask(agent);
    }

    /**
     * Creates a test task with a specific description.
     */
    protected Task createTask(String description, Agent agent) {
        return TestFixtures.createTestTask(description, agent);
    }

    /**
     * Creates multiple test tasks.
     */
    protected List<Task> createTasks(int count, Agent agent) {
        return TestFixtures.createTestTasks(count, agent);
    }

    /**
     * Creates a chain of dependent tasks.
     */
    protected List<Task> createDependentTasks(int count, Agent agent) {
        return TestFixtures.createDependentTaskChain(count, agent);
    }

    // ============================================
    // Swarm Helpers
    // ============================================

    /**
     * Creates a simple swarm with one agent and one task.
     */
    protected Swarm createSimpleSwarm() {
        return TestFixtures.createSimpleSwarm(mockChatClient);
    }

    /**
     * Creates a swarm with event publisher.
     */
    protected Swarm createSwarmWithPublisher(List<Agent> agents, List<Task> tasks) {
        return TestFixtures.createTestSwarmWithPublisher(agents, tasks, mockEventPublisher);
    }

    // ============================================
    // Event Assertions
    // ============================================

    /**
     * Gets all captured events.
     */
    protected List<SwarmEvent> getCapturedEvents() {
        return new ArrayList<>(capturedEvents);
    }

    /**
     * Gets captured events of a specific type.
     */
    protected List<SwarmEvent> getCapturedEvents(SwarmEvent.Type type) {
        return capturedEvents.stream()
                .filter(e -> e.getType() == type)
                .collect(Collectors.toList());
    }

    /**
     * Asserts that an event of the given type was published.
     */
    protected void assertEventPublished(SwarmEvent.Type type) {
        assertTrue(capturedEvents.stream().anyMatch(e -> e.getType() == type),
                "Expected event of type " + type + " to be published");
    }

    /**
     * Asserts that an event of the given type was NOT published.
     */
    protected void assertEventNotPublished(SwarmEvent.Type type) {
        assertFalse(capturedEvents.stream().anyMatch(e -> e.getType() == type),
                "Expected event of type " + type + " NOT to be published");
    }

    /**
     * Asserts the order of events published.
     */
    protected void assertEventOrder(SwarmEvent.Type... expectedOrder) {
        List<SwarmEvent.Type> actualTypes = capturedEvents.stream()
                .map(SwarmEvent::getType)
                .collect(Collectors.toList());

        int lastIndex = -1;
        for (SwarmEvent.Type expected : expectedOrder) {
            int currentIndex = -1;
            for (int i = lastIndex + 1; i < actualTypes.size(); i++) {
                if (actualTypes.get(i) == expected) {
                    currentIndex = i;
                    break;
                }
            }
            assertTrue(currentIndex > lastIndex,
                    "Expected event " + expected + " after position " + lastIndex +
                            ", but found at " + currentIndex + ". Events: " + actualTypes);
            lastIndex = currentIndex;
        }
    }

    /**
     * Clears captured events.
     */
    protected void clearCapturedEvents() {
        capturedEvents.clear();
    }

    // ============================================
    // Task Assertions
    // ============================================

    /**
     * Asserts that a task completed successfully.
     */
    protected void assertTaskCompleted(Task task) {
        assertEquals(Task.TaskStatus.COMPLETED, task.getStatus(),
                "Task should be COMPLETED");
        assertNotNull(task.getCompletedAt(), "Task should have completion time");
        assertNotNull(task.getOutput(), "Task should have output");
    }

    /**
     * Asserts that a task failed.
     */
    protected void assertTaskFailed(Task task) {
        assertEquals(Task.TaskStatus.FAILED, task.getStatus(),
                "Task should be FAILED");
        assertNotNull(task.getFailureReason(), "Task should have failure reason");
    }

    /**
     * Asserts that a task was skipped.
     */
    protected void assertTaskSkipped(Task task) {
        assertEquals(Task.TaskStatus.SKIPPED, task.getStatus(),
                "Task should be SKIPPED");
    }

    /**
     * Asserts that a task is pending.
     */
    protected void assertTaskPending(Task task) {
        assertEquals(Task.TaskStatus.PENDING, task.getStatus(),
                "Task should be PENDING");
    }

    // ============================================
    // Swarm Assertions
    // ============================================

    /**
     * Asserts that a swarm output is successful.
     */
    protected void assertSwarmSuccessful(SwarmOutput output) {
        assertNotNull(output, "Swarm output should not be null");
        assertTrue(output.isSuccessful(), "Swarm should be successful");
        assertNotNull(output.getFinalOutput(), "Swarm should have final output");
    }

    /**
     * Asserts that a swarm completed with the expected task count.
     */
    protected void assertSwarmCompletedWith(SwarmOutput output, int expectedTaskCount) {
        assertSwarmSuccessful(output);
        assertEquals(expectedTaskCount, output.getTaskOutputs().size(),
                "Swarm should have " + expectedTaskCount + " task outputs");
    }

    /**
     * Asserts the swarm status.
     */
    protected void assertSwarmStatus(Swarm swarm, Swarm.SwarmStatus expected) {
        assertEquals(expected, swarm.getStatus(),
                "Swarm should be " + expected);
    }

    // ============================================
    // Async Helpers
    // ============================================

    /**
     * Waits for a CompletableFuture with timeout.
     */
    protected <T> T waitFor(CompletableFuture<T> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("Future did not complete within " + timeoutSeconds + " seconds", e);
        }
    }

    /**
     * Asserts that a future completes successfully.
     */
    protected <T> T assertFutureCompletes(CompletableFuture<T> future) {
        return waitFor(future, 10);
    }

    /**
     * Asserts that a future fails with an exception.
     */
    protected void assertFutureFails(CompletableFuture<?> future, Class<? extends Exception> exceptionClass) {
        try {
            future.get(10, TimeUnit.SECONDS);
            fail("Expected future to fail with " + exceptionClass.getSimpleName());
        } catch (Exception e) {
            assertTrue(exceptionClass.isInstance(e.getCause()) || exceptionClass.isInstance(e),
                    "Expected " + exceptionClass.getSimpleName() + " but got " + e.getClass().getSimpleName());
        }
    }

    // ============================================
    // Utility Methods
    // ============================================

    /**
     * Generates a unique test ID.
     */
    protected String uniqueId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Creates a long string for testing truncation.
     */
    protected String longString(int length) {
        return "x".repeat(length);
    }

    /**
     * Sleeps for testing async operations.
     */
    protected void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
