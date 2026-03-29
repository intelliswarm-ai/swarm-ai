package ai.intelliswarm.swarmai.budget;

/**
 * Thrown when a workflow exceeds its configured budget and the policy action is
 * {@link BudgetPolicy.BudgetAction#HARD_STOP}.
 */
public class BudgetExceededException extends RuntimeException {

    private final String workflowId;
    private final BudgetSnapshot snapshot;

    public BudgetExceededException(String workflowId, BudgetSnapshot snapshot) {
        super(formatMessage(workflowId, snapshot));
        this.workflowId = workflowId;
        this.snapshot = snapshot;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public BudgetSnapshot getSnapshot() {
        return snapshot;
    }

    private static String formatMessage(String workflowId, BudgetSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Budget exceeded for workflow '").append(workflowId).append("': ");
        if (snapshot.tokenBudgetExceeded()) {
            sb.append(String.format("tokens %,d used (%.1f%%)",
                    snapshot.totalTokensUsed(), snapshot.tokenUtilizationPercent()));
        }
        if (snapshot.tokenBudgetExceeded() && snapshot.costBudgetExceeded()) {
            sb.append(", ");
        }
        if (snapshot.costBudgetExceeded()) {
            sb.append(String.format("cost $%.4f used (%.1f%%)",
                    snapshot.estimatedCostUsd(), snapshot.costUtilizationPercent()));
        }
        return sb.toString();
    }
}
