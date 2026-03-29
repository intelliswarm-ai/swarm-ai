package ai.intelliswarm.swarmai.base;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Factory class providing pre-built test objects for SwarmAI tests.
 * Reduces boilerplate and ensures consistent test data across test classes.
 */
public class TestFixtures {

    // Default test values
    public static final String DEFAULT_AGENT_ROLE = "Test Agent";
    public static final String DEFAULT_AGENT_GOAL = "Complete test tasks efficiently";
    public static final String DEFAULT_AGENT_BACKSTORY = "An agent created for testing purposes";
    public static final String DEFAULT_TASK_DESCRIPTION = "Execute the test task";
    public static final String DEFAULT_EXPECTED_OUTPUT = "Task completed successfully";
    public static final String DEFAULT_RESPONSE = "This is a test response from the AI model";

    // ============================================
    // Agent Builders
    // ============================================

    /**
     * Creates a basic test agent with default values.
     */
    public static Agent createTestAgent(ChatClient chatClient) {
        return Agent.builder()
                .role(DEFAULT_AGENT_ROLE)
                .goal(DEFAULT_AGENT_GOAL)
                .backstory(DEFAULT_AGENT_BACKSTORY)
                .chatClient(chatClient)
                .build();
    }

    /**
     * Creates a test agent with a specific role.
     */
    public static Agent createTestAgent(String role, ChatClient chatClient) {
        return Agent.builder()
                .role(role)
                .goal("Goal for " + role)
                .backstory("Backstory for " + role)
                .chatClient(chatClient)
                .build();
    }

    /**
     * Creates a test agent with a specific ID.
     */
    public static Agent createTestAgentWithId(String id, ChatClient chatClient) {
        return Agent.builder()
                .id(id)
                .role(DEFAULT_AGENT_ROLE)
                .goal(DEFAULT_AGENT_GOAL)
                .backstory(DEFAULT_AGENT_BACKSTORY)
                .chatClient(chatClient)
                .build();
    }

    /**
     * Creates a test agent with tools.
     */
    public static Agent createTestAgentWithTools(ChatClient chatClient, List<BaseTool> tools) {
        return Agent.builder()
                .role(DEFAULT_AGENT_ROLE)
                .goal(DEFAULT_AGENT_GOAL)
                .backstory(DEFAULT_AGENT_BACKSTORY)
                .chatClient(chatClient)
                .tools(tools)
                .build();
    }

    /**
     * Creates a manager agent for hierarchical processes.
     */
    public static Agent createManagerAgent(ChatClient chatClient) {
        return Agent.builder()
                .role("Project Manager")
                .goal("Coordinate team and delegate tasks effectively")
                .backstory("Experienced project manager with expertise in AI orchestration")
                .chatClient(chatClient)
                .allowDelegation(true)
                .build();
    }

