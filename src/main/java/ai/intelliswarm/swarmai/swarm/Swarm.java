package ai.intelliswarm.swarmai.swarm;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetExceededException;
import ai.intelliswarm.swarmai.budget.BudgetPolicy;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.GovernanceInterceptor;
import ai.intelliswarm.swarmai.governance.WorkflowGovernanceEngine;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class Swarm {

    private static final Logger logger = LoggerFactory.getLogger(Swarm.class);

    @NotNull
    private final String id;

    @NotEmpty
    private final List<Agent> agents;

    @NotEmpty
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

    @JsonIgnore
    private ApplicationEventPublisher eventPublisher;

    @JsonIgnore
    private final BudgetTracker budgetTracker;
    @JsonIgnore
    private final BudgetPolicy budgetPolicy;
    @JsonIgnore
    private final WorkflowGovernanceEngine governance;
    @JsonIgnore
    private final List<ApprovalGate> approvalGates;
    @JsonIgnore
    private final TenantQuotaEnforcer tenantQuotaEnforcer;

    private final LocalDateTime createdAt;
    private SwarmOutput lastOutput;
    private SwarmStatus status = SwarmStatus.READY;

    private Swarm(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.agents = new ArrayList<>(builder.agents);
        this.tasks = new ArrayList<>(builder.tasks);
        this.processType = builder.processType;
        this.managerAgent = builder.managerAgent;
        this.verbose = builder.verbose;
        this.memory = builder.memory;
        this.knowledge = builder.knowledge;
        this.config = new HashMap<>(builder.config);
        this.maxRpm = builder.maxRpm;
        this.language = builder.language;
        this.tenantId = builder.tenantId;
        this.eventPublisher = builder.eventPublisher;
        this.budgetTracker = builder.budgetTracker;
        this.budgetPolicy = builder.budgetPolicy;
        this.governance = builder.governance;
        this.approvalGates = builder.approvalGates != null ? new ArrayList<>(builder.approvalGates) : List.of();
        this.tenantQuotaEnforcer = builder.tenantQuotaEnforcer;
        this.createdAt = LocalDateTime.now();

        validateConfiguration();
    }

    public SwarmOutput kickoff(Map<String, Object> inputs) {
        // Initialize observability context for this workflow
        ObservabilityContext ctx = ObservabilityContext.create()
                .withSwarmId(this.id);

        // Set tenant context if configured
        if (tenantId != null) {
            ctx.withTenantId(tenantId);
        }

        // Enforce tenant quota if enforcer is available
        if (tenantQuotaEnforcer != null && tenantId != null) {
            tenantQuotaEnforcer.checkWorkflowQuota(tenantId);
            tenantQuotaEnforcer.recordWorkflowStart(tenantId);
        }

        // Set budget policy for this workflow
        if (budgetTracker != null && budgetPolicy != null) {
            budgetTracker.setBudgetPolicy(this.id, budgetPolicy);
        }

        publishEvent(SwarmEvent.Type.SWARM_STARTED, "Swarm kickoff initiated");

        Map<String, Object> safeInputs = inputs != null ? inputs : Map.of();

        try {
            status = SwarmStatus.RUNNING;

            // Reset tasks before execution so swarm can be re-used (e.g., kickoffForEach)
            tasks.forEach(Task::reset);

            // Inject budget tracker into inputs for Process-level usage tracking
            if (budgetTracker != null) {
                safeInputs = new HashMap<>(safeInputs);
                safeInputs.put("__budgetTracker", budgetTracker);
                safeInputs.put("__budgetSwarmId", this.id);
            }

            Process process = createProcess();

            // Wrap with governance interceptor if configured
            if (governance != null && !approvalGates.isEmpty()) {
                process = new GovernanceInterceptor(process, governance, approvalGates);
            }

            SwarmOutput output = process.execute(tasks, safeInputs, this.id);

            this.lastOutput = output;
            status = SwarmStatus.COMPLETED;

            // Log final budget snapshot
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
            status = SwarmStatus.FAILED;
            publishEvent(SwarmEvent.Type.BUDGET_EXCEEDED,
                "Swarm execution stopped: budget exceeded - " + e.getMessage());
            throw e;
        } catch (Exception e) {
            status = SwarmStatus.FAILED;
            publishEvent(SwarmEvent.Type.SWARM_FAILED, "Swarm execution failed: " + e.getMessage());
            throw new RuntimeException("Swarm execution failed", e);
        } finally {
            // Release tenant quota
            if (tenantQuotaEnforcer != null && tenantId != null) {
                tenantQuotaEnforcer.recordWorkflowEnd(tenantId);
            }
            ObservabilityContext.clear();
        }
    }

    public CompletableFuture<SwarmOutput> kickoffAsync(Map<String, Object> inputs) {
        return CompletableFuture.supplyAsync(() -> kickoff(inputs));
    }

    public List<SwarmOutput> kickoffForEach(List<Map<String, Object>> inputsList) {
        publishEvent(SwarmEvent.Type.SWARM_STARTED, "Swarm kickoff for each initiated with " + inputsList.size() + " inputs");

        List<SwarmOutput> outputs = new ArrayList<>();
        for (Map<String, Object> input : inputsList) {
            outputs.add(kickoff(input));
        }
        return outputs;
    }

    public CompletableFuture<List<SwarmOutput>> kickoffForEachAsync(List<Map<String, Object>> inputsList) {
        List<CompletableFuture<SwarmOutput>> futures = inputsList.stream()
            .map(this::kickoffAsync)
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList()));
    }

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

    private void validateConfiguration() {
        if (processType == ProcessType.HIERARCHICAL && managerAgent == null) {
            throw new IllegalStateException("Manager agent is required for hierarchical process");
        }

        if (processType == ProcessType.SELF_IMPROVING && managerAgent == null) {
            throw new IllegalStateException("Manager agent (reviewer) is required for self-improving process");
        }

        if (processType == ProcessType.ITERATIVE && managerAgent == null) {
            throw new IllegalStateException("Manager agent (reviewer) is required for iterative process");
        }

        if (processType == ProcessType.SWARM && managerAgent == null) {
            throw new IllegalStateException("Manager agent (reviewer) is required for swarm coordinator process");
        }

        if (tasks.isEmpty()) {
            throw new IllegalStateException("At least one task is required");
        }

        if (agents.isEmpty()) {
            throw new IllegalStateException("At least one agent is required");
        }
    }

    public void resetMemory() {
        if (memory != null) {
            memory.clear();
            publishEvent(SwarmEvent.Type.MEMORY_RESET, "Swarm memory has been reset");
        }
    }

    private void publishEvent(SwarmEvent.Type type, String message) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, id);
            eventPublisher.publishEvent(event);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private List<Agent> agents = new ArrayList<>();
        private List<Task> tasks = new ArrayList<>();
        private ProcessType processType = ProcessType.SEQUENTIAL;
        private Agent managerAgent;
        private boolean verbose = false;
        private Memory memory;
        private Knowledge knowledge;
        private Map<String, Object> config = new HashMap<>();
        private Integer maxRpm;
        private String language = "en";
        private String tenantId;
        private ApplicationEventPublisher eventPublisher;
        private BudgetTracker budgetTracker;
        private BudgetPolicy budgetPolicy;
        private WorkflowGovernanceEngine governance;
        private List<ApprovalGate> approvalGates;
        private TenantQuotaEnforcer tenantQuotaEnforcer;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder agents(List<Agent> agents) {
            this.agents = new ArrayList<>(agents);
            return this;
        }

        public Builder agent(Agent agent) {
            this.agents.add(agent);
            return this;
        }

        public Builder tasks(List<Task> tasks) {
            this.tasks = new ArrayList<>(tasks);
            return this;
        }

        public Builder task(Task task) {
            this.tasks.add(task);
            return this;
        }

        public Builder process(ProcessType processType) {
            this.processType = processType;
            return this;
        }

        public Builder managerAgent(Agent managerAgent) {
            this.managerAgent = managerAgent;
            return this;
        }

        public Builder verbose(boolean verbose) {
            this.verbose = verbose;
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

        public Builder config(String key, Object value) {
            this.config.put(key, value);
            return this;
        }

        public Builder maxRpm(Integer maxRpm) {
            this.maxRpm = maxRpm;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder eventPublisher(ApplicationEventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder budgetTracker(BudgetTracker budgetTracker) {
            this.budgetTracker = budgetTracker;
            return this;
        }

        public Builder budgetPolicy(BudgetPolicy budgetPolicy) {
            this.budgetPolicy = budgetPolicy;
            return this;
        }

        public Builder governance(WorkflowGovernanceEngine governance) {
            this.governance = governance;
            return this;
        }

        public Builder approvalGates(List<ApprovalGate> approvalGates) {
            this.approvalGates = approvalGates;
            return this;
        }

        public Builder approvalGate(ApprovalGate gate) {
            if (this.approvalGates == null) this.approvalGates = new ArrayList<>();
            this.approvalGates.add(gate);
            return this;
        }

        public Builder tenantQuotaEnforcer(TenantQuotaEnforcer tenantQuotaEnforcer) {
            this.tenantQuotaEnforcer = tenantQuotaEnforcer;
            return this;
        }

        public Swarm build() {
            return new Swarm(this);
        }
    }

    // Getters
    public String getId() { return id; }
    public List<Agent> getAgents() { return new ArrayList<>(agents); }
    public List<Task> getTasks() { return new ArrayList<>(tasks); }
    public ProcessType getProcessType() { return processType; }
    public Agent getManagerAgent() { return managerAgent; }
    public boolean isVerbose() { return verbose; }
    public Memory getMemory() { return memory; }
    public Knowledge getKnowledge() { return knowledge; }
    public Map<String, Object> getConfig() { return new HashMap<>(config); }
    public Integer getMaxRpm() { return maxRpm; }
    public String getLanguage() { return language; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public SwarmOutput getLastOutput() { return lastOutput; }
    public SwarmStatus getStatus() { return status; }
    public String getTenantId() { return tenantId; }
    public BudgetTracker getBudgetTracker() { return budgetTracker; }
    public WorkflowGovernanceEngine getGovernance() { return governance; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Swarm)) return false;
        Swarm swarm = (Swarm) o;
        return Objects.equals(id, swarm.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Swarm{" +
                "id='" + id + '\'' +
                ", agents=" + agents.size() +
                ", tasks=" + tasks.size() +
                ", processType=" + processType +
                ", status=" + status +
                '}';
    }

    public enum SwarmStatus {
        READY,
        RUNNING,
        COMPLETED,
        FAILED
    }
}
