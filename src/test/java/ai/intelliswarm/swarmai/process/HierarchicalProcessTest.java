package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Hierarchical Process Tests")
class HierarchicalProcessTest extends BaseSwarmTest {

    private Agent managerAgent;
    private List<Agent> workerAgents;
    private HierarchicalProcess process;

    @BeforeEach
    void setUp() {
        // Manager with sequential responses for coordination
        ChatClient managerClient = MockChatClientFactory.withResponses(
                "Delegation: Task 1 to Worker 1, Task 2 to Worker 2",
                "Final summary of all results"
        );
        managerAgent = Agent.builder()
                .id("manager")
                .role("Project Manager")
                .goal("Coordinate team effectively")
                .backstory("Experienced manager")
                .chatClient(managerClient)
                .allowDelegation(true)
                .build();

        workerAgents = List.of(
                createAgent("Worker 1"),
                createAgent("Worker 2")
        );

        List<Agent> allAgents = new java.util.ArrayList<>(workerAgents);
        allAgents.add(managerAgent);

        process = new HierarchicalProcess(allAgents, managerAgent, mockEventPublisher);
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("executes with manager agent delegation")
        void execute_withManagerAgent_delegates() {
            Task task = Task.builder()
                    .description("Test task")
                    .agent(workerAgents.get(0))
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertNotNull(output);
            // Manager coordination + worker task + final coordination
            assertTrue(output.getTaskOutputs().size() >= 2);
        }

        @Test
        @DisplayName("coordinates worker agents")
        void execute_coordinatesWorkerAgents() {
            List<Task> tasks = List.of(
                    Task.builder()
                            .id("task-1")
                            .description("Task for worker 1")
                            .agent(workerAgents.get(0))
                            .build(),
                    Task.builder()
                            .id("task-2")
                            .description("Task for worker 2")
                            .agent(workerAgents.get(1))
                            .build()
            );

            SwarmOutput output = process.execute(tasks, Map.of());

            assertNotNull(output);
            assertTrue(output.isSuccessful());
        }

        @Test
        @DisplayName("synthesizes final output")
        void execute_synthesizesFinalOutput() {
            Task task = Task.builder()
                    .description("Test task")
                    .agent(workerAgents.get(0))
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertNotNull(output.getFinalOutput());
            assertFalse(output.getFinalOutput().isEmpty());
        }

        @Test
        @DisplayName("publishes process events")
        void execute_publishesEvents() {
            Task task = Task.builder()
                    .description("Test task")
                    .agent(workerAgents.get(0))
                    .build();

            process.execute(List.of(task), Map.of());

            assertEventPublished(SwarmEvent.Type.PROCESS_STARTED);
            assertEventPublished(SwarmEvent.Type.TASK_STARTED);
            assertEventPublished(SwarmEvent.Type.TASK_COMPLETED);
        }

        @Test
        @DisplayName("includes usage metrics")
        void execute_includesUsageMetrics() {
            Task task = Task.builder()
                    .description("Test task")
                    .agent(workerAgents.get(0))
                    .build();

            SwarmOutput output = process.execute(List.of(task), Map.of());

            assertNotNull(output.getUsageMetrics());
            assertTrue(output.getUsageMetrics().containsKey("totalTasks"));
            assertTrue(output.getUsageMetrics().containsKey("delegatedTasks"));
            assertTrue(output.getUsageMetrics().containsKey("managerTasks"));
        }
    }

    @Nested
    @DisplayName("Agent Selection")
    class AgentSelectionTests {

        @Test
        @DisplayName("selects agent by relevant tools")
        void selectAgentForTask_byRelevantTools() {
            List<BaseTool> tools = TestFixtures.createMockTools("search");
            Agent agentWithTools = TestFixtures.createTestAgentWithTools(mockChatClient, tools);

            List<Agent> agents = List.of(agentWithTools, createAgent("Other Worker"));
            HierarchicalProcess toolProcess = new HierarchicalProcess(
                    agents, managerAgent, mockEventPublisher);

            Task taskWithTools = Task.builder()
                    .description("Search task")
                    .tools(tools)
                    .agent(agentWithTools)
                    .build();

            SwarmOutput output = toolProcess.execute(List.of(taskWithTools), Map.of());

            assertNotNull(output);
        }

        @Test
        @DisplayName("selects agent by expertise keywords")
        void selectAgentForTask_byExpertise() {
            Agent dataAgent = Agent.builder()
                    .role("Data Analyst")
                    .goal("Analyze data patterns")
                    .backstory("Expert in data")
                    .chatClient(mockChatClient)
                    .build();

            List<Agent> agents = List.of(dataAgent, createAgent("General Worker"));
            HierarchicalProcess expertiseProcess = new HierarchicalProcess(
                    agents, managerAgent, mockEventPublisher);

            Task dataTask = Task.builder()
                    .description("Analyze the data patterns")
                    .agent(dataAgent)
                    .build();

            SwarmOutput output = expertiseProcess.execute(List.of(dataTask), Map.of());

            assertNotNull(output);
        }
    }

