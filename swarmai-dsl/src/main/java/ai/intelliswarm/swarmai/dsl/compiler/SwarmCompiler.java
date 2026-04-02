package ai.intelliswarm.swarmai.dsl.compiler;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.CompactionConfig;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.dsl.model.*;
import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.ApprovalPolicy;
import ai.intelliswarm.swarmai.governance.GateTrigger;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.CompositeProcess;
import ai.intelliswarm.swarmai.process.HierarchicalProcess;
import ai.intelliswarm.swarmai.process.IterativeProcess;
import ai.intelliswarm.swarmai.process.ParallelProcess;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.process.SelfImprovingProcess;
import ai.intelliswarm.swarmai.process.SequentialProcess;
import ai.intelliswarm.swarmai.process.SwarmCoordinator;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.*;

/**
 * Compiles a {@link SwarmDefinition} (parsed from YAML) into a live {@link Swarm} instance.
 *
 * <p>The compiler resolves agent references, wires task dependencies, looks up tools by name,
 * and assembles budget/governance configuration — bridging the declarative YAML world
 * to the imperative builder API.
 *
 * <pre>{@code
 * SwarmCompiler compiler = SwarmCompiler.builder()
 *     .chatClient(chatClient)
 *     .eventPublisher(eventPublisher)
 *     .tool("web-search", webSearchTool)
 *     .tool("file-read", fileReadTool)
 *     .build();
 *
 * SwarmDefinition definition = parser.parse(Path.of("workflow.yaml"));
 * Swarm swarm = compiler.compile(definition);
 * SwarmOutput output = swarm.kickoff(Map.of("topic", "AI Safety"));
 * }</pre>
 */
public class SwarmCompiler {

    private static final Logger logger = LoggerFactory.getLogger(SwarmCompiler.class);

    private final ChatClient defaultChatClient;
    private final Map<String, ChatClient> namedChatClients;
    private final Map<String, BaseTool> toolRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final Memory memory;
    private final Knowledge knowledge;

    private SwarmCompiler(Builder builder) {
        this.defaultChatClient = builder.defaultChatClient;
        this.namedChatClients = new HashMap<>(builder.namedChatClients);
        this.toolRegistry = new HashMap<>(builder.toolRegistry);
        this.eventPublisher = builder.eventPublisher;
        this.memory = builder.memory;
        this.knowledge = builder.knowledge;
    }

    /**
     * Compiles a SwarmDefinition into a ready-to-execute Swarm.
     * For COMPOSITE process types, use {@link #compileWorkflow(SwarmDefinition)} instead.
     *
     * @throws SwarmCompileException if the process type is COMPOSITE
     */
    public Swarm compile(SwarmDefinition definition) {
        ProcessType processType = ProcessType.valueOf(definition.getProcess());
        if (processType == ProcessType.COMPOSITE) {
            throw new SwarmCompileException(
                    "COMPOSITE process cannot be compiled to a plain Swarm. " +
                    "Use compileWorkflow() instead, which returns a CompiledWorkflow.");
        }

        CompilationContext ctx = buildContext(definition);
        return buildSwarm(definition, ctx);
    }

    /**
     * Compiles a SwarmDefinition into a {@link CompiledWorkflow} that supports all
     * process types, including COMPOSITE with multi-stage pipelines.
     */
    public CompiledWorkflow compileWorkflow(SwarmDefinition definition) {
        CompilationContext ctx = buildContext(definition);
        ProcessType processType = ProcessType.valueOf(definition.getProcess());

        if (processType == ProcessType.COMPOSITE) {
            return compileCompositeWorkflow(definition, ctx);
        }

        Swarm swarm = buildSwarm(definition, ctx);
        return CompiledWorkflow.fromSwarm(swarm);
    }

    private CompilationContext buildContext(SwarmDefinition definition) {
        // Load knowledge sources if defined
        Knowledge effectiveKnowledge = this.knowledge;
        if (definition.getKnowledgeSources() != null && !definition.getKnowledgeSources().isEmpty()) {
            ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge kb =
                    new ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge();
            for (KnowledgeSourceDefinition src : definition.getKnowledgeSources()) {
                kb.addSource(src.getId(), src.getContent(), null);
            }
            effectiveKnowledge = kb;
        }

        Map<String, Agent> agentMap = new LinkedHashMap<>();
        Knowledge finalKnowledge = effectiveKnowledge;
        definition.getAgents().forEach((agentId, agentDef) -> {
            Agent agent = compileAgent(agentId, agentDef, finalKnowledge);
            agentMap.put(agentId, agent);
        });

        List<Task> tasks = new ArrayList<>();
        definition.getTasks().forEach((taskId, taskDef) -> {
            Task task = compileTask(taskId, taskDef, agentMap);
            tasks.add(task);
        });

        return new CompilationContext(agentMap, tasks, effectiveKnowledge);
    }

