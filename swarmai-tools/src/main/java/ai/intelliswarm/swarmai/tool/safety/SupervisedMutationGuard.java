yespackage ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.governance.ApprovalGate;
import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.governance.ApprovalRequest;
import ai.intelliswarm.swarmai.governance.GateTrigger;
import ai.intelliswarm.swarmai.governance.GovernanceContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Default {@link MutationGuard} for supervised mode: every plan is turned into
 * an {@link ApprovalGate} and routed through the existing {@link ApprovalGateHandler}.
 * The guard blocks until the request is approved, rejected, or times out, then
 * maps the {@link ApprovalRequest} back to a {@link Decision}.
 *
 * <p>Timeout behaviour is delegated to the configured handler / policy: this class
 * only supplies the gate's max wait time. A request that times out is mapped to
 * {@code rejected} unless the handler explicitly auto-approved it (per its policy).
 */
public class SupervisedMutationGuard implements MutationGuard {

    private final ApprovalGateHandler approvalHandler;
    private final Duration timeout;
    private final String tenantId;

    /**
     * @param approvalHandler the handler that delivers gates to approvers
     * @param timeout         how long to wait for a human decision before timing out
     * @param tenantId        tenant identifier for multi-tenant filtering; may be {@code "default"}
     */
    public SupervisedMutationGuard(ApprovalGateHandler approvalHandler,
                                   Duration timeout,
                                   String tenantId) {
        this.approvalHandler = Objects.requireNonNull(approvalHandler, "approvalHandler");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.tenantId = tenantId != null ? tenantId : "default";
    }

    @Override
    public Decision check(MutationPlan plan) {
        Objects.requireNonNull(plan, "plan");

        ApprovalGate gate = ApprovalGate.builder()
                .name("tool:" + plan.toolName())
                .description(plan.summary())
                .trigger(GateTrigger.BEFORE_TASK)
                .timeout(timeout)
                .build();

        GovernanceContext context = new GovernanceContext(
                /* swarmId */ "tool-mutation",
                /* taskId */ plan.toolName(),
                tenantId,
                /* iteration */ 0,
                buildContextMetadata(plan));

        ApprovalRequest result = approvalHandler.requestApproval(gate, context);

        String decidedBy = result.getDecidedBy() != null ? result.getDecidedBy() : "SYSTEM";
        String reason = result.getReason() != null ? result.getReason() : "";

        return result.isEffectivelyApproved()
                ? Decision.approve(decidedBy, reason)
                : Decision.reject(decidedBy, reason);
    }

    private static Map<String, Object> buildContextMetadata(MutationPlan plan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tool", plan.toolName());
        metadata.put("riskLevel", plan.riskLevel().name());
        metadata.put("opCount", plan.opCount());
        metadata.put("summary", plan.summary());
        metadata.put("ops", summariseOps(plan.ops()));
        if (!plan.metadata().isEmpty()) {
            metadata.put("toolMetadata", plan.metadata());
        }
        return metadata;
    }

    private static List<Map<String, Object>> summariseOps(List<MutationPlan.Op> ops) {
        return ops.stream()
                .map(op -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", op.type());
                    m.put("target", op.target());
                    if (!op.details().isEmpty()) {
                        m.put("details", op.details());
                    }
                    return m;
                })
                .collect(Collectors.toUnmodifiableList());
    }
}
