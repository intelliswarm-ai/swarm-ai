package ai.intelliswarm.swarmai.tool.safety;

import java.util.Objects;

/**
 * Decorator that short-circuits every {@link #check(MutationPlan)} call to
 * an immediate rejection while {@link KillSwitch#isDisabled()} is true.
 * Wrap this around the rest of the chain so a flipped switch silences the
 * approval prompt and audit log entirely.
 */
public class KillSwitchMutationGuard implements MutationGuard {

    private final MutationGuard delegate;

    public KillSwitchMutationGuard(MutationGuard delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Decision check(MutationPlan plan) {
        if (KillSwitch.isDisabled()) {
            return Decision.reject("SYSTEM",
                    "Kill-switch engaged (" + KillSwitch.ENV + " / " + KillSwitch.PROP + ")");
        }
        return delegate.check(plan);
    }
}
