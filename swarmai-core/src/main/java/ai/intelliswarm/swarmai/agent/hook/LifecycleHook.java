package ai.intelliswarm.swarmai.agent.hook;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Agent lifecycle hook — receives {@link LifecycleEvent}s at well-defined
 * points in an agent run. Implementations override only the methods they care
 * about; all default to {@link HookResult#proceed()}.
 *
 * <p>This is the SwarmAI counterpart to Claude Code's hook framework. Hooks
 * fire at:
 * <ul>
 *   <li><b>Prompt time</b> — {@link #onUserPromptSubmit(LifecycleEvent.UserPromptSubmit)}
 *       can rewrite or block the prompt before the LLM sees it</li>
 *   <li><b>Session boundaries</b> — {@link #onSessionStart(LifecycleEvent.SessionStart)} /
 *       {@link #onSessionEnd(LifecycleEvent.SessionEnd)} for audit, setup, teardown</li>
 *   <li><b>Compaction</b> — {@link #onPreCompact(LifecycleEvent.PreCompact)} can
 *       BLOCK to skip a compaction pass; {@link #onPostCompact(LifecycleEvent.PostCompact)}
 *       can audit what was thrown away</li>
 *   <li><b>Stop boundary</b> — {@link #onStop(LifecycleEvent.Stop)} can BLOCK
 *       to force another turn (e.g. "you didn't cite your sources, keep going")</li>
 *   <li><b>Subagent lifecycle</b> — bracket parallel work, enforce delegation-depth policy</li>
 *   <li><b>Task list</b> — TaskCreated / TaskCompleted lets hooks validate the plan</li>
 *   <li><b>Filesystem</b> — FileChanged for write-audit</li>
 *   <li><b>Permissions</b> — PermissionRequest / PermissionDenied (alternate path
 *       to the rule DSL, useful for programmatic decisions)</li>
 *   <li><b>Notifications</b> — generic event sink (Slack, PagerDuty, etc.)</li>
 * </ul>
 *
 * <p>Hooks are dispatched by {@link LifecycleHookRegistry}. Multiple hooks may
 * be registered; the registry stops at the first non-PROCEED action.
 */
@PublicApi(since = "1.0.24")
public interface LifecycleHook {

    default HookResult onUserPromptSubmit(LifecycleEvent.UserPromptSubmit event) { return HookResult.proceed(); }
    default HookResult onSessionStart(LifecycleEvent.SessionStart event)          { return HookResult.proceed(); }
    default HookResult onSessionEnd(LifecycleEvent.SessionEnd event)              { return HookResult.proceed(); }
    default HookResult onPreCompact(LifecycleEvent.PreCompact event)              { return HookResult.proceed(); }
    default HookResult onPostCompact(LifecycleEvent.PostCompact event)            { return HookResult.proceed(); }
    default HookResult onStop(LifecycleEvent.Stop event)                          { return HookResult.proceed(); }
    default HookResult onSubagentStart(LifecycleEvent.SubagentStart event)        { return HookResult.proceed(); }
    default HookResult onSubagentStop(LifecycleEvent.SubagentStop event)          { return HookResult.proceed(); }
    default HookResult onTaskCreated(LifecycleEvent.TaskCreated event)            { return HookResult.proceed(); }
    default HookResult onTaskCompleted(LifecycleEvent.TaskCompleted event)        { return HookResult.proceed(); }
    default HookResult onFileChanged(LifecycleEvent.FileChanged event)            { return HookResult.proceed(); }
    default HookResult onPermissionRequest(LifecycleEvent.PermissionRequest event){ return HookResult.proceed(); }
    default HookResult onPermissionDenied(LifecycleEvent.PermissionDenied event)  { return HookResult.proceed(); }
    default HookResult onNotification(LifecycleEvent.Notification event)          { return HookResult.proceed(); }

    /**
     * Dispatch entry point — default implementation pattern-matches on event type
     * and calls the per-event override. Override this method directly for fully
     * dynamic handling (e.g. a hook that fans every event into a structured log).
     */
    default HookResult onEvent(LifecycleEvent event) {
        return switch (event) {
            case LifecycleEvent.UserPromptSubmit e -> onUserPromptSubmit(e);
            case LifecycleEvent.SessionStart e -> onSessionStart(e);
            case LifecycleEvent.SessionEnd e -> onSessionEnd(e);
            case LifecycleEvent.PreCompact e -> onPreCompact(e);
            case LifecycleEvent.PostCompact e -> onPostCompact(e);
            case LifecycleEvent.Stop e -> onStop(e);
            case LifecycleEvent.SubagentStart e -> onSubagentStart(e);
            case LifecycleEvent.SubagentStop e -> onSubagentStop(e);
            case LifecycleEvent.TaskCreated e -> onTaskCreated(e);
            case LifecycleEvent.TaskCompleted e -> onTaskCompleted(e);
            case LifecycleEvent.FileChanged e -> onFileChanged(e);
            case LifecycleEvent.PermissionRequest e -> onPermissionRequest(e);
            case LifecycleEvent.PermissionDenied e -> onPermissionDenied(e);
            case LifecycleEvent.Notification e -> onNotification(e);
        };
    }
}
