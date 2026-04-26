package ai.intelliswarm.swarmai.tool.safety;

/**
 * Thrown by {@link KillSwitch#checkOrThrow()} when the global tool kill-switch is set.
 * Tools should let this propagate (or catch it and convert to a user-facing
 * {@code "Tools disabled: …"} string).
 */
public class KillSwitchEngagedException extends RuntimeException {
    public KillSwitchEngagedException(String message) {
        super(message);
    }
}
