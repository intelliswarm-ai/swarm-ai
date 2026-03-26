package ai.intelliswarm.swarmai.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, in-memory implementation of {@link BudgetTracker}.
 * Suitable for single-JVM deployments. State is lost on restart.
 *
 * <p>Each workflow gets its own {@link WorkflowBudgetState} that uses atomic
 * counters for lock-free thread safety on the hot path (recordUsage).
 */
public class InMemoryBudgetTracker implements BudgetTracker {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryBudgetTracker.class);

    private final ConcurrentHashMap<String, WorkflowBudgetState> states = new ConcurrentHashMap<>();
    private final BudgetPolicy defaultPolicy;

    /**
     * Creates a tracker with the given default policy applied to new workflows.
     *
     * @param defaultPolicy the fallback policy when none is explicitly set
     */
    public InMemoryBudgetTracker(BudgetPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
        logger.info("InMemoryBudgetTracker initialized with default policy: maxTokens={}, maxCost=${}, action={}",
                defaultPolicy.maxTotalTokens(), defaultPolicy.maxCostUsd(), defaultPolicy.onExceeded());
    }

    @Override
    public void recordUsage(String workflowId, long promptTokens, long completionTokens, String modelName) {
        WorkflowBudgetState state = getOrCreateState(workflowId);

        state.promptTokensUsed.addAndGet(promptTokens);
        state.completionTokensUsed.addAndGet(completionTokens);

        double callCost = LlmPricingModel.calculateCost(promptTokens, completionTokens, modelName);
        synchronized (state.costLock) {
            state.estimatedCostUsd += callCost;
        }

        BudgetSnapshot snapshot = buildSnapshot(state);

        // Check warning threshold
        if (snapshot.isWarning(state.policy.warningThresholdPercent())) {
            logger.warn("Budget warning for workflow '{}': tokens={}/{} ({}%), cost=${}/{} ({}%)",
                    workflowId,
                    snapshot.totalTokensUsed(), state.policy.maxTotalTokens(),
                    String.format("%.1f", snapshot.tokenUtilizationPercent()),
                    String.format("%.4f", snapshot.estimatedCostUsd()), state.policy.maxCostUsd(),
                    String.format("%.1f", snapshot.costUtilizationPercent()));
        }

        // Check hard limits
        if (snapshot.isExceeded() && state.policy.onExceeded() == BudgetPolicy.BudgetAction.HARD_STOP) {
            logger.error("Budget HARD_STOP for workflow '{}': {}", workflowId, snapshot);
            throw new BudgetExceededException(workflowId, snapshot);
        }

        if (snapshot.isExceeded() && state.policy.onExceeded() == BudgetPolicy.BudgetAction.WARN) {
            logger.warn("Budget exceeded (WARN mode) for workflow '{}': tokens={}, cost=${}",
                    workflowId, snapshot.totalTokensUsed(),
                    String.format("%.4f", snapshot.estimatedCostUsd()));
        }
    }

    @Override
    public BudgetSnapshot getSnapshot(String workflowId) {
        WorkflowBudgetState state = getOrCreateState(workflowId);
        return buildSnapshot(state);
    }

    @Override
    public boolean isExceeded(String workflowId) {
        return getSnapshot(workflowId).isExceeded();
    }

    @Override
    public void setBudgetPolicy(String workflowId, BudgetPolicy policy) {
        WorkflowBudgetState state = getOrCreateState(workflowId);
        state.policy = policy;
        logger.info("Budget policy updated for workflow '{}': maxTokens={}, maxCost=${}, action={}",
                workflowId, policy.maxTotalTokens(), policy.maxCostUsd(), policy.onExceeded());
    }

    @Override
    public void reset(String workflowId) {
        WorkflowBudgetState state = states.get(workflowId);
        if (state != null) {
            state.promptTokensUsed.set(0);
            state.completionTokensUsed.set(0);
            synchronized (state.costLock) {
                state.estimatedCostUsd = 0.0;
            }
            logger.debug("Budget counters reset for workflow '{}'", workflowId);
        }
    }

    private WorkflowBudgetState getOrCreateState(String workflowId) {
        return states.computeIfAbsent(workflowId, id -> new WorkflowBudgetState(defaultPolicy));
    }

    private BudgetSnapshot buildSnapshot(WorkflowBudgetState state) {
        long prompt = state.promptTokensUsed.get();
        long completion = state.completionTokensUsed.get();
        long total = prompt + completion;

        double cost;
        synchronized (state.costLock) {
            cost = state.estimatedCostUsd;
        }

        double tokenUtil = state.policy.maxTotalTokens() > 0
                ? (total * 100.0) / state.policy.maxTotalTokens()
                : 0.0;
        double costUtil = state.policy.maxCostUsd() > 0
                ? (cost * 100.0) / state.policy.maxCostUsd()
                : 0.0;

        boolean tokenExceeded = total >= state.policy.maxTotalTokens();
        boolean costExceeded = cost >= state.policy.maxCostUsd();

        return new BudgetSnapshot(
                prompt,
                completion,
                total,
                cost,
                tokenUtil,
                costUtil,
                tokenExceeded,
                costExceeded
        );
    }

    /**
     * Internal mutable state for a single workflow's budget tracking.
     * Thread safety is achieved via AtomicLong for token counters and
     * a synchronized block for the double cost accumulator.
     */
    static final class WorkflowBudgetState {
        final AtomicLong promptTokensUsed = new AtomicLong(0);
        final AtomicLong completionTokensUsed = new AtomicLong(0);
        final Object costLock = new Object();
        double estimatedCostUsd = 0.0;
        volatile BudgetPolicy policy;

        WorkflowBudgetState(BudgetPolicy policy) {
            this.policy = policy;
        }
    }
}
