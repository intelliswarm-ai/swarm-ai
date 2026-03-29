package ai.intelliswarm.swarmai.governance;

import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Decorator that wraps a {@link Process} to enforce governance gates during execution.
 *
 * Uses the decorator pattern to intercept task execution and check for
 * BEFORE_TASK and AFTER_TASK approval gates. If no gates match, the delegate
 * is called directly with zero overhead.
 *
 * All non-execute Process methods are delegated to the wrapped process.
 */
public class GovernanceInterceptor implements Process {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceInterceptor.class);

    private final Process delegate;
    private final WorkflowGovernanceEngine engine;
    private final List<ApprovalGate> gates;

    /**
     * Creates a governance interceptor that wraps the given process.
     *
     * @param delegate the process to wrap
     * @param engine   the governance engine for checking gates
     * @param gates    the list of approval gates to enforce
     */
    public GovernanceInterceptor(Process delegate, WorkflowGovernanceEngine engine,
                                 List<ApprovalGate> gates) {
        this.delegate = delegate;
        this.engine = engine;
        this.gates = gates != null ? List.copyOf(gates) : List.of();
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        // Find BEFORE_TASK and AFTER_TASK gates
        List<ApprovalGate> beforeTaskGates = gates.stream()
                .filter(g -> g.trigger() == GateTrigger.BEFORE_TASK)
                .toList();

        List<ApprovalGate> afterTaskGates = gates.stream()
                .filter(g -> g.trigger() == GateTrigger.AFTER_TASK)
                .toList();

        // If no task-level gates, delegate directly (zero overhead)
        if (beforeTaskGates.isEmpty() && afterTaskGates.isEmpty()) {
            logger.debug("No task-level governance gates configured, delegating directly");
            return delegate.execute(tasks, inputs, swarmId);
        }

        // Check BEFORE_TASK gates for each task before delegating
        for (Task task : tasks) {
            for (ApprovalGate gate : beforeTaskGates) {
                GovernanceContext context = GovernanceContext.of(swarmId, task.getId(), null);
                logger.info("Checking BEFORE_TASK gate '{}' for task '{}'", gate.name(), task.getId());
                engine.checkGate(gate, context);
            }
        }

        // Execute the delegate process
        SwarmOutput output = delegate.execute(tasks, inputs, swarmId);

        // Check AFTER_TASK gates for each task after execution
        for (Task task : tasks) {
            for (ApprovalGate gate : afterTaskGates) {
                GovernanceContext context = GovernanceContext.of(swarmId, task.getId(), null);
                logger.info("Checking AFTER_TASK gate '{}' for task '{}'", gate.name(), task.getId());
                engine.checkGate(gate, context);
            }
        }

        return output;
    }

    @Override
    public ProcessType getType() {
        return delegate.getType();
    }

    @Override
    public boolean isAsync() {
        return delegate.isAsync();
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        delegate.validateTasks(tasks);
    }

    @Override
    public String interpolateInputs(String template, Map<String, Object> inputs) {
        return delegate.interpolateInputs(template, inputs);
    }

    /**
     * Returns the wrapped delegate process.
     */
    public Process getDelegate() {
        return delegate;
    }

    /**
     * Returns the configured gates.
     */
    public List<ApprovalGate> getGates() {
        return gates;
    }
}