    private Swarm buildSwarm(SwarmDefinition definition, CompilationContext ctx) {
        ProcessType processType = ProcessType.valueOf(definition.getProcess());

        Swarm.Builder swarmBuilder = Swarm.builder()
                .agents(new ArrayList<>(ctx.agentMap.values()))
                .tasks(ctx.tasks)
                .process(processType)
                .verbose(definition.isVerbose())
                .language(definition.getLanguage());

        if (definition.getName() != null) {
            swarmBuilder.id(definition.getName());
        }
        if (definition.getMaxRpm() != null) {
            swarmBuilder.maxRpm(definition.getMaxRpm());
        }
        if (definition.getTenantId() != null) {
            swarmBuilder.tenantId(definition.getTenantId());
        }
        if (definition.getManagerAgent() != null) {
            Agent manager = ctx.agentMap.get(definition.getManagerAgent());
            if (manager == null) {
                throw new SwarmCompileException(
                        "Manager agent '" + definition.getManagerAgent() + "' not found");
            }
            swarmBuilder.managerAgent(manager);
        }

        if (definition.getConfig() != null) {
            definition.getConfig().forEach(swarmBuilder::config);
        }
        if (definition.getBudget() != null) {
            BudgetPolicy policy = compileBudget(definition.getBudget());
            BudgetTracker tracker = new InMemoryBudgetTracker(policy);
            swarmBuilder.budgetPolicy(policy);
            swarmBuilder.budgetTracker(tracker);
        }
        if (definition.getGovernance() != null && definition.getGovernance().getApprovalGates() != null) {
            List<ApprovalGate> gates = definition.getGovernance().getApprovalGates().stream()
                    .map(this::compileApprovalGate)
                    .toList();
            swarmBuilder.approvalGates(gates);
        }
        if (eventPublisher != null) {
            swarmBuilder.eventPublisher(eventPublisher);
        }
        if (memory != null) {
            swarmBuilder.memory(memory);
        }
        Knowledge effectiveKnowledge = ctx.knowledge != null ? ctx.knowledge : knowledge;
        if (effectiveKnowledge != null) {
            swarmBuilder.knowledge(effectiveKnowledge);
        }

        Swarm swarm = swarmBuilder.build();

        logger.info("Compiled swarm: id={}, agents={}, tasks={}, process={}",
                swarm.getId(), swarm.getAgents().size(), swarm.getTasks().size(), processType);

        return swarm;
    }

    private CompiledWorkflow compileCompositeWorkflow(SwarmDefinition definition, CompilationContext ctx) {
        List<Agent> agents = new ArrayList<>(ctx.agentMap.values());

        List<ai.intelliswarm.swarmai.process.Process> stages = new ArrayList<>();
        for (int i = 0; i < definition.getStages().size(); i++) {
            StageDefinition stageDef = definition.getStages().get(i);
            ai.intelliswarm.swarmai.process.Process stage = compileStage(stageDef, agents, ctx.agentMap, i);
            stages.add(stage);
        }

        CompositeProcess composite = CompositeProcess.of(stages);
        String swarmId = definition.getName() != null ? definition.getName() : UUID.randomUUID().toString();

        logger.info("Compiled composite workflow: id={}, stages={}, agents={}, tasks={}",
                swarmId, stages.size(), agents.size(), ctx.tasks.size());

        return CompiledWorkflow.fromComposite(composite, ctx.tasks, swarmId);
    }

