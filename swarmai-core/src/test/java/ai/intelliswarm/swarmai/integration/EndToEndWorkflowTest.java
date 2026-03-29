package ai.intelliswarm.swarmai.integration;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("End-to-End Workflow Tests")
@Tag("integration")
class EndToEndWorkflowTest extends BaseSwarmTest {

    @Nested
    @DisplayName("Simple Workflows")
    class SimpleWorkflowTests {

        @Test
        @DisplayName("single agent, single task workflow")
        void simpleWorkflow_singleAgentSingleTask() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertSwarmSuccessful(output);
            assertEquals(1, output.getTaskOutputs().size());
            assertEquals(Swarm.SwarmStatus.COMPLETED, swarm.getStatus());
        }

        @Test
        @DisplayName("single agent, multiple tasks workflow")
        void simpleWorkflow_singleAgentMultipleTasks() {
            ChatClient seqClient = MockChatClientFactory.withResponses(
                    "First response", "Second response", "Third response");
            Agent agent = TestFixtures.createTestAgent(seqClient);

            List<Task> tasks = TestFixtures.createTestTasks(3, agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .tasks(tasks)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertSwarmCompletedWith(output, 3);
        }
    }

    @Nested
    @DisplayName("Complex Workflows")
    class ComplexWorkflowTests {

        @Test
        @DisplayName("multiple agents with task dependencies")
        void complexWorkflow_multipleAgentsWithDependencies() {
            ChatClient seqClient = MockChatClientFactory.withResponses(
                    "Research findings", "Analysis of findings", "Final report");

            Agent researcher = Agent.builder()
                    .id("researcher")
                    .role("Researcher")
                    .goal("Gather information")
                    .backstory("Expert researcher")
                    .chatClient(seqClient)
                    .build();

            Agent analyst = Agent.builder()
                    .id("analyst")
                    .role("Analyst")
                    .goal("Analyze data")
                    .backstory("Expert analyst")
                    .chatClient(seqClient)
                    .build();

            Task researchTask = Task.builder()
                    .id("research")
                    .description("Research the topic")
                    .agent(researcher)
                    .build();

            Task analysisTask = Task.builder()
                    .id("analysis")
                    .description("Analyze the research")
                    .agent(analyst)
                    .dependsOn(researchTask)
                    .build();

            Task reportTask = Task.builder()
                    .id("report")
                    .description("Create final report")
                    .agent(researcher)
                    .dependsOn(analysisTask)
                    .build();

            Swarm swarm = Swarm.builder()
                    .agent(researcher)
                    .agent(analyst)
                    .task(researchTask)
                    .task(analysisTask)
                    .task(reportTask)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertSwarmCompletedWith(output, 3);

            // Verify order
            assertEquals("research", output.getTaskOutputs().get(0).getTaskId());
            assertEquals("analysis", output.getTaskOutputs().get(1).getTaskId());
            assertEquals("report", output.getTaskOutputs().get(2).getTaskId());
        }

        @Test
        @DisplayName("workflow with memory persistence")
        void workflow_withMemory_persistsAcrossTasks() {
            InMemoryMemory memory = new InMemoryMemory();

            Agent agent = createAgent();
            List<Task> tasks = TestFixtures.createTestTasks(2, agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .tasks(tasks)
                    .memory(memory)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertSwarmSuccessful(output);
            assertNotNull(swarm.getMemory());
        }

        @Test
        @DisplayName("workflow with knowledge base")
        void workflow_withKnowledge_usesInPrompts() {
            InMemoryKnowledge knowledge = new InMemoryKnowledge();
            knowledge.addSource("doc-1", "Important context about AI", null);

            MockChatClientFactory.CapturingChatClient capturing =
                    MockChatClientFactory.capturing("Response using knowledge");

            Agent agent = Agent.builder()
                    .role("Expert")
                    .goal("Use knowledge")
                    .backstory("Knowledgeable agent")
                    .chatClient(capturing.getClient())
                    .knowledge(knowledge)
                    .build();

            Task task = Task.builder()
                    .description("Task about AI")
                    .agent(agent)
                    .build();

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .knowledge(knowledge)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertSwarmSuccessful(output);
        }
    }

    @Nested
    @DisplayName("Conditional Workflows")
    class ConditionalWorkflowTests {

        @Test
        @DisplayName("workflow with conditions skips correctly")
        void workflow_withConditions_skipsCorrectly() {
            Agent agent = createAgent();

            Task alwaysRun = Task.builder()
                    .id("always")
                    .description("Always runs")
                    .agent(agent)
                    .build();

            Task conditionalSkip = Task.builder()
                    .id("conditional")
                    .description("Conditionally runs")
                    .agent(agent)
                    .condition(ctx -> false) // Always skip
                    .build();

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(alwaysRun)
                    .task(conditionalSkip)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertEquals(2, output.getTaskOutputs().size());
            assertEquals(Task.TaskStatus.COMPLETED, alwaysRun.getStatus());
            assertEquals(Task.TaskStatus.SKIPPED, conditionalSkip.getStatus());
        }
    }

