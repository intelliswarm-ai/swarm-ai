package ai.intelliswarm.swarmai.tool.base;

/**
 * Result returned by a {@link ToolHook} to control tool execution flow.
 *
 * <p>Pre-hooks can:
 * <ul>
 *   <li>{@link Action#ALLOW} — proceed with tool execution</li>
 *   <li>{@link Action#DENY} — block execution and return the message as output</li>
 *   <li>{@link Action#WARN} — log a warning but proceed</li>
 * </ul>
 *
 * <p>Post-hooks can:
 * <ul>
 *   <li>{@link Action#ALLOW} — pass through original output</li>
 *   <li>{@link Action#DENY} — replace output with hook message (for filtering)</li>
 *   <li>{@link Action#WARN} — log a warning, optionally replace output</li>
 * </ul>
 *
 * @param action         the action to take
 * @param message        human-readable message (logged or returned to LLM on DENY)
 * @param modifiedOutput replacement output (only used by post-hooks, null to keep original)
 */
public record ToolHookResult(
        Action action,
        String message,
        String modifiedOutput
) {
    public enum Action {
        ALLOW,
        DENY,
        WARN
    }

    /** Allow execution (or pass through output). */
    public static ToolHookResult allow() {
        return new ToolHookResult(Action.ALLOW, null, null);
    }

    /** Deny execution with a reason. */
    public static ToolHookResult deny(String reason) {
        return new ToolHookResult(Action.DENY, reason, null);
    }

    /** Warn but allow, with an optional message. */
    public static ToolHookResult warn(String message) {
        return new ToolHookResult(Action.WARN, message, null);
    }

    /** Allow but replace the output. */
    public static ToolHookResult withModifiedOutput(String newOutput) {
        return new ToolHookResult(Action.ALLOW, null, newOutput);
    }
}
