package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.GovernanceInterceptor;
import ai.intelliswarm.swarmai.governance.WorkflowGovernanceEngine;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Immutable, validated, executable swarm configuration.
 * Produced by {@link SwarmGraph#compile()} after validation passes.
 *
 * <p>This class is frozen — all agents, tasks, and configuration are immutable.
 * Execution uses the type-safe {@link AgentState} for inputs.
 *
 * <p>Usage:
 * <pre>{@code
 * CompiledSwarm swarm = SwarmGraph.create()
 *     .addAgent(agent)
 *     .addTask(task)
 *     .compile()
 *     .compiled();
 *
 * // Type-safe execution
 * SwarmOutput output = swarm.kickoff(AgentState.of(Map.of("topic", "AI")));
 *
 * // Backward-compatible execution
 * SwarmOutput output = swarm.kickoff(Map.of("topic", "AI"));
 * }</pre>
 */
public final class CompiledSwarm implements SwarmDefinition {

    private static final Logger logger = LoggerFactory.getLogger(CompiledSwarm.class);

    private final String id;
    private final List<Agent> agents;
    private final List<Task> tasks;
    private final ProcessType processType;
    private final Agent managerAgent;
    private final boolean verbose;
    private final Memory memory;
    private final Knowledge knowledge;
    private final Map<String, Object> config;
    private final Integer maxRpm;
    private final String language;
    private final String tenantId;
    private final ApplicationEventPublisher eventPublisher;
    private final BudgetTracker budgetTracker;
    private final BudgetPolicy budgetPolicy;
    private final WorkflowGovernanceEngine governance;
    private final List<ApprovalGate> approvalGates;
    private final TenantQuotaEnforcer tenantQuotaEnforcer;
    private final StateSchema stateSchema;
    private final LocalDateTime compiledAt;

    CompiledSwarm(
            String id,
            List<Agent> agents,
            List<Task> tasks,
            ProcessType processType,
            Agent managerAgent,
            boolean verbose,
            Memory memory,
            Knowledge knowledge,
            Map<String, Object> config,
            Integer maxRpm,
            String language,
            String tenantId,
            ApplicationEventPublisher eventPublisher,
            BudgetTracker budgetTracker,
            BudgetPolicy budgetPolicy,
            WorkflowGovernanceEngine governance,
            List<ApprovalGate> approvalGates,
            TenantQuotaEnforcer tenantQuotaEnforcer,
            StateSchema stateSchema) {
        this.id = id;
        this.agents = agents;
        this.tasks = tasks;
        this.processType = processType;
        this.managerAgent = managerAgent;
        this.verbose = verbose;
        this.memory = memory;
        this.knowledge = knowledge;
        this.config = config;
        this.maxRpm = maxRpm;
        this.language = language;
        this.tenantId = tenantId;
        this.eventPublisher = eventPublisher;
        this.budgetTracker = budgetTracker;
        this.budgetPolicy = budgetPolicy;
        this.governance = governance;
        this.approvalGates = approvalGates;
        this.tenantQuotaEnforcer = tenantQuotaEnforcer;
        this.stateSchema = stateSchema;
        this.compiledAt = LocalDateTime.now();
    }

    // ========================================
    // Execution with AgentState (new API)
    // ========================================

    /**
     * Executes the compiled swarm with type-safe state.
     *
     * @param state the initial agent state
     * @return the execution output
     */
    public SwarmOutput kickoff(AgentState state) {
        ObservabilityContext ctx = ObservabilityContext.create()
                .withSwarmId(this.id);

        if (tenantId != null) {
            ctx.withTenantId(tenantId);
        }

        if (tenantQuotaEnforcer != null && tenantId != null) {
            tenantQuotaEnforcer.checkWorkflowQuota(tenantId);
            tenantQuotaEnforcer.recordWorkflowStart(tenantId);
        }

        if (budgetTracker != null && budgetPolicy != null) {
            budgetTracker.setBudgetPolicy(this.id, budgetPolicy);
        }

        publishEvent(SwarmEvent.Type.SWARM_STARTED, "Swarm kickoff initiated");

        // Convert AgentState to Map for backward compatibility with Process implementations
        Map<String, Object> inputs = state != null ? new HashMap<>(state.data()) : new HashMap<>();

        try {
            // Reset tasks for re-execution
            tasks.forEach(Task::reset);

            // Inject budget tracker into inputs (backward compat with process implementations)
            if (budgetTracker != null) {
                inputs.put("__budgetTracker", budgetTracker);
                inputs.put("__budgetSwarmId", this.id);
            }

            Process process = createProcess();

            if (governance != null && !approvalGates.isEmpty()) {
                process = new GovernanceInterceptor(process, governance, approvalGates);
            }

            SwarmOutput output = process.execute(tasks, inputs, this.id);

            if (budgetTracker != null) {
                BudgetSnapshot snapshot = budgetTracker.getSnapshot(this.id);
                if (snapshot != null) {
                    logger.info("Workflow {} budget: {} tokens used, ${} estimated cost",
                            this.id, snapshot.totalTokensUsed(),
                            String.format("%.4f", snapshot.estimatedCostUsd()));
                }
            }

            publishEvent(SwarmEvent.Type.SWARM_COMPLETED, "Swarm execution completed successfully");
            return output;

        } catch (BudgetExceededException e) {
            publishEvent(SwarmEvent.Type.BUDGET_EXCEEDED,
                    "Swarm execution stopped: budget exceeded - " + e.getMessage());
            throw e;
        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.SWARM_FAILED, "Swarm execution failed: " + e.getMessage());
            throw new RuntimeException("Swarm execution failed", e);
        } finally {
            if (tenantQuotaEnforcer != null && tenantId != null) {
                tenantQuotaEnforcer.recordWorkflowEnd(tenantId);
            }
            ObservabilityContext.clear();
        }
    }

    // ========================================
    // Backward-compatible execution with Map
    // ========================================

    /**
     * Executes the compiled swarm with a raw map of inputs.
     * Backward-compatible bridge for existing code.
     *
     * @param inputs the input variables
     * @return the execution output
     */
    public SwarmOutput kickoff(Map<String, Object> inputs) {
        return kickoff(AgentState.of(stateSchema, inputs != null ? inputs : Map.of()));
    }

    /**
     * Async execution with AgentState.
     */
    public CompletableFuture<SwarmOutput> kickoffAsync(AgentState state) {
        return CompletableFuture.supplyAsync(() -> kickoff(state));
    }

    /**
     * Async execution with raw map (backward compatible).
     */
    public CompletableFuture<SwarmOutput> kickoffAsync(Map<String, Object> inputs) {
        return CompletableFuture.supplyAsync(() -> kickoff(inputs));
    }

    /**
     * Batch execution with AgentState.
     */
    public List<SwarmOutput> kickoffForEach(List<AgentState> stateList) {
        List<SwarmOutput> outputs = new ArrayList<>();
        for (AgentState state : stateList) {
            outputs.add(kickoff(state));
        }
        return outputs;
    }

    /**
     * Batch async execution with AgentState.
     */
    public CompletableFuture<List<SwarmOutput>> kickoffForEachAsync(List<AgentState> stateList) {
        List<CompletableFuture<SwarmOutput>> futures = stateList.stream()
                .map(this::kickoffAsync)
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }

    // ========================================
    // Process creation (same logic as Swarm.createProcess)
    // ========================================

    private Process createProcess() {
        return switch (processType) {
            case SEQUENTIAL -> new ai.intelliswarm.swarmai.process.SequentialProcess(agents, eventPublisher);
            case HIERARCHICAL -> new ai.intelliswarm.swarmai.process.HierarchicalProcess(agents, managerAgent, eventPublisher);
            case PARALLEL -> new ai.intelliswarm.swarmai.process.ParallelProcess(agents, eventPublisher);
            case ITERATIVE -> {
                int maxIter = config.containsKey("maxIterations") ? (int) config.get("maxIterations") : 3;
                String criteria = config.containsKey("qualityCriteria") ? (String) config.get("qualityCriteria") : null;
                yield new ai.intelliswarm.swarmai.process.IterativeProcess(
                        agents, managerAgent, eventPublisher, maxIter, criteria);
            }
            case SELF_IMPROVING -> {
                int maxIter = config.containsKey("maxIterations") ? (int) config.get("maxIterations") : 5;
                String criteria = config.containsKey("qualityCriteria") ? (String) config.get("qualityCriteria") : null;
                yield new ai.intelliswarm.swarmai.process.SelfImprovingProcess(
                        agents, managerAgent, eventPublisher, maxIter, criteria);
            }
            case SWARM -> {
                int maxIter = config.containsKey("maxIterations") ? ((Number) config.get("maxIterations")).intValue() : 5;
                int maxParallel = config.containsKey("maxParallelAgents") ? ((Number) config.get("maxParallelAgents")).intValue() : 5;
                String criteria = config.containsKey("qualityCriteria") ? (String) config.get("qualityCriteria") : null;
                ai.intelliswarm.swarmai.process.SwarmCoordinator coordinator =
                        new ai.intelliswarm.swarmai.process.SwarmCoordinator(
                                agents, managerAgent, eventPublisher, maxIter, maxParallel, criteria);
                if (config.containsKey("targetPrefix")) {
                    coordinator.setTargetPattern((String) config.get("targetPrefix"));
                }
                yield coordinator;
            }
        };
    }

    private void publishEvent(SwarmEvent.Type type, String message) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, id);
            eventPublisher.publishEvent(event);
        }
    }

    // ========================================
    // SwarmDefinition implementation
    // ========================================

    @Override
    public List<Agent> agents() {
        return agents;
    }

    @Override
    public List<Task> tasks() {
        return tasks;
    }

    @Override
    public ProcessType processType() {
        return processType;
    }

    // ========================================
    // Accessors
    // ========================================

    public String getId() { return id; }
    public Agent getManagerAgent() { return managerAgent; }
    public boolean isVerbose() { return verbose; }
    public Memory getMemory() { return memory; }
    public Knowledge getKnowledge() { return knowledge; }
    public Map<String, Object> getConfig() { return config; }
    public Integer getMaxRpm() { return maxRpm; }
    public String getLanguage() { return language; }
    public String getTenantId() { return tenantId; }
    public BudgetTracker getBudgetTracker() { return budgetTracker; }
    public WorkflowGovernanceEngine getGovernance() { return governance; }
    public StateSchema getStateSchema() { return stateSchema; }
    public LocalDateTime getCompiledAt() { return compiledAt; }

    @Override
    public String toString() {
        return "CompiledSwarm{" +
                "id='" + id + '\'' +
                ", agents=" + agents.size() +
                ", tasks=" + tasks.size() +
                ", processType=" + processType +
                ", compiledAt=" + compiledAt +
                '}';
    }
}
