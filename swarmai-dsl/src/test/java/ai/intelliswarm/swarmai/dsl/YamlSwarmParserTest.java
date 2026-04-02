package ai.intelliswarm.swarmai.dsl;

import ai.intelliswarm.swarmai.dsl.model.SwarmDefinition;
import ai.intelliswarm.swarmai.dsl.parser.SwarmParseException;
import ai.intelliswarm.swarmai.dsl.parser.YamlSwarmParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YamlSwarmParserTest {

    private YamlSwarmParser parser;

    @BeforeEach
    void setUp() {
        parser = new YamlSwarmParser();
    }

    @Test
    void parseSimpleSequentialWorkflow() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/simple-sequential.yaml");

        assertEquals("Simple Research", def.getName());
        assertEquals("SEQUENTIAL", def.getProcess());
        assertTrue(def.isVerbose());
        assertEquals(2, def.getAgents().size());
        assertEquals(2, def.getTasks().size());

        // Verify agent details
        var researcher = def.getAgents().get("researcher");
        assertNotNull(researcher);
        assertEquals("Senior Researcher", researcher.getRole());
        assertEquals("Find comprehensive information", researcher.getGoal());

        var writer = def.getAgents().get("writer");
        assertNotNull(writer);
        assertEquals("Technical Writer", writer.getRole());

        // Verify task details
        var researchTask = def.getTasks().get("research");
        assertNotNull(researchTask);
        assertEquals("researcher", researchTask.getAgent());
        assertTrue(researchTask.getDependsOn().isEmpty());

        var reportTask = def.getTasks().get("report");
        assertNotNull(reportTask);
        assertEquals("writer", reportTask.getAgent());
        assertEquals(1, reportTask.getDependsOn().size());
        assertEquals("research", reportTask.getDependsOn().get(0));
        assertEquals("MARKDOWN", reportTask.getOutputFormat());
    }

    @Test
    void parseTemplateVariableSubstitution() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/template-workflow.yaml",
                Map.of(
                        "workflowName", "AI Safety Analysis",
                        "maxTokens", 50000,
                        "role", "AI Safety Analyst",
                        "topic", "AI alignment"
                ));

        assertEquals("AI Safety Analysis", def.getName());
        assertEquals(50000L, def.getBudget().getMaxTokens());

        var analyst = def.getAgents().get("analyst");
        assertEquals("AI Safety Analyst", analyst.getRole());
        assertEquals("Analyze AI alignment in depth", analyst.getGoal());
        assertTrue(analyst.getBackstory().contains("AI alignment"));

        var task = def.getTasks().get("analyze");
        assertTrue(task.getDescription().contains("AI alignment"));
    }

    @Test
    void parseFullFeaturedWorkflow() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/full-featured.yaml");

        assertEquals("Enterprise Pipeline", def.getName());
        assertEquals("HIERARCHICAL", def.getProcess());
        assertEquals("en", def.getLanguage());
        assertEquals(30, def.getMaxRpm());
        assertEquals("tenant-acme", def.getTenantId());
        assertEquals("manager", def.getManagerAgent());

        // Budget
        assertNotNull(def.getBudget());
        assertEquals(200000L, def.getBudget().getMaxTokens());
        assertEquals(10.0, def.getBudget().getMaxCostUsd());
        assertEquals("HARD_STOP", def.getBudget().getOnExceeded());
        assertEquals(75.0, def.getBudget().getWarningThresholdPercent());

        // Agents
        assertEquals(3, def.getAgents().size());
        var manager = def.getAgents().get("manager");
        assertEquals(5, manager.getMaxTurns());
        assertEquals(0.3, manager.getTemperature());

        var researcher = def.getAgents().get("researcher");
        assertEquals(1, researcher.getTools().size());
        assertEquals("web-search", researcher.getTools().get(0));

        var analyst = def.getAgents().get("analyst");
        assertEquals("READ_ONLY", analyst.getPermissionMode());

        // Tasks with dependencies
        var analyzeTask = def.getTasks().get("analyze");
        assertEquals(1, analyzeTask.getDependsOn().size());
        assertEquals("gather", analyzeTask.getDependsOn().get(0));

        var synthesizeTask = def.getTasks().get("synthesize");
        assertEquals("output/report.md", synthesizeTask.getOutputFile());
        assertEquals("MARKDOWN", synthesizeTask.getOutputFormat());

        // Governance
        assertNotNull(def.getGovernance());
        assertEquals(1, def.getGovernance().getApprovalGates().size());
        var gate = def.getGovernance().getApprovalGates().get(0);
        assertEquals("Quality Review", gate.getName());
        assertEquals("AFTER_TASK", gate.getTrigger());
        assertEquals(15, gate.getTimeoutMinutes());

        // Config
        assertEquals(5, def.getConfig().get("maxIterations"));
    }

    @Test
    void parseInlineYaml() throws IOException {
        String yaml = """
                swarm:
                  process: PARALLEL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Do work"
                      backstory: "A capable worker"
                  tasks:
                    task1:
                      description: "First task"
                      agent: worker
                """;

        SwarmDefinition def = parser.parseString(yaml);
        assertEquals("PARALLEL", def.getProcess());
        assertEquals(1, def.getAgents().size());
        assertEquals(1, def.getTasks().size());
    }

    @Test
    void failOnMissingSwarmRoot() {
        String yaml = """
                agents:
                  worker:
                    role: "Worker"
                """;

        assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
    }

    @Test
    void failOnMissingAgents() {
        assertThrows(SwarmParseException.class,
                () -> parser.parseResource("workflows/invalid-missing-agents.yaml"));
    }

    @Test
    void failOnBadAgentReference() {
        assertThrows(SwarmParseException.class,
                () -> parser.parseResource("workflows/invalid-bad-reference.yaml"));
    }

    @Test
    void failOnInvalidProcessType() {
        String yaml = """
                swarm:
                  process: NONEXISTENT
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
    }

    @Test
    void unresolvedTemplateVariablesAreLeftAsIs() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/template-workflow.yaml",
                Map.of("workflowName", "Test", "maxTokens", 1000, "role", "Tester"));
        // {{topic}} was not provided — should remain as literal text
        var task = def.getTasks().get("analyze");
        assertTrue(task.getDescription().contains("{{topic}}"));
    }

    @Test
    void preservesTaskOrdering() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/full-featured.yaml");
        var taskIds = def.getTasks().keySet().stream().toList();
        assertEquals("gather", taskIds.get(0));
        assertEquals("analyze", taskIds.get(1));
        assertEquals("synthesize", taskIds.get(2));
    }

    @Test
    void parseCompositePipeline() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/composite-pipeline.yaml");

        assertEquals("COMPOSITE", def.getProcess());
        assertEquals(3, def.getStages().size());

        assertEquals("PARALLEL", def.getStages().get(0).getProcess());
        assertNull(def.getStages().get(0).getManagerAgent());

        assertEquals("HIERARCHICAL", def.getStages().get(1).getProcess());
        assertEquals("manager", def.getStages().get(1).getManagerAgent());

        assertEquals("ITERATIVE", def.getStages().get(2).getProcess());
        assertEquals(3, def.getStages().get(2).getMaxIterations());
        assertEquals("Report must be comprehensive", def.getStages().get(2).getQualityCriteria());
    }

    @Test
    void parseCompactionConfig() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/composite-pipeline.yaml");

        var researcher = def.getAgents().get("researcher");
        assertNotNull(researcher.getCompaction());
        assertTrue(researcher.getCompaction().isEnabled());
        assertEquals(6, researcher.getCompaction().getPreserveRecentTurns());
        assertEquals(50000L, researcher.getCompaction().getThresholdTokens());
    }

    @Test
    void parseApprovalPolicy() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/composite-pipeline.yaml");

        var gate = def.getGovernance().getApprovalGates().get(0);
        assertNotNull(gate.getPolicy());
        assertEquals(2, gate.getPolicy().getRequiredApprovals());
        assertEquals(2, gate.getPolicy().getApproverRoles().size());
        assertTrue(gate.getPolicy().getApproverRoles().contains("security-lead"));
        assertTrue(gate.getPolicy().getApproverRoles().contains("tech-lead"));
        assertFalse(gate.getPolicy().getAutoApproveOnTimeout());
    }

    @Test
    void failOnCompositeWithoutStages() {
        String yaml = """
                swarm:
                  process: COMPOSITE
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("stages"));
    }

    @Test
    void failOnNestedCompositeStage() {
        String yaml = """
                swarm:
                  process: COMPOSITE
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                  stages:
                    - process: COMPOSITE
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("COMPOSITE"));
    }

    // ==================== ToolHooks Tests ====================

    @Test
    void parseToolHooks() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/toolhooks-workflow.yaml");

        var researcher = def.getAgents().get("researcher");
        assertEquals(4, researcher.getToolHooks().size());
        assertEquals("audit", researcher.getToolHooks().get(0).getType());
        assertEquals("sanitize", researcher.getToolHooks().get(1).getType());
        assertEquals(2, researcher.getToolHooks().get(1).getPatterns().size());
        assertEquals("rate-limit", researcher.getToolHooks().get(2).getType());
        assertEquals(10, researcher.getToolHooks().get(2).getMaxCalls());
        assertEquals(30, researcher.getToolHooks().get(2).getWindowSeconds());
        assertEquals("deny", researcher.getToolHooks().get(3).getType());
        assertEquals(2, researcher.getToolHooks().get(3).getTools().size());

        var writer = def.getAgents().get("writer");
        assertEquals(1, writer.getToolHooks().size());
        assertEquals("audit", writer.getToolHooks().get(0).getType());
    }

    @Test
    void failOnUnknownToolHookType() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                      toolHooks:
                        - type: nonexistent
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    void failOnSanitizeWithoutPatterns() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                      toolHooks:
                        - type: sanitize
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("patterns"));
    }

    @Test
    void failOnRateLimitWithoutMaxCalls() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                      toolHooks:
                        - type: rate-limit
                          windowSeconds: 30
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("maxCalls"));
    }

    @Test
    void failOnDenyWithoutTools() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                      toolHooks:
                        - type: deny
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("tools"));
    }

    @Test
    void failOnCustomWithoutClass() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                      toolHooks:
                        - type: custom
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("class"));
    }

    // ==================== Graph Tests ====================

    @Test
    void parseGraphDebateWorkflow() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/graph-debate.yaml");

        assertNotNull(def.getGraph());
        assertEquals(3, def.getGraph().getNodes().size());
        assertEquals(4, def.getGraph().getEdges().size());

        // State channels
        assertNotNull(def.getState());
        assertTrue(def.getState().getChannels().size() >= 3);
        assertEquals("counter", def.getState().getChannels().get("round").getType());
        assertEquals("stringAppender", def.getState().getChannels().get("debate_log").getType());
        assertEquals("lastWriteWins", def.getState().getChannels().get("verdict").getType());

        // Graph nodes
        var proponent = def.getGraph().getNodes().get("proponent");
        assertEquals("proponent", proponent.getAgent());
        assertTrue(proponent.getTask().contains("FOR the proposition"));

        // Conditional edge
        var conditionalEdge = def.getGraph().getEdges().stream()
                .filter(e -> "opponent".equals(e.getFrom()) && e.isConditional())
                .findFirst().orElseThrow();
        assertEquals(2, conditionalEdge.getConditional().size());
        assertEquals("round < 3", conditionalEdge.getConditional().get(0).getWhen());
        assertEquals("proponent", conditionalEdge.getConditional().get(0).getTo());
        assertTrue(conditionalEdge.getConditional().get(1).isDefault());
        assertEquals("judge", conditionalEdge.getConditional().get(1).target());
    }

    @Test
    void parseGraphEvaluatorWorkflow() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/graph-evaluator.yaml");

        assertNotNull(def.getGraph());
        assertEquals(3, def.getGraph().getNodes().size());
        assertEquals(4, def.getGraph().getEdges().size());

        // Conditional with multiple conditions + default
        var evalEdge = def.getGraph().getEdges().stream()
                .filter(e -> "evaluate".equals(e.getFrom()))
                .findFirst().orElseThrow();
        assertTrue(evalEdge.isConditional());
        assertEquals(3, evalEdge.getConditional().size());
        assertEquals("score >= 80", evalEdge.getConditional().get(0).getWhen());
        assertEquals("iteration >= 3", evalEdge.getConditional().get(1).getWhen());
        assertTrue(evalEdge.getConditional().get(2).isDefault());
    }

    @Test
    void graphWorkflowAllowsEmptyTasks() throws IOException {
        // Graph workflows don't require a tasks: section
        SwarmDefinition def = parser.parseResource("workflows/graph-debate.yaml");
        assertTrue(def.getTasks().isEmpty());
    }

    @Test
    void failOnGraphWithoutStartEdge() {
        String yaml = """
                swarm:
                  name: "Bad Graph"
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  graph:
                    nodes:
                      step1:
                        agent: worker
                        task: "Do something"
                    edges:
                      - from: step1
                        to: END
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("START"));
    }

    @Test
    void failOnGraphNodeReferencingUnknownAgent() {
        String yaml = """
                swarm:
                  name: "Bad Graph"
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  graph:
                    nodes:
                      step1:
                        agent: nonexistent
                        task: "Do something"
                    edges:
                      - from: START
                        to: step1
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    void failOnEdgeReferencingUnknownNode() {
        String yaml = """
                swarm:
                  name: "Bad Graph"
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  graph:
                    nodes:
                      step1:
                        agent: worker
                        task: "Do something"
                    edges:
                      - from: START
                        to: nonexistent
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    // ==================== Workflow Hooks & Task Conditions Tests ====================

    @Test
    void parseWorkflowHooks() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/hooks-workflow.yaml");

        assertEquals(3, def.getHooks().size());
        assertEquals("BEFORE_WORKFLOW", def.getHooks().get(0).getPoint());
        assertEquals("log", def.getHooks().get(0).getType());
        assertEquals("Starting hooked workflow", def.getHooks().get(0).getMessage());
        assertEquals("AFTER_TASK", def.getHooks().get(1).getPoint());
        assertEquals("AFTER_WORKFLOW", def.getHooks().get(2).getPoint());
    }

    @Test
    void parseTaskCondition() throws IOException {
        SwarmDefinition def = parser.parseResource("workflows/condition-workflow.yaml");

        var riskReport = def.getTasks().get("risk-report");
        assertEquals("contains('risk')", riskReport.getCondition());

        var summary = def.getTasks().get("summary");
        assertNull(summary.getCondition());
    }

    @Test
    void failOnInvalidHookPoint() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                  hooks:
                    - point: INVALID_POINT
                      type: log
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("INVALID_POINT"));
    }

    @Test
    void failOnInvalidHookType() {
        String yaml = """
                swarm:
                  process: SEQUENTIAL
                  agents:
                    worker:
                      role: "Worker"
                      goal: "Work"
                      backstory: "Worker"
                  tasks:
                    task1:
                      description: "Do"
                      agent: worker
                  hooks:
                    - point: BEFORE_WORKFLOW
                      type: nonexistent
                """;

        SwarmParseException ex = assertThrows(SwarmParseException.class, () -> parser.parseString(yaml));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }
}
