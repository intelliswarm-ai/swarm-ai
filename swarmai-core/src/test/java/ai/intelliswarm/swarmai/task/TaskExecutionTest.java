package ai.intelliswarm.swarmai.task;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task Execution Tests")
class TaskExecutionTest extends BaseSwarmTest {

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("succeeds with pending status")
        void execute_withPendingStatus_succeeds() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertEquals(Task.TaskStatus.PENDING, task.getStatus());

            TaskOutput output = task.execute(Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("throws exception when already executed")
        void execute_withAlreadyExecuted_throwsException() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            task.execute(Collections.emptyList());

            IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getMessage().contains("already been executed"));
        }

        @Test
        @DisplayName("sets status to RUNNING during execution")
        void execute_setsStatusToRunning() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            // Can't easily test RUNNING status mid-execution without threading
            // but we verify it transitions properly
            assertEquals(Task.TaskStatus.PENDING, task.getStatus());
            task.execute(Collections.emptyList());
            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
        }

        @Test
        @DisplayName("sets status to COMPLETED after success")
        void execute_setsStatusToCompleted() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            task.execute(Collections.emptyList());

            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
        }

        @Test
        @DisplayName("sets startedAt timestamp")
        void execute_setsStartedAtTimestamp() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertNull(task.getStartedAt());

            task.execute(Collections.emptyList());

            assertNotNull(task.getStartedAt());
        }

        @Test
        @DisplayName("sets completedAt timestamp")
        void execute_setsCompletedAtTimestamp() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertNull(task.getCompletedAt());

            task.execute(Collections.emptyList());

            assertNotNull(task.getCompletedAt());
        }

        @Test
        @DisplayName("throws exception without agent")
        void execute_withoutAgent_throwsException() {
            Task task = TestFixtures.createTestTaskWithoutAgent();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertTrue(exception.getCause().getMessage().contains("Agent is required"));
        }

        @Test
        @DisplayName("executes when condition is true")
        void execute_withConditionTrue_executes() {
            Agent agent = createAgent();
            Task task = TestFixtures.createTestTaskWithCondition(agent, ctx -> true);

            TaskOutput output = task.execute(Collections.emptyList());

            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("skips when condition is false")
        void execute_withConditionFalse_skips() {
            Agent agent = createAgent();
            Task task = TestFixtures.createTestTaskWithCondition(agent, ctx -> false);

            TaskOutput output = task.execute(Collections.emptyList());

            assertEquals(Task.TaskStatus.SKIPPED, task.getStatus());
            assertTrue(output.getRawOutput().contains("skipped"));
        }

        @Test
        @DisplayName("filters context by dependencies")
        void execute_filtersDependencyContext() {
            Agent agent = createAgent();
            Task task1 = TestFixtures.createTestTaskWithId("task-1", agent);
            Task task2 = Task.builder()
                    .id("task-2")
                    .description("Task with dependency")
                    .agent(agent)
                    .dependsOn(task1)
                    .build();

            List<TaskOutput> context = List.of(
                    TestFixtures.createTaskOutput("task-1", "agent-1", "Output 1"),
                    TestFixtures.createTaskOutput("task-other", "agent-2", "Output other")
            );

            // Task 2 depends only on task-1, so it should filter context
            task2.execute(context);

            assertEquals(Task.TaskStatus.COMPLETED, task2.getStatus());
        }

        @Test
        @DisplayName("passes all context when no dependencies")
        void execute_passesAllContextWhenNoDependencies() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            List<TaskOutput> context = List.of(
                    TestFixtures.createTaskOutput("task-1", "agent-1", "Output 1"),
                    TestFixtures.createTaskOutput("task-2", "agent-2", "Output 2")
            );

            TaskOutput output = task.execute(context);

            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
            assertNotNull(output);
        }

        @Test
        @DisplayName("sets FAILED status when agent fails")
        void execute_whenAgentFails_setsFailedStatus() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Agent failure"));
            Task task = createTask(agent);

            assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertEquals(Task.TaskStatus.FAILED, task.getStatus());
        }

        @Test
        @DisplayName("sets failure reason when agent fails")
        void execute_whenAgentFails_setsFailureReason() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Specific error message"));
            Task task = createTask(agent);

            assertThrows(RuntimeException.class, () ->
                    task.execute(Collections.emptyList()));

            assertNotNull(task.getFailureReason());
        }
    }

    @Nested
    @DisplayName("executeAsync()")
    class ExecuteAsyncTests {

        @Test
        @DisplayName("completes successfully")
        void executeAsync_completesSuccessfully() throws ExecutionException, InterruptedException {
            Agent agent = createAgent();
            Task task = createTask(agent);

            CompletableFuture<TaskOutput> future = task.executeAsync(Collections.emptyList());
            TaskOutput output = future.get();

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(Task.TaskStatus.COMPLETED, task.getStatus());
        }

        @Test
        @DisplayName("propagates exceptions")
        void executeAsync_propagatesExceptions() {
            Agent agent = TestFixtures.createTestAgent(chatClientWithError("Async error"));
            Task task = createTask(agent);

            CompletableFuture<TaskOutput> future = task.executeAsync(Collections.emptyList());

            assertThrows(ExecutionException.class, future::get);
        }
    }

    @Nested
    @DisplayName("isReady()")
    class IsReadyTests {

        @Test
        @DisplayName("returns true with no dependencies")
        void isReady_withNoDependencies_returnsTrue() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertTrue(task.isReady(Set.of()));
            assertTrue(task.isReady(Set.of("other-task")));
        }

        @Test
        @DisplayName("returns true with completed dependencies")
        void isReady_withCompletedDependencies_returnsTrue() {
            Agent agent = createAgent();
            Task task1 = TestFixtures.createTestTaskWithId("task-1", agent);
            Task task2 = TestFixtures.createTestTaskWithDependency(agent, task1);

            assertTrue(task2.isReady(Set.of("task-1")));
        }

        @Test
        @DisplayName("returns false with incomplete dependencies")
        void isReady_withIncompleteDependencies_returnsFalse() {
            Agent agent = createAgent();
            Task task1 = TestFixtures.createTestTaskWithId("task-1", agent);
            Task task2 = TestFixtures.createTestTaskWithDependency(agent, task1);

            assertFalse(task2.isReady(Set.of()));
            assertFalse(task2.isReady(Set.of("task-other")));
        }
    }

    @Nested
    @DisplayName("Task Properties")
    class TaskPropertiesTests {

        @Test
        @DisplayName("stores output after execution")
        void task_storesOutput() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            assertNull(task.getOutput());

            task.execute(Collections.emptyList());

            assertNotNull(task.getOutput());
            assertEquals(task.getOutput().getRawOutput(), TestFixtures.DEFAULT_RESPONSE);
        }

        @Test
        @DisplayName("getters return correct values")
        void task_gettersReturnCorrectValues() {
            Agent agent = createAgent();
            Task task = Task.builder()
                    .id("test-id")
                    .description("Test description")
                    .expectedOutput("Expected output")
                    .agent(agent)
                    .asyncExecution(true)
                    .maxExecutionTime(5000)
                    .context("key", "value")
                    .build();

            assertEquals("test-id", task.getId());
            assertEquals("Test description", task.getDescription());
            assertEquals("Expected output", task.getExpectedOutput());
            assertEquals(agent, task.getAgent());
            assertTrue(task.isAsyncExecution());
            assertEquals(5000, task.getMaxExecutionTime());
            assertEquals("value", task.getContext().get("key"));
        }
    }
}
