package ai.intelliswarm.swarmai.state;

import java.util.Map;
import java.util.Optional;

/**
 * Context passed to {@link SwarmHook} implementations during execution.
 * Contains the current state, the hook point that triggered, and contextual
 * information about what is being executed.
 *
 * @param <S> the state type
 */
public record HookContext<S extends AgentState>(
        HookPoint hookPoint,
        S state,
        String workflowId,
        String taskId,
        String toolName,
        Map<String, Object> metadata,
        Throwable error
) {
    /**
     * Creates a context for workflow-level hooks.
     */
    public static <S extends AgentState> HookContext<S> forWorkflow(
            HookPoint point, S state, String workflowId) {
        return new HookContext<>(point, state, workflowId, null, null, Map.of(), null);
    }

    /**
     * Creates a context for task-level hooks.
     */
    public static <S extends AgentState> HookContext<S> forTask(
            HookPoint point, S state, String workflowId, String taskId) {
        return new HookContext<>(point, state, workflowId, taskId, null, Map.of(), null);
    }

    /**
     * Creates a context for tool-level hooks.
     */
    public static <S extends AgentState> HookContext<S> forTool(
            HookPoint point, S state, String workflowId, String toolName) {
        return new HookContext<>(point, state, workflowId, null, toolName, Map.of(), null);
    }

    /**
     * Creates a context for error hooks.
     */
    public static <S extends AgentState> HookContext<S> forError(
            S state, String workflowId, Throwable error) {
        return new HookContext<>(HookPoint.ON_ERROR, state, workflowId, null, null, Map.of(), error);
    }

    /**
     * Returns the task ID if present.
     */
    public Optional<String> getTaskId() {
        return Optional.ofNullable(taskId);
    }

    /**
     * Returns the tool name if present.
     */
    public Optional<String> getToolName() {
        return Optional.ofNullable(toolName);
    }

    /**
     * Returns the error if this is an error context.
     */
    public Optional<Throwable> getError() {
        return Optional.ofNullable(error);
    }
}