    private ai.intelliswarm.swarmai.process.Process compileStage(StageDefinition stageDef, List<Agent> agents,
                                 Map<String, Agent> agentMap, int stageIndex) {
        ProcessType stageType = ProcessType.valueOf(stageDef.getProcess());

        Agent managerAgent = null;
        if (stageDef.getManagerAgent() != null) {
            managerAgent = agentMap.get(stageDef.getManagerAgent());
            if (managerAgent == null) {
                throw new SwarmCompileException(
                        "Stage " + stageIndex + " references unknown manager agent '" + stageDef.getManagerAgent() + "'");
            }
        }

        int maxIter = stageDef.getMaxIterations() != null ? stageDef.getMaxIterations() : 3;
        String criteria = stageDef.getQualityCriteria();

        return switch (stageType) {
            case SEQUENTIAL -> new SequentialProcess(agents, eventPublisher);
            case PARALLEL -> new ParallelProcess(agents, eventPublisher);
            case HIERARCHICAL -> {
                if (managerAgent == null) {
                    throw new SwarmCompileException("Stage " + stageIndex + " (HIERARCHICAL) requires a managerAgent");
                }
                yield new HierarchicalProcess(agents, managerAgent, eventPublisher);
            }
            case ITERATIVE -> {
                if (managerAgent == null) {
                    throw new SwarmCompileException("Stage " + stageIndex + " (ITERATIVE) requires a managerAgent");
                }
                yield new IterativeProcess(agents, managerAgent, eventPublisher, maxIter, criteria);
            }
            case SELF_IMPROVING -> {
                if (managerAgent == null) {
                    throw new SwarmCompileException("Stage " + stageIndex + " (SELF_IMPROVING) requires a managerAgent");
                }
                yield new SelfImprovingProcess(agents, managerAgent, eventPublisher, maxIter, criteria);
            }
            case SWARM -> {
                if (managerAgent == null) {
                    throw new SwarmCompileException("Stage " + stageIndex + " (SWARM) requires a managerAgent");
                }
                int maxParallel = stageDef.getMaxParallelAgents() != null ? stageDef.getMaxParallelAgents() : 5;
                yield new SwarmCoordinator(agents, managerAgent, eventPublisher, maxIter, maxParallel, criteria);
            }
            case COMPOSITE -> throw new SwarmCompileException("Stage " + stageIndex + " cannot be COMPOSITE (no nesting)");
        };
    }

    private record CompilationContext(Map<String, Agent> agentMap, List<Task> tasks, Knowledge knowledge) {}

    private Agent compileAgent(String agentId, AgentDefinition def, Knowledge effectiveKnowledge) {
        ChatClient chatClient = resolveChatClient(def.getModel());

        Agent.Builder builder = Agent.builder()
                .id(agentId)
                .role(def.getRole())
                .goal(def.getGoal())
                .backstory(def.getBackstory())
                .chatClient(chatClient)
                .verbose(def.isVerbose())
                .allowDelegation(def.isAllowDelegation());

        if (def.getModel() != null) {
            builder.modelName(def.getModel());
        }
        if (def.getMaxTurns() != null) {
            builder.maxTurns(def.getMaxTurns());
        }
        if (def.getTemperature() != null) {
            builder.temperature(def.getTemperature());
        }
        if (def.getMaxExecutionTime() != null) {
            builder.maxExecutionTime(def.getMaxExecutionTime());
        }
        if (def.getMaxIter() != null) {
            builder.maxIter(def.getMaxIter());
        }
        if (def.getMaxRpm() != null) {
            builder.maxRpm(def.getMaxRpm());
        }
        if (def.getPermissionMode() != null) {
            builder.permissionMode(PermissionLevel.valueOf(def.getPermissionMode()));
        }

        // Compaction config
        if (def.getCompaction() != null) {
            CompactionConfigDefinition cc = def.getCompaction();
            if (!cc.isEnabled()) {
                builder.compactionConfig(CompactionConfig.disabled());
            } else {
                int preserve = cc.getPreserveRecentTurns() != null ? cc.getPreserveRecentTurns() : 4;
                long threshold = cc.getThresholdTokens() != null ? cc.getThresholdTokens() : 100_000L;
                builder.compactionConfig(CompactionConfig.of(preserve, threshold));
            }
        }

        // Agent-level memory
        if (def.isMemory()) {
            builder.memory(memory != null ? memory : new ai.intelliswarm.swarmai.memory.InMemoryMemory());
        }

        // Agent-level knowledge
        if (def.isKnowledge() && effectiveKnowledge != null) {
            builder.knowledge(effectiveKnowledge);
        }

        // Resolve tools by name
        List<BaseTool> tools = resolveTools(def.getTools(), "agent '" + agentId + "'");
        builder.tools(tools);

        return builder.build();
    }

