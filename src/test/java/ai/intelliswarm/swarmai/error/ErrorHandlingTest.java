package ai.intelliswarm.swarmai.error;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.process.SequentialProcess;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Error Handling Tests")
class ErrorHandlingTest extends BaseSwarmTest {

    @Nested
    @DisplayName("Swarm Validation Errors")
    class SwarmValidationErrorsTests {

        @Test
        @DisplayName("throws exception with no agents")
        void swarm_withNoAgents_throwsException() {
            Task task = TestFixtures.createTestTaskWithoutAgent();

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    Swarm.builder()
                            .task(task)
                            .process(ProcessType.SEQUENTIAL)
                            .build());

            assertTrue(exception.getMessage().toLowerCase().contains("agent"));
        }

        @Test
        @DisplayName("throws exception with no tasks")
        void swarm_withNoTasks_throwsException() {
            Agent agent = createAgent();

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    Swarm.builder()
                            .agent(agent)
                            .process(ProcessType.SEQUENTIAL)
                            .build());

            assertTrue(exception.getMessage().toLowerCase().contains("task"));
        }

        @Test
        @DisplayName("throws exception for hierarchical without manager")
        void swarm_hierarchicalWithoutManager_throwsException() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    Swarm.builder()
                            .agent(agent)
                            .task(task)
                            .process(ProcessType.HIERARCHICAL)
                            .build());

            assertTrue(exception.getMessage().toLowerCase().contains("manager"));
        }
    }

    @Nested
    @DisplayName("Agent Validation Errors")
    class AgentValidationErrorsTests {

        @Test
        @DisplayName("throws exception with null role")
        void agent_withNullRole_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    Agent.builder()
                            .goal("Goal")
                            .backstory("Backstory")
                            .chatClient(mockChatClient)
                            .build());
        }

        @Test
        @DisplayName("throws exception with null goal")
        void agent_withNullGoal_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    Agent.builder()
                            .role("Role")
                            .backstory("Backstory")
                            .chatClient(mockChatClient)
                            .build());
        }

        @Test
        @DisplayName("throws exception with null backstory")
        void agent_withNullBackstory_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    Agent.builder()
                            .role("Role")
                            .goal("Goal")
                            .chatClient(mockChatClient)
                            .build());
        }

        @Test
        @DisplayName("throws exception with null ChatClient")
        void agent_withNullChatClient_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    Agent.builder()
                            .role("Role")
                            .goal("Goal")
                            .backstory("Backstory")
                            .build());
        }
    }

    @Nested
    @DisplayName("Task Validation Errors")
    class TaskValidationErrorsTests {

        @Test
        @DisplayName("throws exception with null description")
        void task_withNullDescription_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    Task.builder().build());
        }

        @Test
        @DisplayName("throws exception when executed without agent")
        void task_withNullAgent_throwsException() {
            Task task = TestFixtures.createTestTaskWithoutAgent();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getCause().getMessage().contains("Agent is required"));
        }

        @Test
        @DisplayName("throws exception when already executed")
        void task_alreadyExecuted_throwsException() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            task.execute(Collections.emptyList());

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getMessage().contains("already been executed"));
        }
    }

    @Nested
    @DisplayName("Agent Execution Errors")
    class AgentExecutionErrorsTests {

        @Test
        @DisplayName("throws exception on ChatClient failure")
        void agent_chatClientFailure_throwsException() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("API failure"));
            Task task = createTask(agent);

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getMessage().contains("Task execution failed") ||
                    exception.getMessage().contains("Failed to execute task"));
        }
    }

    @Nested
    @DisplayName("Process Validation Errors")
    class ProcessValidationErrorsTests {

        @Test
        @DisplayName("throws exception for circular dependencies")
        void process_circularDependency_throwsException() {
            Agent agent = createAgent();

            Task task1 = Task.builder()
                    .id("task-1")
                    .description("Task 1")
                    .agent(agent)
                    .dependsOn("task-2")
                    .build();

            Task task2 = Task.builder()
                    .id("task-2")
                    .description("Task 2")
                    .agent(agent)
                    .dependsOn("task-1")
                    .build();

            SequentialProcess process = new SequentialProcess(List.of(agent), mockEventPublisher);

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task1, task2), Map.of()));

            // Check that the exception or its cause mentions circular dependency
            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.toLowerCase().contains("circular") || causeMessage.toLowerCase().contains("circular"),
                    "Expected circular dependency message. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("throws exception for missing dependency")
        void process_missingDependency_throwsException() {
            Agent agent = createAgent();

            Task task = Task.builder()
                    .id("task-1")
                    .description("Task 1")
                    .agent(agent)
                    .dependsOn("nonexistent-task")
                    .build();

            SequentialProcess process = new SequentialProcess(List.of(agent), mockEventPublisher);

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task), Map.of()));

            // Check that the exception or its cause mentions non-existent/missing dependency
            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("non-existent") || message.contains("nonexistent") ||
                       causeMessage.contains("non-existent") || causeMessage.contains("nonexistent"),
                    "Expected missing dependency message. Got: " + message + " / " + causeMessage);
        }
    }

    @Nested
    @DisplayName("Task Status Tracking")
    class TaskStatusTrackingTests {

        @Test
        @DisplayName("captures failure reason")
        void task_failureReason_captured() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Specific error message"));
            Task task = createTask(agent);

            assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertNotNull(task.getFailureReason());
        }

        @Test
        @DisplayName("sets failed status correctly")
        void task_failedStatus_setCorrectly() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Error"));
            Task task = createTask(agent);

            assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertEquals(Task.TaskStatus.FAILED, task.getStatus());
        }

        @Test
        @DisplayName("sets completed timestamp on failure")
        void task_completedAtSetOnFailure() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Error"));
            Task task = createTask(agent);

            assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertNotNull(task.getCompletedAt());
        }
    }

    @Nested
    @DisplayName("Swarm Status Tracking")
    class SwarmStatusTrackingTests {

        @Test
        @DisplayName("sets failed status correctly on swarm failure")
        void swarm_failedStatus_setCorrectly() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Error"));
            Task task = createTask(failingAgent);

            Swarm swarm = Swarm.builder()
                    .agent(failingAgent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            assertThrows(RuntimeException.class, () ->
                    swarm.kickoff(Map.of()));

            assertEquals(Swarm.SwarmStatus.FAILED, swarm.getStatus());
        }

        @Test
        @DisplayName("preserves READY status before kickoff")
        void swarm_readyStatus_beforeKickoff() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .build();

            assertEquals(Swarm.SwarmStatus.READY, swarm.getStatus());
        }
    }

    @Nested
    @DisplayName("Null Input Handling")
    class NullInputHandlingTests {

        @Test
        @DisplayName("swarm handles null inputs map")
        void swarm_withNullInputs_handlesGracefully() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .build();

            // Should not throw NPE
            assertDoesNotThrow(() -> swarm.kickoff(null));
        }

        @Test
        @DisplayName("task handles null context list")
        void task_withNullContext_handlesGracefully() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            // Should not throw NullPointerException specifically
            // Other exceptions may occur due to mock setup, but NPE should not
            try {
                task.execute(null);
            } catch (NullPointerException e) {
                fail("Task should handle null context without NPE");
            } catch (RuntimeException e) {
                // Other exceptions are acceptable - the point is no NPE on null context
            }
        }
    }

    @Nested
    @DisplayName("Exception Messages")
    class ExceptionMessagesTests {

        @Test
        @DisplayName("swarm failure includes descriptive message")
        void swarm_failureMessage_isDescriptive() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Root cause"));
            Task task = createTask(failingAgent);

            Swarm swarm = Swarm.builder()
                    .agent(failingAgent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    swarm.kickoff(Map.of()));

            assertTrue(exception.getMessage().contains("Swarm execution failed"));
        }

        @Test
        @DisplayName("task failure includes task ID")
        void task_failureMessage_includesTaskId() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Error"));
            Task task = Task.builder()
                    .id("my-task-id")
                    .description("Test")
                    .agent(failingAgent)
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getMessage().contains("my-task-id"));
        }
    }
}
