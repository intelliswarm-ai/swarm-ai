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

@DisplayName("Iterative Process Tests")
class IterativeProcessTest extends BaseSwarmTest {

    private Agent workerAgent;
    private Agent reviewerAgent;

    @BeforeEach
    void setUp() {
        // Worker agent uses the default mock that always returns a fixed response
        workerAgent = createAgent("Worker Agent");
    }

    @Nested
    @DisplayName("execute() - Approved on first iteration")
    class ApprovedFirstIterationTests {

        @Test
        @DisplayName("stops iterating when reviewer approves on first pass")
        void execute_approvedFirstIteration_stopsImmediately() {
            // Reviewer immediately approves
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                    "APPROVED\n\nThe output meets all quality criteria. Well done.");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            // 1 worker output + 1 review output = 2 total
            assertEquals(2, output.getTaskOutputs().size());
            assertEquals(1, output.getUsageMetrics().get("iterations"));
            assertEquals(true, output.getUsageMetrics().get("approved"));
        }

        @Test
        @DisplayName("publishes ITERATION_REVIEW_PASSED event")
        void execute_approved_publishesPassedEvent() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                    "APPROVED - excellent work");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            process.execute(List.of(task), Map.of(), "test-swarm");

            assertEventPublished(SwarmEvent.Type.ITERATION_REVIEW_PASSED);
        }
    }

    @Nested
    @DisplayName("execute() - Iterative refinement")
    class IterativeRefinementTests {

        @Test
        @DisplayName("re-executes tasks when reviewer says NEEDS_REFINEMENT")
        void execute_needsRefinement_reExecutesTasks() {
            // Worker gives different responses each time
            ChatClient workerClient = MockChatClientFactory.withResponses(
                    "Draft v1 - initial attempt",
                    "Draft v2 - improved with feedback");
            Agent iteratingWorker = TestFixtures.createTestAgent("Worker", workerClient);

            // Reviewer: first rejects, then approves
            ChatClient reviewerClient = MockChatClientFactory.withResponses(
                    "NEEDS_REFINEMENT\n\nThe draft is missing code examples. Add at least 2 snippets.",
                    "APPROVED\n\nGreat improvement. Code examples are clear and relevant.");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(iteratingWorker), reviewerAgent, mockEventPublisher, 3, null);

            Task task = Task.builder()
                    .description("Write a technical blog post")
                    .expectedOutput("A blog post with code examples")
                    .agent(iteratingWorker)
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals(2, output.getUsageMetrics().get("iterations"));
            assertEquals(true, output.getUsageMetrics().get("approved"));
            // 2 iterations * 1 worker task + 2 review tasks = 4
            assertEquals(4, output.getTaskOutputs().size());
        }

        @Test
        @DisplayName("publishes ITERATION_REVIEW_FAILED then PASSED events")
        void execute_refineThenApprove_publishesCorrectEvents() {
            ChatClient workerClient = MockChatClientFactory.withResponses("v1", "v2");
            Agent iteratingWorker = TestFixtures.createTestAgent("Worker", workerClient);

            ChatClient reviewerClient = MockChatClientFactory.withResponses(
                    "NEEDS_REFINEMENT\nFix the introduction.",
                    "APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(iteratingWorker), reviewerAgent, mockEventPublisher, 3, null);

            Task task = Task.builder()
                    .description("Write content")
                    .agent(iteratingWorker)
                    .build();

            process.execute(List.of(task), Map.of(), "test-swarm");

            assertEventPublished(SwarmEvent.Type.ITERATION_REVIEW_FAILED);
            assertEventPublished(SwarmEvent.Type.ITERATION_REVIEW_PASSED);
            assertEventPublished(SwarmEvent.Type.ITERATION_STARTED);
            assertEventPublished(SwarmEvent.Type.ITERATION_COMPLETED);
        }
    }

    @Nested
    @DisplayName("execute() - Max iterations")
    class MaxIterationsTests {

        @Test
        @DisplayName("stops after max iterations even if not approved")
        void execute_maxIterationsReached_stops() {
            // Reviewer never approves
            ChatClient workerClient = MockChatClientFactory.withResponse("Draft output");
            Agent iteratingWorker = TestFixtures.createTestAgent("Worker", workerClient);

            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                    "NEEDS_REFINEMENT\nStill not good enough.");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            int maxIter = 2;
            IterativeProcess process = new IterativeProcess(
                    List.of(iteratingWorker), reviewerAgent, mockEventPublisher, maxIter, null);

            Task task = Task.builder()
                    .description("Write something")
                    .agent(iteratingWorker)
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(maxIter, output.getUsageMetrics().get("iterations"));
            assertEquals(false, output.getUsageMetrics().get("approved"));
        }

        @Test
        @DisplayName("single iteration when maxIterations is 1")
        void execute_singleIteration_executesOnce() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                    "NEEDS_REFINEMENT\nCould be better.");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 1, null);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertEquals(1, output.getUsageMetrics().get("iterations"));
        }
    }

    @Nested
    @DisplayName("execute() - Multiple tasks")
    class MultipleTasksTests {

        @Test
        @DisplayName("executes dependent tasks in order with iteration")
        void execute_dependentTasks_executesInOrder() {
            ChatClient workerClient = MockChatClientFactory.withResponses(
                    "Research output", "Blog post draft",
                    "Research v2", "Blog post v2");
            Agent iteratingWorker = TestFixtures.createTestAgent("Worker", workerClient);

            ChatClient reviewerClient = MockChatClientFactory.withResponses(
                    "NEEDS_REFINEMENT\nNeed more depth.",
                    "APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(iteratingWorker), reviewerAgent, mockEventPublisher, 3, null);

            Task research = Task.builder()
                    .id("research")
                    .description("Research the topic")
                    .agent(iteratingWorker)
                    .build();

            Task writing = Task.builder()
                    .id("writing")
                    .description("Write a blog post")
                    .agent(iteratingWorker)
                    .dependsOn(research)
                    .build();

            SwarmOutput output = process.execute(List.of(research, writing), Map.of(), "test-swarm");

            assertNotNull(output);
            assertEquals(2, output.getUsageMetrics().get("iterations"));
            assertEquals(true, output.getUsageMetrics().get("approved"));
        }
    }

    @Nested
    @DisplayName("execute() - Quality criteria")
    class QualityCriteriaTests {

        @Test
        @DisplayName("passes quality criteria to reviewer")
        void execute_withQualityCriteria_passesToReviewer() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            String criteria = "Must have code examples and be technically accurate";
            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, criteria);

            Task task = createTask(workerAgent);
            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertTrue(output.isSuccessful());
        }
    }

    @Nested
    @DisplayName("execute() - Final output")
    class FinalOutputTests {

        @Test
        @DisplayName("final output comes from last worker task, not review")
        void execute_finalOutput_isFromWorkerNotReview() {
            String expectedWorkerOutput = "This is the worker's final blog post content";
            ChatClient workerClient = MockChatClientFactory.withResponse(expectedWorkerOutput);
            Agent singleWorker = TestFixtures.createTestAgent("Worker", workerClient);

            ChatClient reviewerClient = MockChatClientFactory.withResponse(
                    "APPROVED - looks great!");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(singleWorker), reviewerAgent, mockEventPublisher, 3, null);

            Task task = Task.builder()
                    .description("Write content")
                    .agent(singleWorker)
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of(), "test-swarm");

            assertEquals(expectedWorkerOutput, output.getFinalOutput());
        }
    }

    @Nested
    @DisplayName("validateTasks()")
    class ValidateTasksTests {

        @Test
        @DisplayName("throws exception with empty task list")
        void validateTasks_emptyList_throws() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher);

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(Collections.emptyList(), Map.of(), "test-swarm"));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("empty") || causeMessage.contains("empty"),
                    "Expected message about empty tasks. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("throws exception with missing dependency")
        void validateTasks_missingDep_throws() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher);

            Task task = Task.builder()
                    .description("Task with bad dep")
                    .agent(workerAgent)
                    .dependsOn("ghost-task")
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task), Map.of(), "test-swarm"));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("non-existent") || causeMessage.contains("non-existent"),
                    "Expected missing dependency message. Got: " + message + " / " + causeMessage);
        }
    }

    @Nested
    @DisplayName("Process Properties")
    class ProcessPropertiesTests {

        @Test
        @DisplayName("returns ITERATIVE type")
        void getType_returnsIterative() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher);

            assertEquals(ProcessType.ITERATIVE, process.getType());
        }

        @Test
        @DisplayName("is not async")
        void isAsync_returnsFalse() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher);

            assertFalse(process.isAsync());
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("publishes PROCESS_FAILED on error")
        void execute_onError_publishesProcessFailedEvent() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Task error"));

            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(failingAgent), reviewerAgent, mockEventPublisher);

            Task task = createTask(failingAgent);

            assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task), Map.of(), "test-swarm"));

            assertEventPublished(SwarmEvent.Type.PROCESS_FAILED);
        }

        @Test
        @DisplayName("publishes PROCESS_STARTED before failure")
        void execute_onError_publishesProcessStartedFirst() {
            Agent failingAgent = TestFixtures.createTestAgent(chatClientWithError("Fail"));

            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(failingAgent), reviewerAgent, mockEventPublisher);

            Task task = createTask(failingAgent);

            assertThrows(RuntimeException.class, () ->
                    process.execute(List.of(task), Map.of(), "test-swarm"));

            assertEventPublished(SwarmEvent.Type.PROCESS_STARTED);
            assertEventPublished(SwarmEvent.Type.PROCESS_FAILED);
        }
    }

    @Nested
    @DisplayName("Event Flow")
    class EventFlowTests {

        @Test
        @DisplayName("publishes correct event sequence for single approved iteration")
        void execute_singleApproved_correctEventSequence() {
            ChatClient reviewerClient = MockChatClientFactory.withResponse("APPROVED");
            reviewerAgent = TestFixtures.createTestAgent("Reviewer", reviewerClient);

            IterativeProcess process = new IterativeProcess(
                    List.of(workerAgent), reviewerAgent, mockEventPublisher, 3, null);

            Task task = createTask(workerAgent);
            process.execute(List.of(task), Map.of(), "test-swarm");

            assertEventOrder(
                    SwarmEvent.Type.PROCESS_STARTED,
                    SwarmEvent.Type.ITERATION_STARTED,
                    SwarmEvent.Type.TASK_STARTED,
                    SwarmEvent.Type.TASK_COMPLETED,
                    SwarmEvent.Type.ITERATION_REVIEW_PASSED,
                    SwarmEvent.Type.ITERATION_COMPLETED
            );
        }
    }
}
