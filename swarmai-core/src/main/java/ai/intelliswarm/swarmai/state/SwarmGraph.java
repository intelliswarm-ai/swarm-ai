package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.WorkflowGovernanceEngine;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

/**
 * Mutable construction phase of a swarm workflow.
 * Agents, tasks, and configuration are added here. When ready, call {@link #compile()}
 * to validate the configuration and produce an immutable {@link CompiledSwarm}.
 *
 * <p>This class enforces the "build, then compile, then execute" lifecycle.
 * Compile-time validation catches configuration errors (missing agents, invalid
 * dependencies, etc.) before any execution occurs.
 *
 * <p>Usage:
 * <pre>{@code
 * CompilationResult result = SwarmGraph.create()
 *     .addAgent(researcher)
 *     .addTask(researchTask)
 *     .process(ProcessType.SEQUENTIAL)
 *     .compile();
 *
 * if (result.isSuccess()) {
 *     result.compiled().kickoff(state);
 * }
 * }</pre>
 */
public final class SwarmGraph implements SwarmDefinition {

    private String id;
    private final List<Agent> agents = new ArrayList<>();
    private final List<Task> tasks = new ArrayList<>();
    private ProcessType processType = ProcessType.SEQUENTIAL;
    private Agent managerAgent;
    private boolean verbose = false;
    private Memory memory;
    private Knowledge knowledge;
    private final Map<String, Object> config = new HashMap<>();
    private Integer maxRpm;
    private String language = "en";
    private String tenantId;
    private ApplicationEventPublisher eventPublisher;
    private BudgetTracker budgetTracker;
    private BudgetPolicy budgetPolicy;
    private WorkflowGovernanceEngine governance;
    private final List<ApprovalGate> approvalGates = new ArrayList<>();
    private TenantQuotaEnforcer tenantQuotaEnforcer;
    private StateSchema stateSchema = StateSchema.PERMISSIVE;
    private CheckpointSaver checkpointSaver;
    private final List<String> interruptBeforeTaskIds = new ArrayList<>();
    private final List<String> interruptAfterTaskIds = new ArrayList<>();
    private final Map<HookPoint, List<SwarmHook<AgentState>>> hooks = new java.util.EnumMap<>(HookPoint.class);

    private SwarmGraph() {}

    /**
     * Creates a new mutable SwarmGraph for configuration.
     */
    public static SwarmGraph create() {
        return new SwarmGraph();
    }

    /**
     * Creates a new SwarmGraph with a specific state schema.
     */
    public static SwarmGraph create(StateSchema schema) {
        SwarmGraph graph = new SwarmGraph();
        graph.stateSchema = schema != null ? schema : StateSchema.PERMISSIVE;
        return graph;
    }

    // ========================================
    // Fluent configuration methods
    // ========================================

    public SwarmGraph id(String id) {
        this.id = id;
        return this;
    }

    public SwarmGraph addAgent(Agent agent) {
        Objects.requireNonNull(agent, "Agent cannot be null");
        this.agents.add(agent);
        return this;
    }

    public SwarmGraph agents(List<Agent> agents) {
        this.agents.clear();
        this.agents.addAll(agents);
        return this;
    }

    public SwarmGraph addTask(Task task) {
        Objects.requireNonNull(task, "Task cannot be null");
        this.tasks.add(task);
        return this;
    }

    public SwarmGraph tasks(List<Task> tasks) {
        this.tasks.clear();
        this.tasks.addAll(tasks);
        return this;
    }

    public SwarmGraph process(ProcessType processType) {
        this.processType = processType;
        return this;
    }

    public SwarmGraph managerAgent(Agent managerAgent) {
        this.managerAgent = managerAgent;
        return this;
    }

    public SwarmGraph verbose(boolean verbose) {
        this.verbose = verbose;
        return this;
    }

    public SwarmGraph memory(Memory memory) {
        this.memory = memory;
        return this;
    }

    public SwarmGraph knowledge(Knowledge knowledge) {
        this.knowledge = knowledge;
        return this;
    }

    public SwarmGraph config(String key, Object value) {
        this.config.put(key, value);
        return this;
    }

    public SwarmGraph maxRpm(Integer maxRpm) {
        this.maxRpm = maxRpm;
        return this;
    }

    public SwarmGraph language(String language) {
        this.language = language;
        return this;
    }

