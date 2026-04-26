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
     * Outcome of a guard decision.
     *
     * @param approved   whether the plan is allowed to proceed
     * @param decidedBy  identity of the approver (or {@code "SYSTEM"} for policy/timeout decisions)
     * @param reason     human-readable explanation; never null (use empty string when none)
     */
    record Decision(boolean approved, String decidedBy, String reason) {
        public Decision {
            Objects.requireNonNull(decidedBy, "decidedBy");
            reason = reason != null ? reason : "";
        }

        public static Decision approve(String decidedBy, String reason) {
            return new Decision(true, decidedBy, reason);
        }

        public static Decision reject(String decidedBy, String reason) {
            return new Decision(false, decidedBy, reason);
        }
    }
}
