package ai.intelliswarm.swarmai.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Core orchestrator for the Workflow Governance Engine.
 *
 * Coordinates approval gate checks during workflow execution. When a gate is triggered,
 * this engine delegates to the {@link ApprovalGateHandler} and either allows the workflow
 * to proceed or throws a {@link GovernanceException} if the gate is rejected.
 */
public class WorkflowGovernanceEngine {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowGovernanceEngine.class);

    private final ApprovalGateHandler gateHandler;
    private final ApplicationEventPublisher eventPublisher;

    public WorkflowGovernanceEngine(ApprovalGateHandler gateHandler,
                                    ApplicationEventPublisher eventPublisher) {
        this.gateHandler = gateHandler;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Checks an approval gate. Blocks the calling thread until the gate is approved,
     * rejected, or times out.
     *
     * @param gate    the approval gate to check
     * @param context the workflow state at the time the gate was triggered
     * @throws GovernanceException if the gate is rejected or times out without auto-approval
     */
    public void checkGate(ApprovalGate gate, GovernanceContext context) {
        logger.info("Governance gate entered: gate='{}', trigger={}, swarmId='{}', taskId='{}'",
                gate.name(), gate.trigger(), context.swarmId(), context.taskId());

        ApprovalRequest result = gateHandler.requestApproval(gate, context);

        if (result.isEffectivelyApproved()) {
            logger.info("Governance gate passed: gate='{}', requestId='{}', status={}",
                    gate.name(), result.getRequestId(), result.getStatus());
        } else {
            logger.warn("Governance gate rejected: gate='{}', requestId='{}', status={}, reason='{}'",
                    gate.name(), result.getRequestId(), result.getStatus(), result.getReason());
            throw new GovernanceException(
                    "Governance gate '" + gate.name() + "' rejected: " + result.getReason(),
                    gate.gateId(),
                    result.getRequestId(),
                    result.getStatus()
            );
        }
    }

    /**
     * Enforces a budget warning gate. Same behavior as {@link #checkGate} but
     * provides semantic clarity for budget-specific gates.
     *
     * @param gate    the budget warning gate to check
     * @param context the workflow state including budget information in metadata
     * @throws GovernanceException if the gate is rejected or times out without auto-approval
     */
    public void enforceBudgetGate(ApprovalGate gate, GovernanceContext context) {
        logger.info("Budget governance gate triggered: gate='{}', swarmId='{}'",
                gate.name(), context.swarmId());
        checkGate(gate, context);
    }

    /**
     * Returns the underlying gate handler for direct request management.
     */
    public ApprovalGateHandler getGateHandler() {
        return gateHandler;
    }
}
