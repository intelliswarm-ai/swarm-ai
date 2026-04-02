package ai.intelliswarm.swarmai.dsl.compiler;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.budget.InMemoryBudgetTracker;
import ai.intelliswarm.swarmai.dsl.model.*;
import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.GateTrigger;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
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
     */
    public Swarm compile(SwarmDefinition definition) {
        // 1. Build agents
        Map<String, Agent> agentMap = new LinkedHashMap<>();
        definition.getAgents().forEach((agentId, agentDef) -> {
            Agent agent = compileAgent(agentId, agentDef);
            agentMap.put(agentId, agent);
        });

        // 2. Build tasks
        List<Task> tasks = new ArrayList<>();
        definition.getTasks().forEach((taskId, taskDef) -> {
            Task task = compileTask(taskId, taskDef, agentMap);
            tasks.add(task);
        });

        // 3. Build swarm
        ProcessType processType = ProcessType.valueOf(definition.getProcess());

        Swarm.Builder swarmBuilder = Swarm.builder()
                .agents(new ArrayList<>(agentMap.values()))
                .tasks(tasks)
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
            Agent manager = agentMap.get(definition.getManagerAgent());
            if (manager == null) {
                throw new SwarmCompileException(
                        "Manager agent '" + definition.getManagerAgent() + "' not found");
            }
            swarmBuilder.managerAgent(manager);
        }

        // 4. Config map
        if (definition.getConfig() != null) {
            definition.getConfig().forEach(swarmBuilder::config);
        }

        // 5. Budget
        if (definition.getBudget() != null) {
            BudgetPolicy policy = compileBudget(definition.getBudget());
            BudgetTracker tracker = new InMemoryBudgetTracker(policy);
            swarmBuilder.budgetPolicy(policy);
            swarmBuilder.budgetTracker(tracker);
        }

        // 6. Governance
        if (definition.getGovernance() != null && definition.getGovernance().getApprovalGates() != null) {
            List<ApprovalGate> gates = definition.getGovernance().getApprovalGates().stream()
                    .map(this::compileApprovalGate)
                    .toList();
            swarmBuilder.approvalGates(gates);
        }

        // 7. Infrastructure
        if (eventPublisher != null) {
            swarmBuilder.eventPublisher(eventPublisher);
        }
        if (memory != null) {
            swarmBuilder.memory(memory);
        }
        if (knowledge != null) {
            swarmBuilder.knowledge(knowledge);
        }

        Swarm swarm = swarmBuilder.build();

        logger.info("Compiled swarm: id={}, agents={}, tasks={}, process={}",
                swarm.getId(), swarm.getAgents().size(), swarm.getTasks().size(), processType);

        return swarm;
    }

    private Agent compileAgent(String agentId, AgentDefinition def) {
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