    public SwarmGraph tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public SwarmGraph eventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
        return this;
    }

    public SwarmGraph budgetTracker(BudgetTracker budgetTracker) {
        this.budgetTracker = budgetTracker;
        return this;
    }

    public SwarmGraph budgetPolicy(BudgetPolicy budgetPolicy) {
        this.budgetPolicy = budgetPolicy;
        return this;
    }

    public SwarmGraph governance(WorkflowGovernanceEngine governance) {
        this.governance = governance;
        return this;
    }

    public SwarmGraph addApprovalGate(ApprovalGate gate) {
        this.approvalGates.add(gate);
        return this;
    }

    public SwarmGraph approvalGates(List<ApprovalGate> gates) {
        this.approvalGates.clear();
        this.approvalGates.addAll(gates);
        return this;
    }

    public SwarmGraph tenantQuotaEnforcer(TenantQuotaEnforcer enforcer) {
        this.tenantQuotaEnforcer = enforcer;
        return this;
    }

    public SwarmGraph stateSchema(StateSchema schema) {
        this.stateSchema = schema != null ? schema : StateSchema.PERMISSIVE;
        return this;
    }

    public SwarmGraph checkpointSaver(CheckpointSaver saver) {
        this.checkpointSaver = saver;
        return this;
    }

    /**
     * Pauses workflow execution before the specified task and saves a checkpoint.
     * Use with {@link CompiledSwarm#resume(String)} to implement human-in-the-loop patterns.
     */
    public SwarmGraph interruptBefore(String taskId) {
        this.interruptBeforeTaskIds.add(taskId);
        return this;
    }

    /**
     * Saves a checkpoint after the specified task completes.
     */
    public SwarmGraph interruptAfter(String taskId) {
        this.interruptAfterTaskIds.add(taskId);
        return this;
    }

    /**
     * Registers a hook at the specified point in the workflow lifecycle.
     * Multiple hooks can be registered at the same point — they execute in registration order.
     */
    public SwarmGraph addHook(HookPoint point, SwarmHook<AgentState> hook) {
        Objects.requireNonNull(point, "HookPoint cannot be null");
        Objects.requireNonNull(hook, "Hook cannot be null");
        hooks.computeIfAbsent(point, k -> new ArrayList<>()).add(hook);
        return this;
    }

    // ========================================
    // Compilation
    // ========================================

    /**
     * Validates the graph configuration and produces an immutable {@link CompiledSwarm}.
     * All validation errors are collected and returned, rather than throwing on the first error.
     *
     * @return a {@link CompilationResult} — either success with a CompiledSwarm, or failure with errors
     */
    public CompilationResult compile() {
        List<CompilationError> errors = validate();

        if (!errors.isEmpty()) {
            return CompilationResult.failure(errors);
        }

        return CompilationResult.success(new CompiledSwarm(
                id != null ? id : UUID.randomUUID().toString(),
                List.copyOf(agents),
                List.copyOf(tasks),
                processType,
                managerAgent,
                verbose,
                memory,
                knowledge,
                Map.copyOf(config),
                maxRpm,
                language,
                tenantId,
                eventPublisher,
                budgetTracker,
                budgetPolicy,
                governance,
                List.copyOf(approvalGates),
                tenantQuotaEnforcer,
                stateSchema,
                checkpointSaver,
                List.copyOf(interruptBeforeTaskIds),
                List.copyOf(interruptAfterTaskIds),
                Map.copyOf(hooks)
        ));
    }

    /**
     * Compiles the graph, throwing an {@link IllegalStateException} if validation fails.
     * Convenience method for callers who prefer exceptions over result objects.
     */
    public CompiledSwarm compileOrThrow() {
        CompilationResult result = compile();
        if (!result.isSuccess()) {
            StringBuilder sb = new StringBuilder("Swarm compilation failed with ")
                    .append(result.errors().size()).append(" error(s):\n");
            for (CompilationError error : result.errors()) {
                sb.append("  - ").append(error.message()).append("\n");
            }
            throw new IllegalStateException(sb.toString());
        }
        return result.compiled();
    }

    private List<CompilationError> validate() {
        List<CompilationError> errors = new ArrayList<>();

        if (agents.isEmpty()) {
            errors.add(new CompilationError.NoAgents());
        }

        if (tasks.isEmpty()) {
            errors.add(new CompilationError.NoTasks());
        }

        // Manager agent required for specific process types
        if (managerAgent == null) {
            if (processType == ProcessType.HIERARCHICAL) {
                errors.add(new CompilationError.MissingManagerAgent("hierarchical"));
            }
            if (processType == ProcessType.ITERATIVE) {
                errors.add(new CompilationError.MissingManagerAgent("iterative"));
            }
            if (processType == ProcessType.SELF_IMPROVING) {
                errors.add(new CompilationError.MissingManagerAgent("self-improving"));
            }
            if (processType == ProcessType.SWARM) {
                errors.add(new CompilationError.MissingManagerAgent("swarm"));
            }
        }

        // Validate task dependencies
        Set<String> taskIds = new HashSet<>();
        for (Task task : tasks) {
            taskIds.add(task.getId());
        }
        for (Task task : tasks) {
            if (task.getDependencyTaskIds() != null) {
                for (String dep : task.getDependencyTaskIds()) {
                    if (!taskIds.contains(dep)) {
                        errors.add(new CompilationError.InvalidDependency(task.getId(), dep));
                    }
                }
            }
        }

        return errors;
    }

    // ========================================
    // SwarmDefinition implementation
    // ========================================

    @Override
    public List<Agent> agents() {
        return Collections.unmodifiableList(agents);
    }

    @Override
    public List<Task> tasks() {
        return Collections.unmodifiableList(tasks);
    }

    @Override
    public ProcessType processType() {
        return processType;
    }

    // ========================================
    // Accessors for internal state (used by CompiledSwarm)
    // ========================================

    Agent managerAgent() { return managerAgent; }
    boolean verbose() { return verbose; }
    Memory memory() { return memory; }
    Knowledge knowledge() { return knowledge; }
    Map<String, Object> config() { return config; }
    Integer maxRpm() { return maxRpm; }
    String language() { return language; }
    String tenantId() { return tenantId; }
    ApplicationEventPublisher eventPublisher() { return eventPublisher; }
    BudgetTracker budgetTracker() { return budgetTracker; }
    BudgetPolicy budgetPolicy() { return budgetPolicy; }
    WorkflowGovernanceEngine governance() { return governance; }
    List<ApprovalGate> approvalGates() { return approvalGates; }
    TenantQuotaEnforcer tenantQuotaEnforcer() { return tenantQuotaEnforcer; }
    StateSchema stateSchema() { return stateSchema; }
}
