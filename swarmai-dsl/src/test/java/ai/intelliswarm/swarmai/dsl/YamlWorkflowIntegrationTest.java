package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.compiler.CompiledWorkflow;
import ai.intelliswarm.swarmai.dsl.compiler.SwarmCompiler;
import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test that loads and compiles every YAML workflow file
 * to verify the DSL parser and compiler handle all configurations correctly.
 * Covers all 30 example workflows + DSL test fixtures.
 */
class YamlWorkflowIntegrationTest {

    private YamlSwarmParser parser;
    private SwarmCompiler compiler;

    private static final Map<String, Object> TEMPLATE_VARS = Map.ofEntries(
            Map.entry("workflowName", "Test"),
            Map.entry("maxTokens", 100000),
            Map.entry("role", "Tester"),
            Map.entry("topic", "AI Safety"),
            Map.entry("ticker", "AAPL"),
            Map.entry("company", "Acme"),
            Map.entry("industry", "Technology"),
            Map.entry("sector", "Technology"),
            Map.entry("target", "192.168.1.0/24"),
            Map.entry("targetNetwork", "192.168.1.0/24"),
            Map.entry("dataPath", "/data/sample.csv"),
            Map.entry("language", "en"),
            Map.entry("project", "Test Project"),
            Map.entry("outputDir", "output"),
            Map.entry("pipelineName", "Test Pipeline"),
            Map.entry("query", "How does AI work?")
    );

    @BeforeEach
    void setUp() {
        parser = new YamlSwarmParser();

        // Create mock ChatClient
        AssistantMessage message = new AssistantMessage("Mock response");
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
        ChatClient chatClient = mock(ChatClient.class, Mockito.RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().system(anyString()).user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().user(anyString())
                .call().chatResponse()).thenReturn(chatResponse);
        when(chatClient.prompt().user(anyString())
                .toolNames(any(String[].class)).call().chatResponse()).thenReturn(chatResponse);

        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        // Create mock tools for all tool names used across examples
        String[] toolNames = {
            "web-search", "http-request", "web-scrape", "calculator", "file-read", "file-write",
            "csv-analysis", "json-transform", "xml-parse", "shell-command", "code-execution",
            "directory-read", "sec-filings", "headless-browser"
        };

        SwarmCompiler.Builder compilerBuilder = SwarmCompiler.builder()
                .chatClient(chatClient)
                .eventPublisher(eventPublisher);

        for (String toolName : toolNames) {
            BaseTool mockTool = mock(BaseTool.class);
            when(mockTool.getFunctionName()).thenReturn(toolName);
            when(mockTool.getDescription()).thenReturn("Mock " + toolName);
            when(mockTool.getParameterSchema()).thenReturn(Map.of());
            compilerBuilder.tool(toolName, mockTool);
        }

        compiler = compilerBuilder.build();
    }

