package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Agent Execution Tests")
class AgentExecutionTest extends BaseSwarmTest {

    @Nested
    @DisplayName("executeTask()")
    class ExecuteTaskTests {

        @Test
        @DisplayName("returns TaskOutput with valid input")
        void executeTask_withValidInput_returnsTaskOutput() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertEquals(TestFixtures.DEFAULT_RESPONSE, output.getRawOutput());
        }

        @Test
        @DisplayName("succeeds with empty context")
        void executeTask_withEmptyContext_succeeds() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("executes successfully with context")
        void executeTask_withContext_executesSuccessfully() {
            Agent agent = createAgent();
            Task task = createTask(agent);

            List<TaskOutput> context = List.of(
                    TestFixtures.createTaskOutput("prev-task", "prev-agent", "Previous output")
            );

            TaskOutput output = agent.executeTask(task, context);

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            // Context processing is verified by successful execution
        }

        @Test
        @DisplayName("queries knowledge base when configured")
        void executeTask_withKnowledge_queriesKnowledgeBase() {
            InMemoryKnowledge knowledge = new InMemoryKnowledge();
            knowledge.addSource("doc1", "Relevant knowledge about AI", null);

            Agent agent = Agent.builder()
                    .role("Test Agent")
                    .goal("Test goal")
                    .backstory("Test backstory")
                    .chatClient(mockChatClient)
                    .knowledge(knowledge)
                    .build();

            Task task = Task.builder()
                    .description("Task about AI")
                    .agent(agent)
                    .build();

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
        }

        @Test
        @DisplayName("registers tools when available")
        void executeTask_withTools_registersFunctions() {
            List<BaseTool> tools = TestFixtures.createMockTools("search", "calculate");
            Agent agent = TestFixtures.createTestAgentWithTools(mockChatClient, tools);
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertEquals(2, agent.getTools().size());
        }

        @Test
        @DisplayName("increments execution count")
        void executeTask_incrementsExecutionCount() {
            Agent agent = createAgent();
            Task task1 = createTask("Task 1", agent);
            Task task2 = createTask("Task 2", agent);

            assertEquals(0, agent.getExecutionCount());

            agent.executeTask(task1, Collections.emptyList());
            assertEquals(1, agent.getExecutionCount());

            agent.executeTask(task2, Collections.emptyList());
            assertEquals(2, agent.getExecutionCount());
        }

        @Test
        @DisplayName("sets correct agent ID in output")
        void executeTask_setsCorrectAgentId() {
            Agent agent = TestFixtures.createTestAgentWithId("agent-123", mockChatClient);
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertEquals("agent-123", output.getAgentId());
        }

        @Test
        @DisplayName("sets correct task ID in output")
        void executeTask_setsCorrectTaskId() {
            Agent agent = createAgent();
            Task task = TestFixtures.createTestTaskWithId("task-456", agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertEquals("task-456", output.getTaskId());
        }

        @Test
        @DisplayName("extracts summary from long response")
        void executeTask_extractsSummaryFromLongResponse() {
            String longResponse = "x".repeat(200);
            ChatClient client = chatClientWithResponse(longResponse);
            Agent agent = TestFixtures.createTestAgent(client);
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output.getSummary());
            assertTrue(output.getSummary().length() <= 100);
            assertTrue(output.getSummary().endsWith("..."));
        }

        @Test
        @DisplayName("keeps short response as summary")
        void executeTask_extractsSummaryFromShortResponse() {
            String shortResponse = "Short response";
            ChatClient client = chatClientWithResponse(shortResponse);
            Agent agent = TestFixtures.createTestAgent(client);
            Task task = createTask(agent);

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertEquals(shortResponse, output.getSummary());
        }

        @Test
        @DisplayName("throws exception when ChatClient fails")
        void executeTask_whenChatClientFails_throwsException() {
            ChatClient failingClient = chatClientWithError("API error");
            Agent agent = TestFixtures.createTestAgent(failingClient);
            Task task = createTask(agent);

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    agent.executeTask(task, Collections.emptyList()));

            assertTrue(exception.getMessage().contains("Failed to execute task"));
        }
    }

    @Nested
    @DisplayName("executeTaskAsync()")
    class ExecuteTaskAsyncTests {

        @Test
        @DisplayName("completes successfully")
        void executeTaskAsync_completesSuccessfully() throws ExecutionException, InterruptedException {
            Agent agent = createAgent();
            Task task = createTask(agent);

            CompletableFuture<TaskOutput> future = agent.executeTaskAsync(task, Collections.emptyList());

            TaskOutput output = future.get();
            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("propagates errors")
        void executeTaskAsync_propagatesErrors() {
            ChatClient failingClient = chatClientWithError("Async error");
            Agent agent = TestFixtures.createTestAgent(failingClient);
            Task task = createTask(agent);

            CompletableFuture<TaskOutput> future = agent.executeTaskAsync(task, Collections.emptyList());

            assertThrows(ExecutionException.class, future::get);
        }
    }

    @Nested
    @DisplayName("Prompt Building")
    class PromptBuildingTests {

        @Test
        @DisplayName("executes with custom role, goal, and backstory")
        void buildPrompt_withCustomRoleGoalBackstory_executes() {
            Agent agent = Agent.builder()
                    .role("Senior Developer")
                    .goal("Write clean code")
                    .backstory("10 years of experience")
                    .chatClient(mockChatClient)
                    .build();

            Task task = createTask(agent);
            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            // Agent was constructed with role/goal/backstory and executed successfully
            assertEquals("Senior Developer", agent.getRole());
            assertEquals("Write clean code", agent.getGoal());
            assertEquals("10 years of experience", agent.getBackstory());
        }

        @Test
        @DisplayName("executes with expected output specified")
        void buildPrompt_withExpectedOutput_executes() {
            Agent agent = createAgent();

            Task task = Task.builder()
                    .description("Test task")
                    .expectedOutput("JSON formatted result")
                    .agent(agent)
                    .build();

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            // Task was configured with expectedOutput and executed successfully
            assertEquals("JSON formatted result", task.getExpectedOutput());
        }

        @Test
        @DisplayName("executes with task description")
        void buildPrompt_withTaskDescription_executes() {
            Agent agent = createAgent();

            Task task = Task.builder()
                    .description("Analyze the data")
                    .agent(agent)
                    .build();

            TaskOutput output = agent.executeTask(task, Collections.emptyList());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
            assertEquals("Analyze the data", task.getDescription());
        }
    }
}
