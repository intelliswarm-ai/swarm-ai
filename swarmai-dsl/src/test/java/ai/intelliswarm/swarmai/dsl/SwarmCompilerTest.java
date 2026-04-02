package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompileException;
import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler;
import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SwarmCompilerTest {

    private YamlSwarmParser parser;
    private ChatClient mockChatClient;
    private ApplicationEventPublisher mockEventPublisher;

    @BeforeEach
    void setUp() {
        parser = new YamlSwarmParser();
        mockEventPublisher = mock(ApplicationEventPublisher.class);
        mockChatClient = createMockChatClient("Mock response");
    }

    private static ChatClient createMockChatClient(String response) {
        AssistantMessage message = new AssistantMessage(response);
        Generation generation = new Generation(message);

        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(50);
        when(usage.getTotalTokens()).thenReturn(150);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getResult()).thenReturn(generation);
        when(chatResponse.getMetadata()).thenReturn(metadata);

        ChatClient mockClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(mockClient.prompt().system(anyString()).user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);
        when(mockClient.prompt().user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(mockClient.prompt().user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);
        return mockClient;
    }

    @Test
    void compileSimpleSequentialWorkflow() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/simple-sequential.yaml");

        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(mockChatClient)
                .eventPublisher(mockEventPublisher)
                .build();

        Swarm swarm = compiler.compile(def);

        assertEquals("Simple Research", swarm.getId());
        assertEquals(ProcessType.SEQUENTIAL, swarm.getProcessType());
        assertTrue(swarm.isVerbose());
        assertEquals(2, swarm.getAgents().size());
        assertEquals(2, swarm.getTasks().size());

        // Verify agents were built correctly
        var agents = swarm.getAgents();
        assertEquals("researcher", agents.get(0).getId());
        assertEquals("Senior Researcher", agents.get(0).getRole());
        assertEquals("writer", agents.get(1).getId());

        // Verify task dependencies
        var tasks = swarm.getTasks();
        assertTrue(tasks.get(0).getDependencyTaskIds().isEmpty());
        assertEquals(1, tasks.get(1).getDependencyTaskIds().size());
        assertEquals("research", tasks.get(1).getDependencyTaskIds().get(0));
    }

    @Test
    void compileFullFeaturedWorkflow() throws IOException {
        // Create a mock tool for "web-search"
        BaseTool webSearchTool = mock(BaseTool.class);
        when(webSearchTool.getFunctionName()).thenReturn("web-search");
        when(webSearchTool.getDescription()).thenReturn("Search the web");
        when(webSearchTool.getParameterSchema()).thenReturn(Map.of());

        SwarmDefinition def = parser.parseResource("workflows/full-featured.yaml");

        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(mockChatClient)
                .eventPublisher(mockEventPublisher)
                .tool("web-search", webSearchTool)
                .build();

        Swarm swarm = compiler.compile(def);

        assertEquals("Enterprise Pipeline", swarm.getId());
        assertEquals(ProcessType.HIERARCHICAL, swarm.getProcessType());
        assertEquals("en", swarm.getLanguage());
        assertEquals(30, swarm.getMaxRpm());
        assertNotNull(swarm.getManagerAgent());
        assertEquals("manager", swarm.getManagerAgent().getId());
        assertEquals(3, swarm.getAgents().size());
        assertEquals(3, swarm.getTasks().size());

        // Verify budget was compiled
        assertNotNull(swarm.getBudgetTracker());

        // Verify config was passed through
        var config = swarm.getConfig();
        assertEquals(5, config.get("maxIterations"));

        // Verify researcher has tools
        var researcher = swarm.getAgents().stream()
                .filter(a -> a.getId().equals("researcher"))
                .findFirst().orElseThrow();
        assertEquals(1, researcher.getTools().size());
    }

    @Test
    void compileWithTemplateVariables() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/template-workflow.yaml",
                Map.of(
                        "workflowName", "Climate Analysis",
                        "maxTokens", 50000,
                        "role", "Climate Scientist",
                        "topic", "climate change"
                ));

        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(mockChatClient)
                .eventPublisher(mockEventPublisher)
                .build();

        Swarm swarm = compiler.compile(def);

        assertEquals("Climate Analysis", swarm.getId());
        var agent = swarm.getAgents().get(0);
        assertEquals("Climate Scientist", agent.getRole());
        assertTrue(agent.getGoal().contains("climate change"));
    }

    @Test
    void failOnMissingChatClient() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/simple-sequential.yaml");

        SwarmCompiler compiler = SwarmCompiler.builder().build();

        assertThrows(SwarmCompileException.class, () -> compiler.compile(def));
    }

    @Test
    void failOnMissingTool() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/full-featured.yaml");

        // Don't register the "web-search" tool
        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(mockChatClient)
                .eventPublisher(mockEventPublisher)
                .build();

        SwarmCompileException ex = assertThrows(SwarmCompileException.class,
                () -> compiler.compile(def));
        assertTrue(ex.getMessage().contains("web-search"));
    }

    @Test
    void compileInlineYaml() throws IOException {
        String yaml = """
                swarm:
                  process: PARALLEL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Do work efficiently"
                      backstory: "An efficient worker"
                  tasks:
                    task1:
                      description: "Process data"
                      agent: worker
                    task2:
                      description: "Generate report"
                      agent: worker
                """;

        SwarmDefinition def = parser.parseString(yaml);
        SwarmCompiler compiler = SwarmCompiler.builder()
                .chatClient(mockChatClient)
                .eventPublisher(mockEventPublisher)
                .build();

        Swarm swarm = compiler.compile(def);

        assertEquals(ProcessType.PARALLEL, swarm.getProcessType());
        assertEquals(1, swarm.getAgents().size());
        assertEquals(2, swarm.getTasks().size());
    }
}