    /**
     * Creates multiple test agents with unique roles.
     */
    public static List<Agent> createTestAgents(int count, ChatClient chatClient) {
        List<Agent> agents = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            agents.add(Agent.builder()
                    .id("agent-" + i)
                    .role("Test Agent " + i)
                    .goal("Goal for agent " + i)
                    .backstory("Backstory for agent " + i)
                    .chatClient(chatClient)
                    .build());
        }
        return agents;
    }

    // ============================================
    // Task Builders
    // ============================================

    /**
     * Creates a basic test task with an agent.
     */
    public static Task createTestTask(Agent agent) {
        return Task.builder()
                .description(DEFAULT_TASK_DESCRIPTION)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .build();
    }

    /**
     * Creates a test task with a specific description.
     */
    public static Task createTestTask(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("Output for: " + description)
                .agent(agent)
                .build();
    }

    /**
     * Creates a test task with a specific ID.
     */
    public static Task createTestTaskWithId(String id, Agent agent) {
        return Task.builder()
                .id(id)
                .description(DEFAULT_TASK_DESCRIPTION)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .build();
    }

    /**
     * Creates a test task without an agent (for testing error handling).
     */
    public static Task createTestTaskWithoutAgent() {
        return Task.builder()
                .description(DEFAULT_TASK_DESCRIPTION)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .build();
    }

    /**
     * Creates a test task with dependencies.
     */
    public static Task createTestTaskWithDependency(Agent agent, Task dependency) {
        return Task.builder()
                .description("Task depending on " + dependency.getId())
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .dependsOn(dependency)
                .build();
    }

    /**
     * Creates a test task with a condition.
     */
    public static Task createTestTaskWithCondition(Agent agent, java.util.function.Predicate<String> condition) {
        return Task.builder()
                .description(DEFAULT_TASK_DESCRIPTION)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .condition(condition)
                .build();
    }

    /**
     * Creates an async test task.
     */
    public static Task createAsyncTestTask(Agent agent) {
        return Task.builder()
                .description("Async " + DEFAULT_TASK_DESCRIPTION)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .asyncExecution(true)
                .build();
    }

    /**
     * Creates multiple test tasks.
     */
    public static List<Task> createTestTasks(int count, Agent agent) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            tasks.add(Task.builder()
                    .id("task-" + i)
                    .description("Test task " + i)
                    .expectedOutput("Output for task " + i)
                    .agent(agent)
                    .build());
        }
        return tasks;
    }

    /**
     * Creates a chain of dependent tasks.
     */
    public static List<Task> createDependentTaskChain(int count, Agent agent) {
        List<Task> tasks = new ArrayList<>();
        Task previous = null;

        for (int i = 1; i <= count; i++) {
            Task.Builder builder = Task.builder()
                    .id("task-" + i)
                    .description("Task " + i + " in chain")
                    .expectedOutput("Output for task " + i)
                    .agent(agent);

            if (previous != null) {
                builder.dependsOn(previous);
            }

            Task task = builder.build();
            tasks.add(task);
            previous = task;
        }

        return tasks;
    }

    // ============================================
    // TaskOutput Builders
    // ============================================

    /**
     * Creates a successful task output.
     */
    public static TaskOutput createSuccessfulTaskOutput(String taskId, String agentId) {
        return TaskOutput.builder()
                .taskId(taskId)
                .agentId(agentId)
                .rawOutput(DEFAULT_RESPONSE)
                .description(DEFAULT_TASK_DESCRIPTION)
                .summary("Task completed successfully")
                .format(OutputFormat.TEXT)
                .executionTimeMs(100L)
                .build();
    }

    /**
     * Creates a task output with custom content.
     */
    public static TaskOutput createTaskOutput(String taskId, String agentId, String content) {
        return TaskOutput.builder()
                .taskId(taskId)
                .agentId(agentId)
                .rawOutput(content)
                .description(DEFAULT_TASK_DESCRIPTION)
                .summary(content.length() > 100 ? content.substring(0, 97) + "..." : content)
                .format(OutputFormat.TEXT)
                .executionTimeMs(100L)
                .build();
    }

    /**
     * Creates multiple task outputs.
     */
    public static List<TaskOutput> createTaskOutputs(int count) {
        List<TaskOutput> outputs = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            outputs.add(TaskOutput.builder()
                    .taskId("task-" + i)
                    .agentId("agent-" + i)
                    .rawOutput("Output " + i)
                    .description("Task " + i)
                    .summary("Summary " + i)
                    .format(OutputFormat.TEXT)
                    .executionTimeMs(100L * i)
                    .build());
        }
        return outputs;
    }

    // ============================================
    // Swarm Builders
    // ============================================

    /**
     * Creates a basic sequential swarm with one agent and one task.
     */
    public static Swarm createSimpleSwarm(ChatClient chatClient) {
        Agent agent = createTestAgent(chatClient);
        Task task = createTestTask(agent);

        return Swarm.builder()
                .agent(agent)
                .task(task)
                .process(ProcessType.SEQUENTIAL)
                .build();
    }

    /**
     * Creates a swarm with multiple agents and tasks.
     */
    public static Swarm createTestSwarm(List<Agent> agents, List<Task> tasks) {
        Swarm.Builder builder = Swarm.builder()
                .process(ProcessType.SEQUENTIAL);

        agents.forEach(builder::agent);
        tasks.forEach(builder::task);

        return builder.build();
    }

    /**
     * Creates a swarm with an event publisher.
     */
    public static Swarm createTestSwarmWithPublisher(List<Agent> agents, List<Task> tasks,
                                                      ApplicationEventPublisher publisher) {
        Swarm.Builder builder = Swarm.builder()
                .process(ProcessType.SEQUENTIAL)
                .eventPublisher(publisher);

        agents.forEach(builder::agent);
        tasks.forEach(builder::task);

        return builder.build();
    }

    /**
     * Creates a hierarchical swarm with a manager agent.
     */
    public static Swarm createHierarchicalSwarm(ChatClient chatClient, int workerCount) {
        Agent manager = createManagerAgent(chatClient);
        List<Agent> workers = createTestAgents(workerCount, chatClient);

        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i <= workerCount; i++) {
            tasks.add(Task.builder()
                    .id("task-" + i)
                    .description("Worker task " + i)
                    .expectedOutput("Output for worker task " + i)
                    .agent(workers.get(i - 1))
                    .build());
        }

        Swarm.Builder builder = Swarm.builder()
                .process(ProcessType.HIERARCHICAL)
                .managerAgent(manager)
                .agent(manager);

        workers.forEach(builder::agent);
        tasks.forEach(builder::task);

        return builder.build();
    }

    // ============================================
    // Mock Tool
    // ============================================

    /**
     * Creates a mock BaseTool for testing.
     */
    public static BaseTool createMockTool(String name) {
        BaseTool tool = mock(BaseTool.class);
        org.mockito.Mockito.when(tool.getFunctionName()).thenReturn(name);
        org.mockito.Mockito.when(tool.getDescription()).thenReturn("Mock tool: " + name);
        org.mockito.Mockito.when(tool.getParameterSchema()).thenReturn(Map.of());
        org.mockito.Mockito.when(tool.isAsync()).thenReturn(false);
        org.mockito.Mockito.when(tool.execute(org.mockito.ArgumentMatchers.anyMap())).thenReturn("Mock result");
        return tool;
    }

    /**
     * Creates multiple mock tools.
     */
    public static List<BaseTool> createMockTools(String... names) {
        List<BaseTool> tools = new ArrayList<>();
        for (String name : names) {
            tools.add(createMockTool(name));
        }
        return tools;
    }

    // ============================================
    // Event Publisher
    // ============================================

    /**
     * Creates a mock ApplicationEventPublisher.
     */
    public static ApplicationEventPublisher createMockEventPublisher() {
        return mock(ApplicationEventPublisher.class);
    }
}
