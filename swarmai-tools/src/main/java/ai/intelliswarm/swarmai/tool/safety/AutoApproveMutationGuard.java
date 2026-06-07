package ai.intelliswarm.swarmai.tool.safety;

/**
 * Guard that approves every plan without consulting a human. Intended for
 * unattended demo runs only. Wrap with {@link AuditingMutationGuard} (and
 * {@link KillSwitchMutationGuard}) so an operator still has post-hoc visibility
 * and a hard stop.
 *
 * <p><b>Do not enable this in production-like setups.</b> An agent with this
 * guard installed will execute every mutating op it can think of with no human
 * review.
 */
public class AutoApproveMutationGuard implements MutationGuard {

    @Override
    public Decision check(MutationPlan plan) {
        return Decision.approve("AUTO", "auto-approve mode");
    }
}