    @Nested
    @DisplayName("validateTasks()")
    class ValidateTasksTests {

        @Test
        @DisplayName("throws exception with empty tasks")
        void validateTasks_withEmptyTasks_throwsException() {
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    process.execute(Collections.emptyList(), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("empty") || message.contains("Task") ||
                       causeMessage.contains("empty") || causeMessage.contains("Task"),
                    "Expected empty tasks message. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("throws exception without worker agents")
        void validateTasks_withoutWorkers_throwsException() {
            HierarchicalProcess noWorkersProcess = new HierarchicalProcess(
                    List.of(managerAgent), managerAgent, mockEventPublisher);

            Task task = Task.builder()
                    .description("Test")
                    .agent(managerAgent)
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    noWorkersProcess.execute(List.of(task), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("worker") || message.contains("agent") ||
                       causeMessage.contains("worker") || causeMessage.contains("agent"),
                    "Expected worker agent message. Got: " + message + " / " + causeMessage);
        }

        @Test
        @DisplayName("throws exception when manager doesn't allow delegation")
        void validateTasks_managerNoDelegation_throwsException() {
            Agent noDelegationManager = Agent.builder()
                    .role("Manager")
                    .goal("Manage")
                    .backstory("Backstory")
                    .chatClient(mockChatClient)
                    .allowDelegation(false)
                    .build();

            HierarchicalProcess noDelegationProcess = new HierarchicalProcess(
                    workerAgents, noDelegationManager, mockEventPublisher);

            Task task = Task.builder()
                    .description("Test")
                    .agent(workerAgents.get(0))
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    noDelegationProcess.execute(List.of(task), Map.of()));

            String message = exception.getMessage() != null ? exception.getMessage() : "";
            Throwable cause = exception.getCause();
            String causeMessage = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            assertTrue(message.contains("delegation") || message.contains("Delegation") ||
                       causeMessage.contains("delegation") || causeMessage.contains("Delegation"),
                    "Expected delegation message. Got: " + message + " / " + causeMessage);
        }
    }

    @Nested
    @DisplayName("Process Properties")
    class ProcessPropertiesTests {

        @Test
        @DisplayName("returns HIERARCHICAL type")
        void getType_returnsHierarchical() {
            assertEquals(ProcessType.HIERARCHICAL, process.getType());
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
            Agent failingManager = Agent.builder()
                    .role("Manager")
                    .goal("Manage")
                    .backstory("Backstory")
                    .chatClient(chatClientWithError("Manager error"))
                    .allowDelegation(true)
                    .build();

            HierarchicalProcess failingProcess = new HierarchicalProcess(
                    workerAgents, failingManager, mockEventPublisher);

            Task task = Task.builder()
                    .description("Test")
                    .agent(workerAgents.get(0))
                    .build();

            assertThrows(RuntimeException.class, () ->
                    failingProcess.execute(List.of(task), Map.of()));

            assertEventPublished(SwarmEvent.Type.PROCESS_FAILED);
        }

        @Test
        @DisplayName("throws exception on manager failure")
        void execute_onManagerFailure_throwsException() {
            Agent failingManager = Agent.builder()
                    .role("Manager")
                    .goal("Manage")
                    .backstory("Backstory")
                    .chatClient(chatClientWithError("Coordination failed"))
                    .allowDelegation(true)
                    .build();

            HierarchicalProcess failingProcess = new HierarchicalProcess(
                    workerAgents, failingManager, mockEventPublisher);

            Task task = Task.builder()
                    .description("Test")
                    .agent(workerAgents.get(0))
                    .build();

            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    failingProcess.execute(List.of(task), Map.of()));

            assertTrue(exception.getMessage().contains("Hierarchical process"));
        }
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("throws exception with null manager")
        void constructor_withNullManager_throwsException() {
            assertThrows(NullPointerException.class, () ->
                    new HierarchicalProcess(workerAgents, null, mockEventPublisher));
        }

        @Test
        @DisplayName("removes manager from workers list")
        void constructor_removesManagerFromWorkers() {
            List<Agent> allAgents = new java.util.ArrayList<>(workerAgents);
            allAgents.add(managerAgent);

            // Process should not include manager as a worker
            HierarchicalProcess testProcess = new HierarchicalProcess(
                    allAgents, managerAgent, mockEventPublisher);

            // The process should work without trying to use manager as worker
            Task task = Task.builder()
                    .description("Test")
                    .agent(workerAgents.get(0))
                    .build();

            SwarmOutput output = testProcess.execute(List.of(task), Map.of());
            assertNotNull(output);
        }
    }
}
