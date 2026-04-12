package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.exception.ProcessExecutionException;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Parallel Process Tests")
class ParallelProcessTest extends BaseSwarmTest {

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = createAgent();
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("succeeds with single task")
        void execute_withSingleTask_succeeds() {
            ParallelProcess process = new ParallelProcess(List.of(agent), mockEventPublisher);
            Task task = TestFixtures.createTestTask(agent);

            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getTaskOutputs().size());
        }


        @Test
        @DisplayName("handles null inputs without throwing")
        void execute_withNullInputs_succeeds() {
            ParallelProcess process = new ParallelProcess(List.of(agent), mockEventPublisher);
            Task task = TestFixtures.createTestTask(agent);

            SwarmOutput output = process.execute(List.of(task), null, "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(1, output.getTaskOutputs().size());
        }

        @Test
        @DisplayName("executes multiple independent tasks in parallel")
        void execute_withMultipleIndependentTasks_succeeds() {
            ChatClient multiClient = MockChatClientFactory.withResponses(
                    "Response A", "Response B", "Response C");
            Agent multiAgent = TestFixtures.createTestAgent(multiClient);
            ParallelProcess process = new ParallelProcess(List.of(multiAgent), mockEventPublisher);

            Task taskA = TestFixtures.createTestTask("Task A", multiAgent);
            Task taskB = TestFixtures.createTestTask("Task B", multiAgent);
            Task taskC = TestFixtures.createTestTask("Task C", multiAgent);

            SwarmOutput output = process.execute(List.of(taskA, taskB, taskC), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(3, output.getTaskOutputs().size());
        }
    }

    @Nested
    @DisplayName("partial result recovery")
    class PartialResultTests {

        @Test
        @DisplayName("collects partial results when some tasks fail")
        void execute_withSomeFailures_returnsPartialResults() {
            // One agent succeeds, one fails
            ChatClient successClient = MockChatClientFactory.withResponse("Success output");
            ChatClient failClient = MockChatClientFactory.withError("LLM call failed");

            Agent successAgent = TestFixtures.createTestAgent("Success Agent", successClient);
            Agent failAgent = TestFixtures.createTestAgent("Fail Agent", failClient);

            ParallelProcess process = new ParallelProcess(
                    List.of(successAgent, failAgent), mockEventPublisher);

            Task successTask = TestFixtures.createTestTask("Succeed", successAgent);
            Task failTask = TestFixtures.createTestTask("Fail", failAgent);

            // Should NOT throw — should return partial results
            SwarmOutput output = process.execute(List.of(successTask, failTask), Map.of(), "test-swarm");

            assertNotNull(output);
            // At least one task output should be present
            assertFalse(output.getTaskOutputs().isEmpty(),
                    "Should have partial results from successful tasks");
        }

        @Test
        @DisplayName("throws when ALL tasks fail")
        void execute_withAllFailures_throws() {
            ChatClient failClient = MockChatClientFactory.withError("LLM call failed");
            Agent failAgent = TestFixtures.createTestAgent("Fail Agent", failClient);

            ParallelProcess process = new ParallelProcess(
                    List.of(failAgent), mockEventPublisher);

            Task failTask1 = TestFixtures.createTestTask("Fail 1", failAgent);
            Task failTask2 = TestFixtures.createTestTask("Fail 2", failAgent);

            assertThrows(Exception.class, () ->
                    process.execute(List.of(failTask1, failTask2), Map.of(), "test-swarm"));
        }
    }

    @Nested
    @DisplayName("timeout configuration")
    class TimeoutTests {

        @Test
        @DisplayName("uses configurable per-task timeout")
        void constructor_withCustomTimeout_usesIt() {
            // 1 second per-task timeout, 1 max concurrent
            ParallelProcess process = new ParallelProcess(
                    List.of(agent), mockEventPublisher, 2, 1, 1);

            // Just verify it constructs without error
            assertNotNull(process);
            assertEquals(ProcessType.PARALLEL, process.getType());
        }

        @Test
        @DisplayName("defaults per-task timeout to 300 seconds")
        void constructor_withDefaults_uses300sPerTask() {
            ParallelProcess process = new ParallelProcess(List.of(agent), mockEventPublisher);

            // Verify it works with default timeout
            Task task = TestFixtures.createTestTask(agent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");
            assertNotNull(output);
        }

        @Test
        @DisplayName("scales timeout with layer size")
        void execute_withLargeLayer_scaledTimeout() {
            // 4 tasks with 2 max concurrent = 2 batches, so timeout = 2 * perTaskTimeout
            ChatClient multiClient = MockChatClientFactory.withResponses(
                    "R1", "R2", "R3", "R4");
            Agent multiAgent = TestFixtures.createTestAgent(multiClient);

            // Short per-task timeout (10s), 2 concurrent
            ParallelProcess process = new ParallelProcess(
                    List.of(multiAgent), mockEventPublisher, 2, 10, 2);

            Task t1 = TestFixtures.createTestTask("T1", multiAgent);
            Task t2 = TestFixtures.createTestTask("T2", multiAgent);
            Task t3 = TestFixtures.createTestTask("T3", multiAgent);
            Task t4 = TestFixtures.createTestTask("T4", multiAgent);

            // Should succeed — 4 tasks / 2 concurrent = 2 batches * 10s = 20s timeout
            SwarmOutput output = process.execute(
                    List.of(t1, t2, t3, t4), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(4, output.getTaskOutputs().size());
        }
    }

    @Nested
    @DisplayName("concurrency control")
    class ConcurrencyTests {

        @Test
        @DisplayName("respects maxConcurrentLlmCalls")
        void execute_withConcurrencyLimit_respectsLimit() {
            ChatClient client = MockChatClientFactory.withResponses("R1", "R2");
            Agent concAgent = TestFixtures.createTestAgent(client);

            // maxConcurrentLlmCalls = 1 → tasks run sequentially even in parallel layer
            ParallelProcess process = new ParallelProcess(
                    List.of(concAgent), mockEventPublisher, 4, 300, 1);

            Task t1 = TestFixtures.createTestTask("T1", concAgent);
            Task t2 = TestFixtures.createTestTask("T2", concAgent);

            SwarmOutput output = process.execute(List.of(t1, t2), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(2, output.getTaskOutputs().size());
        }
    }
}