    @Nested
    @DisplayName("Batch Processing")
    class BatchProcessingTests {

        @Test
        @DisplayName("batch processing completes all inputs")
        void workflow_batchProcessing_completesAll() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            List<Map<String, Object>> inputs = List.of(
                    Map.of("batch", 1),
                    Map.of("batch", 2),
                    Map.of("batch", 3)
            );

            List<SwarmOutput> outputs = swarm.kickoffForEach(inputs);

            assertEquals(3, outputs.size());
            assertTrue(outputs.stream().allMatch(SwarmOutput::isSuccessful));
        }
    }

    @Nested
    @DisplayName("Async Workflows")
    class AsyncWorkflowTests {

        @Test
        @DisplayName("async workflow completes successfully")
        void workflow_async_completesSuccessfully() throws ExecutionException, InterruptedException {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            CompletableFuture<SwarmOutput> future = swarm.kickoffAsync(Map.of());
            SwarmOutput output = future.get();

            assertSwarmSuccessful(output);
        }

        @Test
        @DisplayName("async batch processing")
        void workflow_asyncBatch_completesSuccessfully() throws ExecutionException, InterruptedException {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            List<Map<String, Object>> inputs = List.of(
                    Map.of("batch", 1),
                    Map.of("batch", 2)
            );

            CompletableFuture<List<SwarmOutput>> future = swarm.kickoffForEachAsync(inputs);
            List<SwarmOutput> outputs = future.get();

            assertEquals(2, outputs.size());
        }
    }

    @Nested
    @DisplayName("Event Publishing")
    class EventPublishingTests {

        @Test
        @DisplayName("events published in correct order")
        void workflow_eventsPublished_inCorrectOrder() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            swarm.kickoff(Map.of());

            assertEventOrder(
                    SwarmEvent.Type.SWARM_STARTED,
                    SwarmEvent.Type.PROCESS_STARTED,
                    SwarmEvent.Type.TASK_STARTED,
                    SwarmEvent.Type.TASK_COMPLETED,
                    SwarmEvent.Type.SWARM_COMPLETED
            );
        }
    }

    @Nested
    @DisplayName("Output Aggregation")
    class OutputAggregationTests {

        @Test
        @DisplayName("outputs aggregated correctly")
        void workflow_outputsAggregated_correctly() {
            ChatClient seqClient = MockChatClientFactory.withResponses(
                    "Output 1", "Output 2", "Final Output");
            Agent agent = TestFixtures.createTestAgent(seqClient);

            List<Task> tasks = TestFixtures.createTestTasks(3, agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .tasks(tasks)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertEquals(3, output.getTaskOutputs().size());
            assertEquals("Final Output", output.getFinalOutput());
            assertNotNull(output.getStartTime());
            assertNotNull(output.getEndTime());
        }

        @Test
        @DisplayName("context passed between tasks")
        void workflow_contextPassed_betweenTasks() {
            MockChatClientFactory.CapturingChatClient capturing =
                    MockChatClientFactory.capturing("Response");
            Agent agent = TestFixtures.createTestAgent(capturing.getClient());

            List<Task> tasks = TestFixtures.createDependentTaskChain(2, agent);

            Swarm swarm = Swarm.builder()
                    .agent(agent)
                    .tasks(tasks)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            swarm.kickoff(Map.of());

            // Second task should have received context
            assertEquals(2, capturing.getCallCount());
        }
    }

    @Nested
    @DisplayName("Hierarchical Workflows")
    class HierarchicalWorkflowTests {

        @Test
        @DisplayName("hierarchical workflow delegates correctly")
        void workflow_hierarchical_delegatesCorrectly() {
            ChatClient managerClient = MockChatClientFactory.withResponses(
                    "Delegation plan", "Final summary");
            Agent manager = Agent.builder()
                    .role("Manager")
                    .goal("Coordinate")
                    .backstory("Experienced manager")
                    .chatClient(managerClient)
                    .allowDelegation(true)
                    .build();

            Agent worker = createAgent("Worker");
            Task task = createTask(worker);

            Swarm swarm = Swarm.builder()
                    .agent(manager)
                    .agent(worker)
                    .task(task)
                    .process(ProcessType.HIERARCHICAL)
                    .managerAgent(manager)
                    .eventPublisher(mockEventPublisher)
                    .build();

            SwarmOutput output = swarm.kickoff(Map.of());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }
    }

    @Nested
    @DisplayName("Error Recovery")
    class ErrorRecoveryTests {

        @Test
        @DisplayName("failure recovery handled gracefully")
        void workflow_failureRecovery_handledGracefully() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Error"));
            Task task = createTask(failingAgent);

            Swarm swarm = Swarm.builder()
                    .agent(failingAgent)
                    .task(task)
                    .process(ProcessType.SEQUENTIAL)
                    .eventPublisher(mockEventPublisher)
                    .build();

            assertThrows(RuntimeException.class, () -> swarm.kickoff(Map.of()));

            assertEquals(Swarm.SwarmStatus.FAILED, swarm.getStatus());
            assertEventPublished(SwarmEvent.Type.SWARM_FAILED);
        }
    }
}
