package ai.intelliswarm.swarmai.swarm;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.agent.streaming.AgentEvent;
import ai.intelliswarm.swarmai.agent.resilience.LlmHealthChecker;
import ai.intelliswarm.swarmai.api.PublicApi;
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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import ai.intelliswarm.swarmai.state.AgentState;
import ai.intelliswarm.swarmai.state.SwarmGraph;
import ai.intelliswarm.swarmai.state.CompiledSwarm;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@PublicApi(since = "1.0")
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
    private final boolean healthCheckEnabled;

    private final LocalDateTime createdAt;
    private SwarmOutput lastOutput;
    private SwarmStatus status = SwarmStatus.READY;

    /**
     * Optional global evolution advisor. When set, Swarm.kickoff() consults it
     * before selecting the process type. The self-improving module registers
     * an implementation that reads evolution history from H2.
     *
     * <p>Thread-safe: set once during auto-configuration, read on every kickoff.
     */
    private static volatile EvolutionAdvisor evolutionAdvisor;

    /**
     * Functional interface for the evolution advisor. Receives the configured
     * processType, task count, and dependency depth, and returns the optimized
     * processType (or the same one if no evolution applies).
     */
    @FunctionalInterface
    public interface EvolutionAdvisor {
        ProcessType advise(ProcessType configured, int taskCount, int maxDepth);
    }

    /**
     * Register a global evolution advisor. Called by SelfImprovementAutoConfiguration.
     */
    public static void setEvolutionAdvisor(EvolutionAdvisor advisor) {
        evolutionAdvisor = advisor;
    }

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
        this.healthCheckEnabled = builder.healthCheckEnabled;
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

            // Consult the evolution advisor — may switch process type
            // based on learned optimizations from prior runs.
            applyEvolution();

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

            if (eventPublisher != null) {
                eventPublisher.publishEvent(
                    new ai.intelliswarm.swarmai.event.SwarmCompletedEvent(this, this.id, output, budgetTracker)
                );
            }

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

    /**
     * Executes the swarm with type-safe AgentState.
     * Delegates to the Map-based kickoff for backward compatibility.
     */
    public SwarmOutput kickoff(AgentState state) {
        return kickoff(state != null ? state.data() : Map.of());
    }

    /**
     * Token-level streaming variant of {@link #kickoff(Map)}: returns a single
     * {@link Flux} of {@link AgentEvent}s spanning every agent in the swarm.
     *
     * <p>Currently supported process types:
     * <ul>
     *   <li>{@link ProcessType#SEQUENTIAL} — agent fluxes are <b>concatenated</b>:
     *       agent N's events arrive after agent N-1 emits its terminal
     *       {@code AgentFinished}, and each agent's {@link TaskOutput} feeds
     *       into the next agent's context just like in non-streaming mode.</li>
     *   <li>{@link ProcessType#PARALLEL} — agent fluxes are <b>merged</b>: every
     *       agent runs concurrently on the parallel scheduler and their events
     *       interleave in arrival order. Frontends use the {@code agentId}
     *       carried on every event to demux per-agent text into separate
     *       UI panes.</li>
     * </ul>
     *
     * <p>Other process types ({@code HIERARCHICAL}, {@code ITERATIVE}, etc.)
     * are not yet supported in streaming mode — callers receive a single
     * {@link AgentEvent.AgentError} and the Flux completes. Use
     * {@link #kickoff(Map)} for those (non-streaming) and call
     * {@link Agent#executeTaskStreaming(Task, java.util.List)} directly when
     * you need streaming for a single agent inside a complex orchestration.
     *
     * <p>Like {@link Agent#executeTaskStreaming}, errors become terminal
     * {@link AgentEvent.AgentError} events on the success channel — the Flux
     * always completes normally so SSE consumers don't need a separate
     * error-handler hook.
     *
     * @since 1.1.0
     */
    public Flux<AgentEvent> runStreaming(Map<String, Object> inputs) {
        if (tasks.isEmpty()) {
            return Flux.just(new AgentEvent.AgentError(
                    "swarm:" + id, "IllegalStateException",
                    "Swarm has no tasks to run", System.currentTimeMillis()));
        }

        return switch (processType) {
            case SEQUENTIAL -> runStreamingSequential(inputs);
            case PARALLEL   -> runStreamingParallel(inputs);
            default -> Flux.just(new AgentEvent.AgentError(
                    "swarm:" + id,
                    "UnsupportedProcessType",
                    "Streaming is not yet supported for ProcessType." + processType
                            + ". Use SEQUENTIAL or PARALLEL, or kickoff() for non-streaming execution.",
                    System.currentTimeMillis()));
        };
    }

    /**
     * SEQUENTIAL streaming — runs agents one after another, threading each agent's
     * {@link TaskOutput} into the next agent's context. The Flux is built lazily
     * (via {@link Flux#defer}) so the i-th agent's prompt is constructed AFTER
     * the (i-1)-th has emitted its {@code AgentFinished}.
     */
    private Flux<AgentEvent> runStreamingSequential(Map<String, Object> inputs) {
        // CopyOnWriteArrayList because the doOnNext mutator runs on the reactive
        // thread but the next agent's Flux.defer reads it on its own thread.
        java.util.List<TaskOutput> accumulated = new java.util.concurrent.CopyOnWriteArrayList<>();
        interpolateStreamingTaskDescriptions(inputs);
        java.util.List<Task> orderedTasks = orderTasksForStreaming(tasks);

        Flux<AgentEvent> chain = Flux.empty();
        for (int i = 0; i < orderedTasks.size(); i++) {
            final Task t = orderedTasks.get(i);
            final Agent a = pickAgentForStreaming(t, i);
            // Defer so we capture each agent's executeTaskStreaming() AFTER the
            // previous Flux completes — gives the deferred call a fresh snapshot
            // of `accumulated`.
            Flux<AgentEvent> step = Flux.defer(() -> a.executeTaskStreaming(t,
                            new java.util.ArrayList<>(accumulated))
                    .doOnNext(evt -> {
                        if (evt instanceof AgentEvent.AgentFinished f
                                && f.taskOutput() != null) {
                            accumulated.add(f.taskOutput());
                        }
                    }));
            chain = chain.concatWith(step);
        }
        return chain;
    }

    /**
     * PARALLEL streaming — fan out, run all agents concurrently on the reactor
     * parallel scheduler, merge their events as they arrive. No context-passing:
     * each agent sees only its own task. Use the {@code agentId} on every
     * {@link AgentEvent} to demux per-agent output on the consumer side.
     */
    private Flux<AgentEvent> runStreamingParallel(Map<String, Object> inputs) {
        interpolateStreamingTaskDescriptions(inputs);
        java.util.List<TaskOutput> completedOutputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<java.util.List<Task>> layers = buildParallelStreamingLayers(tasks);

        Flux<AgentEvent> stream = Flux.empty();
        for (java.util.List<Task> layer : layers) {
            java.util.List<Flux<AgentEvent>> layerStreams = new java.util.ArrayList<>(layer.size());
            for (Task task : layer) {
                int taskIndex = tasks.indexOf(task);
                final Agent agent = pickAgentForStreaming(task, taskIndex);
                java.util.List<TaskOutput> context = contextForTask(task, completedOutputs);
                layerStreams.add(Flux.defer(() -> agent.executeTaskStreaming(task, context)
                        .doOnNext(evt -> {
                            if (evt instanceof AgentEvent.AgentFinished f && f.taskOutput() != null) {
                                completedOutputs.add(f.taskOutput());
                            }
                        }))
                        .subscribeOn(Schedulers.parallel()));
            }
            stream = stream.concatWith(Flux.merge(layerStreams));
        }
        return stream;
    }

    private void interpolateStreamingTaskDescriptions(Map<String, Object> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        for (Task task : tasks) {
            task.interpolateDescription(inputs);
        }
    }

    private java.util.List<Task> orderTasksForStreaming(java.util.List<Task> inputTasks) {
        java.util.List<Task> ordered = new java.util.ArrayList<>();
        java.util.Set<String> processed = new java.util.HashSet<>();
        java.util.Set<String> queued = new java.util.HashSet<>();
        java.util.Queue<Task> queue = new java.util.LinkedList<>();

        for (Task task : inputTasks) {
            if (task.getDependencyTaskIds().isEmpty()) {
                queue.offer(task);
                queued.add(task.getId());
            }
        }

        while (!queue.isEmpty()) {
            Task current = queue.poll();
            ordered.add(current);
            processed.add(current.getId());

            for (Task task : inputTasks) {
                if (!queued.contains(task.getId()) && processed.containsAll(task.getDependencyTaskIds())) {
                    queue.offer(task);
                    queued.add(task.getId());
                }
            }
        }

        if (ordered.size() != inputTasks.size()) {
            throw new IllegalStateException("Circular dependency detected in tasks");
        }
        return ordered;
    }

    private java.util.List<java.util.List<Task>> buildParallelStreamingLayers(java.util.List<Task> inputTasks) {
        java.util.Set<String> processed = new java.util.HashSet<>();
        java.util.Set<String> allTaskIds = inputTasks.stream().map(Task::getId).collect(java.util.stream.Collectors.toSet());
        java.util.List<java.util.List<Task>> layers = new java.util.ArrayList<>();

        while (processed.size() < inputTasks.size()) {
            java.util.List<Task> currentLayer = new java.util.ArrayList<>();
            for (Task task : inputTasks) {
                if (processed.contains(task.getId())) {
                    continue;
                }
                boolean ready = true;
                for (String depId : task.getDependencyTaskIds()) {
                    if (allTaskIds.contains(depId) && !processed.contains(depId)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    currentLayer.add(task);
                }
            }

            if (currentLayer.isEmpty()) {
                throw new IllegalStateException("Circular dependency detected — cannot build layers");
            }

            layers.add(currentLayer);
            for (Task task : currentLayer) {
                processed.add(task.getId());
            }
        }

        return layers;
    }

    private java.util.List<TaskOutput> contextForTask(Task task, java.util.List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return new java.util.ArrayList<>(allOutputs);
        }
        return allOutputs.stream()
                .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
                .toList();
    }

    /**
     * Pair an {@link Agent} to a {@link Task}: prefer the task's explicitly-bound
     * agent (set via {@code Task.builder().agent(...)}), then fall back to the
     * positionally-matched swarm agent, then to the first agent. Mirrors what
     * the existing non-streaming Process implementations do.
     */
    private Agent pickAgentForStreaming(Task task, int index) {
        if (task.getAgent() != null) return task.getAgent();
        if (index < agents.size()) return agents.get(index);
        if (!agents.isEmpty()) return agents.get(0);
        throw new IllegalStateException(
                "Swarm has no agents — cannot stream task " + task.getId());
    }

    public CompletableFuture<SwarmOutput> kickoffAsync(Map<String, Object> inputs) {
        ObservabilityContext.Snapshot snapshot = ObservabilityContext.snapshot();
        return CompletableFuture.supplyAsync(() -> {
            snapshot.restore();
            try {
                return kickoff(inputs);
            } finally {
                ObservabilityContext.clear();
            }
        });
    }

    public CompletableFuture<SwarmOutput> kickoffAsync(AgentState state) {
        ObservabilityContext.Snapshot snapshot = ObservabilityContext.snapshot();
        return CompletableFuture.supplyAsync(() -> {
            snapshot.restore();
            try {
                return kickoff(state);
            } finally {
                ObservabilityContext.clear();
            }
        });
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

    /**
     * Consult the evolution advisor and apply any learned topology optimization.
     * The processType field is final, but we can mutate the config to signal
     * the evolved type, or we override via a mutable field.
     */
    private ProcessType evolvedProcessType;

    private void applyEvolution() {
        if (evolutionAdvisor == null) return;

        // Re-evaluate on every kickoff so prior evolution decisions do not
        // leak into future runs when the advisor returns the configured type.
        evolvedProcessType = null;

        int maxDepth = computeMaxTaskDepth();
        ProcessType advised = evolutionAdvisor.advise(processType, tasks.size(), maxDepth);

        if (advised != processType) {
            logger.info("[{}] Self-evolution applied: {} → {} (learned from prior runs)",
                    id, processType, advised);
            evolvedProcessType = advised;
        }
    }

    private int computeMaxTaskDepth() {
        // Tasks with no dependencies have depth 0
        for (Task task : tasks) {
            if (task.getDependencyTaskIds() != null && !task.getDependencyTaskIds().isEmpty()) {
                return 1; // has at least one dependency
            }
        }
        return 0; // all tasks are independent
    }

    private Process createProcess() {
        ProcessType effectiveType = evolvedProcessType != null ? evolvedProcessType : processType;
        return switch (effectiveType) {
            case SEQUENTIAL -> new ai.intelliswarm.swarmai.process.SequentialProcess(agents, eventPublisher);
            case HIERARCHICAL -> new ai.intelliswarm.swarmai.process.HierarchicalProcess(agents, managerAgent, eventPublisher);
            case PARALLEL -> {
                int perTaskTimeout = config.containsKey("perTaskTimeoutSeconds")
                        ? ((Number) config.get("perTaskTimeoutSeconds")).intValue() : 300;
                int maxConcurrent = config.containsKey("maxConcurrentLlmCalls")
                        ? ((Number) config.get("maxConcurrentLlmCalls")).intValue() : 0;
                yield new ai.intelliswarm.swarmai.process.ParallelProcess(
                        agents, eventPublisher, Runtime.getRuntime().availableProcessors(),
                        perTaskTimeout, maxConcurrent);
            }
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
            case DISTRIBUTED -> {
                int maxParallel = config.containsKey("maxParallelPartitions")
                        ? ((Number) config.get("maxParallelPartitions")).intValue()
                        : Runtime.getRuntime().availableProcessors();
                String criteria = config.containsKey("qualityCriteria") ? (String) config.get("qualityCriteria") : null;
                // DistributedProcess is in swarmai-distributed module — instantiated via reflection
                // to avoid circular dependency from core → distributed
                yield createDistributedProcess(maxParallel, criteria);
            }
            case COMPOSITE -> throw new IllegalStateException(
                    "COMPOSITE process type cannot be created via Swarm.builder(). " +
                    "Use CompositeProcess.of() directly.");
        };
    }

    private Process createDistributedProcess(int maxParallel, String criteria) {
        try {
            Class<?> clazz = Class.forName(
                    "ai.intelliswarm.swarmai.distributed.execution.DistributedProcess");
            var constructor = clazz.getConstructor(
                    java.util.List.class,
                    ai.intelliswarm.swarmai.agent.Agent.class,
                    org.springframework.context.ApplicationEventPublisher.class,
                    int.class, String.class,
                    Class.forName("ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy"));
            Object strategy = Class.forName("ai.intelliswarm.swarmai.distributed.goal.PartitionStrategy")
                    .getEnumConstants()[3]; // ADAPTIVE
            return (Process) constructor.newInstance(
                    agents, managerAgent, eventPublisher, maxParallel, criteria, strategy);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "DISTRIBUTED process requires swarmai-distributed module on classpath. " +
                    "Add dependency: ai.intelliswarm:swarmai-distributed");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create DistributedProcess: " + e.getMessage(), e);
        }
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

        if (processType == ProcessType.DISTRIBUTED && managerAgent == null) {
            throw new IllegalStateException("Manager agent (coordinator) is required for distributed process");
        }

        if (tasks.isEmpty()) {
            throw new IllegalStateException("At least one task is required");
        }

        if (agents.isEmpty()) {
            throw new IllegalStateException("At least one agent is required");
        }

        if (healthCheckEnabled) {
            LlmHealthChecker.assertHealthy(agents);
            // Benchmark each unique model to seed dynamic timeouts with real measurements,
            // so the first workflow call already has a model-specific timeout instead of
            // the cold-start default.
            LlmHealthChecker.benchmarkAndSeed(
                    agents, Agent.getLatencyTracker(), LlmHealthChecker.DEFAULT_BENCHMARK_PROBES);
        }
    }

    /**
     * Converts this Swarm to a SwarmGraph for the new sealed lifecycle API.
     * Bridge method for incremental migration to the type-safe graph model.
     */
    public SwarmGraph toGraph() {
        SwarmGraph graph = SwarmGraph.create()
                .id(id)
                .process(processType)
                .verbose(verbose)
                .language(language);

        agents.forEach(graph::addAgent);
        tasks.forEach(graph::addTask);

        if (managerAgent != null) graph.managerAgent(managerAgent);
        if (memory != null) graph.memory(memory);
        if (knowledge != null) graph.knowledge(knowledge);
        if (maxRpm != null) graph.maxRpm(maxRpm);
        if (tenantId != null) graph.tenantId(tenantId);
        if (eventPublisher != null) graph.eventPublisher(eventPublisher);
        if (budgetTracker != null) graph.budgetTracker(budgetTracker);
        if (budgetPolicy != null) graph.budgetPolicy(budgetPolicy);
        if (governance != null) graph.governance(governance);
        if (!approvalGates.isEmpty()) graph.approvalGates(approvalGates);
        if (tenantQuotaEnforcer != null) graph.tenantQuotaEnforcer(tenantQuotaEnforcer);

        config.forEach(graph::config);

        return graph;
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
        private boolean healthCheckEnabled;

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

        public Builder healthCheckEnabled(boolean healthCheckEnabled) {
            this.healthCheckEnabled = healthCheckEnabled;
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