    // ==================== Standard workflows → Swarm ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "workflows/basics.yaml",
        "workflows/agent-testing.yaml",
        "workflows/audited-research.yaml",
        "workflows/data-pipeline.yaml",
        "workflows/enterprise.yaml",
        "workflows/iterative-investment.yaml",
        "workflows/rag-research.yaml",
        "workflows/streaming.yaml",
        "workflows/secureops.yaml",
        "workflows/memory-persistence.yaml",
        "workflows/multilanguage.yaml",
        "workflows/multiprovider.yaml",
        "workflows/stock-analysis.yaml",
        "workflows/metrics.yaml",
        "workflows/visualization.yaml",
        "workflows/governed-pipeline.yaml",
        "workflows/scheduled-monitoring.yaml",
        "workflows/condition-workflow.yaml",
        "workflows/toolhooks-workflow.yaml",
        "workflows/simple-sequential.yaml",
        "workflows/full-featured.yaml",
        "workflows/template-workflow.yaml"
    })
    void parseAndCompileStandardWorkflows(String resource) throws IOException {
        SwarmDefinition def = parser.parseResource(resource, TEMPLATE_VARS);
        Swarm swarm = compiler.compile(def);
        assertNotNull(swarm, "Failed to compile: " + resource);
        assertFalse(swarm.getAgents().isEmpty(), "No agents in: " + resource);
    }

    // ==================== Parallel/Hierarchical/Swarm/Iterative → Swarm ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "workflows/due-diligence.yaml",
        "workflows/codebase-analysis.yaml",
        "workflows/web-research.yaml",
        "workflows/self-improving.yaml",
        "workflows/competitive-swarm.yaml",
        "workflows/investment-swarm.yaml",
        "workflows/pentest.yaml",
        "workflows/research-pipeline.yaml"
    })
    void parseAndCompileAdvancedWorkflows(String resource) throws IOException {
        SwarmDefinition def = parser.parseResource(resource, TEMPLATE_VARS);
        Swarm swarm = compiler.compile(def);
        assertNotNull(swarm, "Failed to compile: " + resource);
        assertFalse(swarm.getAgents().isEmpty(), "No agents in: " + resource);
    }

    // ==================== Composite workflows ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "workflows/composite-pipeline.yaml",
        "workflows/composite-analysis.yaml"
    })
    void parseAndCompileCompositeWorkflows(String resource) throws IOException {
        SwarmDefinition def = parser.parseResource(resource, TEMPLATE_VARS);
        CompiledWorkflow workflow = compiler.compileWorkflow(def);
        assertNotNull(workflow, "Failed to compile: " + resource);
        assertTrue(workflow.isComposite(), "Not composite: " + resource);
        assertTrue(workflow.getStageCount() > 1, "Should have multiple stages: " + resource);
    }

    // ==================== Graph workflows ====================

    @ParameterizedTest
    @ValueSource(strings = {
        "workflows/graph-debate.yaml",
        "workflows/graph-customer-support.yaml",
        "workflows/graph-human-loop.yaml",
        "workflows/graph-evaluator.yaml",
        "workflows/hooks-workflow.yaml"
    })
    void parseAndCompileGraphWorkflows(String resource) throws IOException {
        SwarmDefinition def = parser.parseResource(resource, TEMPLATE_VARS);
        CompiledWorkflow workflow = compiler.compileWorkflow(def);
        assertNotNull(workflow, "Failed to compile: " + resource);
        assertTrue(workflow.isGraph(), "Not graph: " + resource);
    }

    // ==================== Feature-specific assertions ====================

    @Test
    void auditedResearchHasToolHooks() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/audited-research.yaml", TEMPLATE_VARS);
        Swarm swarm = compiler.compile(def);
        var researcher = swarm.getAgents().stream()
                .filter(a -> a.getId().equals("researcher"))
                .findFirst().orElseThrow();
        assertTrue(researcher.getToolHooks().size() >= 2,
                "Researcher should have audit + sanitize + rate-limit hooks");
    }

    @Test
    void enterpriseHasWorkflowHooks() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/enterprise.yaml", TEMPLATE_VARS);
        assertFalse(def.getHooks().isEmpty(), "Enterprise should have workflow hooks");
    }

    @Test
    void governedPipelineHasTaskCondition() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/governed-pipeline.yaml", TEMPLATE_VARS);
        var publishTask = def.getTasks().get("publish");
        assertNotNull(publishTask.getCondition(), "Publish task should have a condition");
    }

    @Test
    void graphDebateHasConditionalEdges() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/graph-debate.yaml");
        var conditionalEdge = def.getGraph().getEdges().stream()
                .filter(e -> e.isConditional())
                .findFirst().orElseThrow();
        assertEquals("round < 3", conditionalEdge.getConditional().get(0).getWhen());
    }

    @Test
    void graphCustomerSupportHasCategoryRouting() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/graph-customer-support.yaml");
        var classifyEdge = def.getGraph().getEdges().stream()
                .filter(e -> "classify".equals(e.getFrom()) && e.isConditional())
                .findFirst().orElseThrow();
        assertTrue(classifyEdge.getConditional().size() >= 4,
                "Should have BILLING, TECHNICAL, ACCOUNT, + default routes");
    }

    @Test
    void graphHumanLoopHasQualityGate() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/graph-human-loop.yaml");
        var reviewEdge = def.getGraph().getEdges().stream()
                .filter(e -> "review".equals(e.getFrom()) && e.isConditional())
                .findFirst().orElseThrow();
        assertTrue(reviewEdge.getConditional().stream()
                .anyMatch(c -> c.getWhen() != null && c.getWhen().contains("quality_score")));
    }

    @Test
    void dueDiligenceHasWorkflowHooks() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/due-diligence.yaml", TEMPLATE_VARS);
        assertFalse(def.getHooks().isEmpty(), "Due diligence should have audit trail hooks");
    }
}
