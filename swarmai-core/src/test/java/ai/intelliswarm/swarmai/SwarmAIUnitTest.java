package ai.intelliswarm.swarmai;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SwarmAIUnitTest {

    @Test
    void testAgentBuilder() {
        ChatClient mockChatClient = mock(ChatClient.class);
        
        Agent agent = Agent.builder()
            .role("Test Agent")
            .goal("Test the agent builder")
            .backstory("This is a test agent")
            .chatClient(mockChatClient)
            .verbose(true)
            .allowDelegation(true)
            .maxRpm(10)
            .temperature(0.7)
            .maxExecutionTime(30000)
            .build();

        assertEquals("Test Agent", agent.getRole());
        assertEquals("Test the agent builder", agent.getGoal());
        assertEquals("This is a test agent", agent.getBackstory());
        assertTrue(agent.isVerbose());
        assertTrue(agent.isAllowDelegation());
        assertEquals(10, agent.getMaxRpm());
        assertEquals(0.7, agent.getTemperature());
        assertEquals(30000, agent.getMaxExecutionTime());
        assertNotNull(agent.getId());
        assertNotNull(agent.getCreatedAt());
        assertEquals(0, agent.getExecutionCount());
    }

    @Test
    void testTaskBuilder() {
        ChatClient mockChatClient = mock(ChatClient.class);
        
        Agent agent = Agent.builder()
            .role("Test Agent")
            .goal("Test goal")
            .backstory("Test backstory")
            .chatClient(mockChatClient)
            .build();

        Task task = Task.builder()
            .description("Test task description")
            .expectedOutput("Expected test output")
            .agent(agent)
            .asyncExecution(true)
            .outputFormat(OutputFormat.JSON)
            .maxExecutionTime(60000)
            .context("key1", "value1")
            .context("key2", "value2")
            .build();

        assertEquals("Test task description", task.getDescription());
        assertEquals("Expected test output", task.getExpectedOutput());
        assertEquals(agent, task.getAgent());
        assertTrue(task.isAsyncExecution());
        assertEquals(OutputFormat.JSON, task.getOutputFormat());
        assertEquals(60000, task.getMaxExecutionTime());
        assertEquals(2, task.getContext().size());
        assertEquals("value1", task.getContext().get("key1"));
        assertNotNull(task.getId());
        assertNotNull(task.getCreatedAt());
        assertEquals(Task.TaskStatus.PENDING, task.getStatus());
    }

    @Test
    void testTaskDependencies() {
        ChatClient mockChatClient = mock(ChatClient.class);
        
        Agent agent = Agent.builder()
            .role("Test Agent")
            .goal("Test goal")
            .backstory("Test backstory")
            .chatClient(mockChatClient)
            .build();

        Task task1 = Task.builder()
            .description("First task")
            .agent(agent)
            .build();

        Task task2 = Task.builder()
            .description("Second task")
            .agent(agent)
            .dependsOn(task1)
            .build();

        Task task3 = Task.builder()
            .description("Third task")
            .agent(agent)
            .dependsOn("manual-task-id")
            .build();

        assertTrue(task1.getDependencyTaskIds().isEmpty());
        assertEquals(1, task2.getDependencyTaskIds().size());
        assertEquals(task1.getId(), task2.getDependencyTaskIds().get(0));
        assertEquals(1, task3.getDependencyTaskIds().size());
        assertEquals("manual-task-id", task3.getDependencyTaskIds().get(0));
    }

    @Test
    void testTaskOutput() {
        TaskOutput output = TaskOutput.builder()
            .taskId("task-123")
            .agentId("agent-456")
            .rawOutput("This is the raw output from the task")
            .description("Test task")
            .summary("Test summary")
            .format(OutputFormat.TEXT)
            .metadata("executionTime", 1500L)
            .metadata("tokens", 100)
            .executionTimeMs(1500L)
            .build();

        assertEquals("task-123", output.getTaskId());
        assertEquals("agent-456", output.getAgentId());
        assertEquals("This is the raw output from the task", output.getRawOutput());
        assertEquals("Test task", output.getDescription());
        assertEquals("Test summary", output.getSummary());
        assertEquals(OutputFormat.TEXT, output.getFormat());
        assertEquals(1500L, output.getExecutionTimeMs());
        assertTrue(output.isSuccessful());
        assertNotNull(output.getTimestamp());
        assertEquals(2, output.getMetadata().size());
        assertEquals(1500L, output.getMetadata().get("executionTime"));
        assertEquals(100, output.getMetadata().get("tokens"));
    }

    @Test
    void testSwarmBuilder() {
        ChatClient mockChatClient = mock(ChatClient.class);
        ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
        
        Agent agent1 = Agent.builder()
            .role("Agent 1")
            .goal("Goal 1")
            .backstory("Backstory 1")
            .chatClient(mockChatClient)
            .build();

        Agent agent2 = Agent.builder()
            .role("Agent 2")
            .goal("Goal 2")
            .backstory("Backstory 2")
            .chatClient(mockChatClient)
            .build();

        Task task1 = Task.builder()
            .description("Task 1")
            .agent(agent1)
            .build();

        Task task2 = Task.builder()
            .description("Task 2")
            .agent(agent2)
            .build();

        Swarm swarm = Swarm.builder()
            .id("test-swarm-123")
            .agent(agent1)
            .agent(agent2)
            .task(task1)
            .task(task2)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .maxRpm(25)
            .language("en")
            .config("custom-param", "custom-value")
            .eventPublisher(mockPublisher)
            .build();

        assertEquals("test-swarm-123", swarm.getId());
        assertEquals(2, swarm.getAgents().size());
        assertEquals(2, swarm.getTasks().size());
        assertEquals(ProcessType.SEQUENTIAL, swarm.getProcessType());
        assertTrue(swarm.isVerbose());
        assertEquals(25, swarm.getMaxRpm());
        assertEquals("en", swarm.getLanguage());
        assertEquals("custom-value", swarm.getConfig().get("custom-param"));
        assertNotNull(swarm.getCreatedAt());
        assertEquals(Swarm.SwarmStatus.READY, swarm.getStatus());
    }

    @Test
    void testSwarmEvent() {
        Object source = new Object();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("executionTime", 1000L);
        metadata.put("taskCount", 5);

        SwarmEvent event = new SwarmEvent(source, SwarmEvent.Type.SWARM_STARTED, 
            "Swarm execution started", "swarm-123", metadata);

        assertEquals(SwarmEvent.Type.SWARM_STARTED, event.getType());
        assertEquals("Swarm execution started", event.getMessage());
        assertEquals("swarm-123", event.getSwarmId());
        assertEquals(source, event.getSource());
        assertNotNull(event.getEventTime());
        assertEquals(2, event.getMetadata().size());
        assertEquals(1000L, event.getMetadata().get("executionTime"));
        assertEquals(5, event.getMetadata().get("taskCount"));
    }

    @Test
    void testAgentEqualsAndHashCode() {
        ChatClient mockChatClient = mock(ChatClient.class);
        
        Agent agent1 = Agent.builder()
            .id("agent-123")
            .role("Test Agent")
            .goal("Test goal")
            .backstory("Test backstory")
            .chatClient(mockChatClient)
            .build();

        Agent agent2 = Agent.builder()
            .id("agent-123") // Same ID
            .role("Different Role")
            .goal("Different goal")
            .backstory("Different backstory")
            .chatClient(mockChatClient)
            .build();

        Agent agent3 = Agent.builder()
            .id("agent-456") // Different ID
            .role("Test Agent")
            .goal("Test goal")
            .backstory("Test backstory")
            .chatClient(mockChatClient)
            .build();

        assertEquals(agent1, agent2); // Same ID
        assertNotEquals(agent1, agent3); // Different ID
        assertEquals(agent1.hashCode(), agent2.hashCode()); // Same ID
        assertNotEquals(agent1.hashCode(), agent3.hashCode()); // Different ID
    }

    @Test
    void testTaskEqualsAndHashCode() {
        Task task1 = Task.builder()
            .id("task-123")
            .description("Test task")
            .build();

        Task task2 = Task.builder()
            .id("task-123") // Same ID
            .description("Different description")
            .build();

        Task task3 = Task.builder()
            .id("task-456") // Different ID
            .description("Test task")
            .build();

        assertEquals(task1, task2); // Same ID
        assertNotEquals(task1, task3); // Different ID
        assertEquals(task1.hashCode(), task2.hashCode()); // Same ID
        assertNotEquals(task1.hashCode(), task3.hashCode()); // Different ID
    }
}