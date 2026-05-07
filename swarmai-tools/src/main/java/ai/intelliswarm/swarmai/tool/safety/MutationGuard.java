package ai.intelliswarm.swarmai.tool.safety;

import java.util.Objects;

/**
 * Gatekeeper that decides whether a tool may proceed with a {@link MutationPlan}.
 * The default implementation in supervised mode delegates to a human approver via
 * {@link ai.intelliswarm.swarmai.governance.ApprovalGateHandler}.
 *
 * <p>Tools call {@link #check(MutationPlan)} immediately before they execute any
 * mutating operation, and only proceed if the returned {@link Decision#approved()}
 * is {@code true}. Implementations are expected to block while a human or policy
 * decides — callers should treat this as a synchronous, potentially long-running call.
 */
public interface MutationGuard {

    /**
     * Inspect a plan and return whether the tool may proceed.
     * Implementations may block until a human or policy has decided.
     */
    Decision check(MutationPlan plan);

    /**
     * Observe the outcome of a previously-checked plan: the tool either ran
     * to completion or failed mid-way. The {@code correlationId} ties the
     * outcome back to the plan that {@link #check(MutationPlan)} approved.
     *
     * <p>Default implementation is a no-op so existing guards in the chain
     * need not be modified. {@link PlanAwareMutationGuard} overrides this to
     * transition its plan-channel entry to {@link
     * ai.intelliswarm.swarmai.state.plan.PlanEntry.Status#EXECUTED} on
     * success or {@link
     * ai.intelliswarm.swarmai.state.plan.PlanEntry.Status#REJECTED} on
     * failure.
     *
     * <p>Calls with a {@code null} or unknown {@code correlationId} are
     * silently ignored — that's the case when the guard chain doesn't
     * include a plan-aware decorator, so tools can call this unconditionally.
     *
     * @param correlationId the value returned by {@link Decision#correlationId()}
     *                      from the matching {@link #check(MutationPlan)} call
     * @param success       {@code true} if the operation completed successfully;
     *                      {@code false} if it threw or returned a failure
     * @param reason        human-readable detail (e.g. error message); may be null
     */
    default void observe(String correlationId, boolean success, String reason) {
        // no-op
    }

    /**
     * Outcome of a guard decision.
     *
     * @param approved      whether the plan is allowed to proceed
     * @param decidedBy     identity of the approver (or {@code "SYSTEM"} for policy/timeout decisions)
     * @param reason        human-readable explanation; never null (use empty string when none)
     * @param correlationId opaque token tying this decision to a later
     *                      {@link MutationGuard#observe} call. May be {@code null}
     *                      when the guard chain has no plan-aware decorator.
     */
    record Decision(boolean approved, String decidedBy, String reason, String correlationId) {
        public Decision {
            Objects.requireNonNull(decidedBy, "decidedBy");
            reason = reason != null ? reason : "";
            // correlationId may be null
        }

        /** Convenience constructor that omits {@code correlationId}. */
        public Decision(boolean approved, String decidedBy, String reason) {
            this(approved, decidedBy, reason, null);
        }

        public static Decision approve(String decidedBy, String reason) {
            return new Decision(true, decidedBy, reason, null);
        }

        public static Decision reject(String decidedBy, String reason) {
            return new Decision(false, decidedBy, reason, null);
        }

        public static Decision approve(String decidedBy, String reason, String correlationId) {
            return new Decision(true, decidedBy, reason, correlationId);
        }

        public static Decision reject(String decidedBy, String reason, String correlationId) {
            return new Decision(false, decidedBy, reason, correlationId);
        }

        /** Returns a copy of this decision with the given correlation id. */
        public Decision withCorrelationId(String newCorrelationId) {
            return new Decision(approved, decidedBy, reason, newCorrelationId);
        }
    }
}