    private Task compileTask(String taskId, TaskDefinition def, Map<String, Agent> agentMap) {
        Task.Builder builder = Task.builder()
                .id(taskId)
                .description(def.getDescription())
                .asyncExecution(def.isAsyncExecution());

        if (def.getExpectedOutput() != null) {
            builder.expectedOutput(def.getExpectedOutput());
        }
        if (def.getAgent() != null) {
            Agent agent = agentMap.get(def.getAgent());
            if (agent == null) {
                throw new SwarmCompileException(
                        "Task '" + taskId + "' references unknown agent '" + def.getAgent() + "'");
            }
            builder.agent(agent);
        }
        if (def.getOutputFormat() != null) {
            builder.outputFormat(OutputFormat.valueOf(def.getOutputFormat()));
        }
        if (def.getOutputFile() != null) {
            builder.outputFile(def.getOutputFile());
        }
        if (def.getMaxExecutionTime() != null) {
            builder.maxExecutionTime(def.getMaxExecutionTime());
        }

        // Dependencies
        for (String dep : def.getDependsOn()) {
            builder.dependsOn(dep);
        }

        // Task-level tools
        List<BaseTool> tools = resolveTools(def.getTools(), "task '" + taskId + "'");
        builder.tools(tools);

        return builder.build();
    }

    private BudgetPolicy compileBudget(BudgetDefinition def) {
        BudgetPolicy.Builder builder = BudgetPolicy.builder();

        if (def.getMaxTokens() != null) {
            builder.maxTotalTokens(def.getMaxTokens());
        }
        if (def.getMaxCostUsd() != null) {
            builder.maxCostUsd(def.getMaxCostUsd());
        }
        if (def.getOnExceeded() != null) {
            builder.onExceeded(BudgetPolicy.BudgetAction.valueOf(def.getOnExceeded()));
        }
        if (def.getWarningThresholdPercent() != null) {
            builder.warningThresholdPercent(def.getWarningThresholdPercent());
        }

        return builder.build();
    }

    private ApprovalGate compileApprovalGate(ApprovalGateDefinition def) {
        ApprovalGate.Builder builder = ApprovalGate.builder()
                .name(def.getName())
                .description(def.getDescription());

        if (def.getTrigger() != null) {
            builder.trigger(GateTrigger.valueOf(def.getTrigger()));
        }
        if (def.getTimeoutMinutes() != null) {
            builder.timeout(Duration.ofMinutes(def.getTimeoutMinutes()));
        }

        // Approval policy
        if (def.getPolicy() != null) {
            ApprovalPolicyDefinition pd = def.getPolicy();
            int required = pd.getRequiredApprovals() != null ? pd.getRequiredApprovals() : 1;
            List<String> roles = pd.getApproverRoles() != null ? pd.getApproverRoles() : List.of();
            boolean autoApprove = pd.getAutoApproveOnTimeout() != null && pd.getAutoApproveOnTimeout();
            builder.policy(new ApprovalPolicy(required, roles, autoApprove));
        }

        return builder.build();
    }

    private ChatClient resolveChatClient(String modelName) {
        if (modelName != null && namedChatClients.containsKey(modelName)) {
            return namedChatClients.get(modelName);
        }
        if (defaultChatClient == null) {
            throw new SwarmCompileException(
                    "No ChatClient available. Provide a default ChatClient or register one for model: " + modelName);
        }
        return defaultChatClient;
    }

    private List<BaseTool> resolveTools(List<String> toolNames, String context) {
        List<BaseTool> tools = new ArrayList<>();
        for (String toolName : toolNames) {
            BaseTool tool = toolRegistry.get(toolName);
            if (tool == null) {
                throw new SwarmCompileException(
                        context + " references unknown tool '" + toolName + "'. " +
                        "Register it via SwarmCompiler.builder().tool(\"" + toolName + "\", toolInstance)");
            }
            tools.add(tool);
        }
        return tools;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatClient defaultChatClient;
        private final Map<String, ChatClient> namedChatClients = new HashMap<>();
        private final Map<String, BaseTool> toolRegistry = new HashMap<>();
        private ApplicationEventPublisher eventPublisher;
        private Memory memory;
        private Knowledge knowledge;

        public Builder chatClient(ChatClient chatClient) {
            this.defaultChatClient = chatClient;
            return this;
        }

        public Builder chatClient(String modelName, ChatClient chatClient) {
            this.namedChatClients.put(modelName, chatClient);
            return this;
        }

        public Builder tool(String name, BaseTool tool) {
            this.toolRegistry.put(name, tool);
            return this;
        }

        public Builder tools(Map<String, BaseTool> tools) {
            this.toolRegistry.putAll(tools);
            return this;
        }

        public Builder eventPublisher(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        public Builder knowledge(Knowledge knowledge) {
            this.knowledge = knowledge;
            return this;
        }

        public SwarmCompiler build() {
            return new SwarmCompiler(this);
        }
    }
}
