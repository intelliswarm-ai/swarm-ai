package ai.intelliswarm.swarmai.state;

/**
 * A hook that intercepts workflow execution at specific {@link HookPoint}s.
 * Hooks provide a unified mechanism for cross-cutting concerns, replacing
 * the previous fragmented approach of Spring AOP aspects, decorator patterns,
 * and inline code in process implementations.
 *
 * <p>Hooks receive a {@link HookContext} containing the current state and
 * return a (possibly modified) state. This enables hooks to:
 * <ul>
 *   <li>Log or trace execution (observability)</li>
 *   <li>Record token usage (budget tracking)</li>
 *   <li>Check approval gates (governance)</li>
 *   <li>Modify state between tasks</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * SwarmGraph.create()
 *     .addHook(HookPoint.BEFORE_TASK, ctx -> {
 *         System.out.println("Starting task: " + ctx.taskId());
 *         return ctx.state();  // pass through unchanged
 *     })
 *     .addHook(HookPoint.AFTER_TASK, ctx -> {
 *         return ctx.state().withValue("lastCompleted", ctx.taskId());
 *     })
 *     .compile();
 * }</pre>
 *
 * @param <S> the state type (extends AgentState)
 */
@FunctionalInterface
public interface SwarmHook<S extends AgentState> {

    /**
     * Called when the associated {@link HookPoint} is reached during execution.
     *
     * @param context the execution context including current state
     * @return the state to continue with (may be the same or modified)
     */
    S apply(HookContext<S> context);
}
