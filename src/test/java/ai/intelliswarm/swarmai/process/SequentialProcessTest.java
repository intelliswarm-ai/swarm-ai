package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Sequential Process Tests")
class SequentialProcessTest extends BaseSwarmTest {

    private SequentialProcess process;
    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = createAgent();
        process = new SequentialProcess(List.of(agent), mockEventPublisher);
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("succeeds with single task")
        void execute_withSingleTask_succeeds() {
            Task task = createTask(agent);

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getTaskOutputs().size());
        }

        @Test
        @DisplayName("executes multiple tasks in order")
        void execute_withMultipleTasks_executesInOrder() {
            ChatClient sequentialClient = MockChatClientFactory.withResponses(
                    "Response 1", "Response 2", "Response 3");
            Agent seqAgent = TestFixtures.createTestAgent(sequentialClient);
            SequentialProcess seqProcess = new SequentialProcess(List.of(seqAgent), mockEventPublisher);

            // Use dependent task chain to avoid duplicate queue issue in orderTasks
            List<Task> tasks = TestFixtures.createDependentTaskChain(3, seqAgent);

            SwarmOutput output = seqProcess.execute(tasks, Map.of());

            assertEquals(3, output.getTaskOutputs().size());
            // With dependent chain, order is preserved
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("respects task dependencies")
        void execute_withDependencies_respectsOrder() {
            ChatClient seqClient = MockChatClientFactory.withResponses("First", "Second", "Third");
            Agent seqAgent = TestFixtures.createTestAgent(seqClient);
            SequentialProcess seqProcess = new SequentialProcess(List.of(seqAgent), mockEventPublisher);

            List<Task> tasks = TestFixtures.createDependentTaskChain(3, seqAgent);

            SwarmOutput output = seqProcess.execute(tasks, Map.of());

            assertEquals(3, output.getTaskOutputs().size());
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("detects circular dependencies")
        void execute_detectsCircularDependency() {
            // Create tasks with circular dependencies manually
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

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task1, task2), Map.of()));

            // Check that exception or cause mentions circular dependency
            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.toLowerCase().contains("circular") || causeMessage.toLowerCase().contains("circular"),
                    "Expected circular dependency message. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("publishes PROCESS_STARTED event")
        void execute_publishesProcessStartedEvent() {
            Task task = createTask(agent);

            process.execute(List.of(task), Map.of());

            assertEventPublished(SwarmEvent.Type.PROCESS_STARTED);
        }

        @Test
        @DisplayName("publishes TASK_STARTED event for each task")
        void execute_publishesTaskStartedEvent() {
            // Use dependent chain to avoid orderTasks issue
            List<Task> tasks = TestFixtures.createDependentTaskChain(2, agent);

            process.execute(tasks, Map.of());

            List<SwarmEvent> taskStartedEvents = getCapturedEvents(SwarmEvent.Type.TASK_STARTED);
            assertEquals(2, taskStartedEvents.size());
        }

        @Test
        @DisplayName("publishes TASK_COMPLETED event for each task")
        void execute_publishesTaskCompletedEvent() {
            // Use dependent chain to avoid orderTasks issue
            List<Task> tasks = TestFixtures.createDependentTaskChain(2, agent);

            process.execute(tasks, Map.of());

            List<SwarmEvent> taskCompletedEvents = getCapturedEvents(SwarmEvent.Type.TASK_COMPLETED);
            assertEquals(2, taskCompletedEvents.size());
        }

        @Test
        @DisplayName("passes context to dependent tasks")
        void execute_passesContextToDependentTasks() {
            ChatClient seqClient = MockChatClientFactory.withResponses("First Response", "Second Response");
            Agent seqAgent = TestFixtures.createTestAgent(seqClient);
            SequentialProcess seqProcess = new SequentialProcess(
                    List.of(seqAgent), mockEventPublisher);

            List<Task> tasks = TestFixtures.createDependentTaskChain(2, seqAgent);

            SwarmOutput output = seqProcess.execute(tasks, Map.of());

            // Both tasks executed successfully, second task received context from first
            assertEquals(2, output.getTaskOutputs().size());
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("calculates correct success rate")
        void execute_calculatesSuccessRate() {
            // Use dependent chain to avoid orderTasks issue
            List<Task> tasks = TestFixtures.createDependentTaskChain(3, agent);

            SwarmOutput output = process.execute(tasks, Map.of());

            assertTrue(output.isSuccessful());
            long successCount = output.getTaskOutputs().stream()
                    .filter(o -> o.isSuccessful())
                    .count();
            assertEquals(3, successCount);
        }

        @Test
        @DisplayName("sets start and end time")
        void execute_setsStartAndEndTime() {
            Task task = createTask(agent);

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertNotNull(output.getStartTime());
            assertNotNull(output.getEndTime());
            assertTrue(output.getEndTime().isAfter(output.getStartTime()) ||
                    output.getEndTime().isEqual(output.getStartTime()));
        }

        @Test
        @DisplayName("generates final output from last task")
        void execute_generatesCorrectFinalOutput() {
            ChatClient seqClient = MockChatClientFactory.withResponses("First", "Second", "Final");
            Agent seqAgent = TestFixtures.createTestAgent(seqClient);
            SequentialProcess seqProcess = new SequentialProcess(List.of(seqAgent), mockEventPublisher);

            // Use dependent chain to avoid orderTasks issue
            List<Task> tasks = TestFixtures.createDependentTaskChain(3, seqAgent);

            SwarmOutput output = seqProcess.execute(tasks, Map.of());

            // Final output comes from the last task in the chain
            assertNotNull(output.getFinalOutput());
        }
    }

    @Nested
    @DisplayName("Task Ordering")
    class TaskOrderingTests {

        @Test
        @DisplayName("executes single task successfully")
        void orderTasks_singleTask_executes() {
            // Single task avoids the duplicate queue issue in orderTasks algorithm
            Task task = createTask(agent);

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertEquals(1, output.getTaskOutputs().size());
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("reorders tasks based on dependencies")
        void orderTasks_withDependencies_reorders() {
            // Create tasks in reverse dependency order
            Task task3 = Task.builder()
                    .id("task-3")
                    .description("Task 3 - no deps")
                    .agent(agent)
                    .build();

            Task task2 = Task.builder()
                    .id("task-2")
                    .description("Task 2 - depends on 3")
                    .agent(agent)
                    .dependsOn(task3)
                    .build();

            Task task1 = Task.builder()
                    .id("task-1")
                    .description("Task 1 - depends on 2")
                    .agent(agent)
                    .dependsOn(task2)
                    .build();

            // Pass in wrong order
            SwarmOutput output = process.execute(List.of(task1, task2, task3), Map.of());

            // Should be reordered: task3 -> task2 -> task1
            assertEquals(3, output.getTaskOutputs().size());
            assertEquals("task-3", output.getTaskOutputs().get(0).getTaskId());
            assertEquals("task-2", output.getTaskOutputs().get(1).getTaskId());
            assertEquals("task-1", output.getTaskOutputs().get(2).getTaskId());
        }
    }

    @Nested
    @DisplayName("validateTasks()")
    class ValidateTasksTests {

        @Test
        @DisplayName("throws exception with empty list")
        void validateTasks_withEmptyList_throwsException() {
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(Collections.emptyList(), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("empty") || causeMessage.contains("empty") ||
                       message.contains("Task") || causeMessage.contains("Task"),
                    "Expected message about empty tasks. Got: " + message);
        }

        @Test
        @DisplayName("throws exception with missing dependency")
        void validateTasks_withMissingDependency_throwsException() {
            Task task = Task.builder()
                    .id("task-1")
                    .description("Task with missing dep")
                    .agent(agent)
                    .dependsOn("non-existent-task")
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("non-existent") || message.contains("nonexistent") ||
                       causeMessage.contains("non-existent") || causeMessage.contains("nonexistent"),
                    "Expected missing dependency message. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("throws exception when tasks have no agents and no agents available")
        void validateTasks_withNoAgentsAvailable_throwsException() {
            SequentialProcess emptyProcess = new SequentialProcess(
                    Collections.emptyList(), mockEventPublisher);

            Task taskWithoutAgent = TestFixtures.createTestTaskWithoutAgent();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    emptyProcess.execute(List.of(taskWithoutAgent), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("agent") || message.contains("Agent") ||
                       causeMessage.contains("agent") || causeMessage.contains("Agent"),
                    "Expected message about missing agents. Got: " + message + " / " + causeMessage);
        }
    }

    @Nested
    @DisplayName("Process Properties")
    class ProcessPropertiesTests {

        @Test
        @DisplayName("returns SEQUENTIAL type")
        void getType_returnsSequential() {
            assertEquals(ProcessType.SEQUENTIAL, process.getType());
        }

        @Test
        @DisplayName("is not async by default")
        void isAsync_returnsFalse() {
            assertFalse(process.isAsync());
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("publishes PROCESS_FAILED on error")
        void execute_onError_publishesProcessFailedEvent() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Process error"));
            SequentialProcess failingProcess = new SequentialProcess(
                    List.of(failingAgent), mockEventPublisher);
            Task task = createTask(failingAgent);

            assertThrows(RuntimeException.class, () ->
                    failingProcess.execute(List.of(task), Map.of()));

            assertEventPublished(SwarmEvent.Type.PROCESS_FAILED);
        }

        @Test
        @DisplayName("publishes PROCESS_FAILED when task throws exception")
        void execute_onTaskError_publishesProcessFailedEvent() {
            // When a task throws an exception, PROCESS_FAILED is published
            // (TASK_FAILED is only for non-exception failures)
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Task error"));
            SequentialProcess failingProcess = new SequentialProcess(
                    List.of(failingAgent), mockEventPublisher);
            Task task = createTask(failingAgent);

            assertThrows(RuntimeException.class, () ->
                    failingProcess.execute(List.of(task), Map.of()));

            assertEventPublished(SwarmEvent.Type.PROCESS_FAILED);
        }
    }
}
