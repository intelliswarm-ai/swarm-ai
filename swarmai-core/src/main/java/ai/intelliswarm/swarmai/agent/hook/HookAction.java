package ai.intelliswarm.swarmai.agent.hook;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * What a {@link LifecycleHook} wants the runtime to do with the event it
 * just inspected.
 *
 * <ul>
 *   <li>{@link #PROCEED} — continue as if the hook hadn't been there</li>
 *   <li>{@link #MODIFY} — accept the hook's transformed payload and continue
 *       (e.g. rewrite a user prompt before the LLM sees it)</li>
 *   <li>{@link #BLOCK} — refuse to continue; the runtime surfaces the hook's
 *       message back to the user or LLM</li>
 *   <li>{@link #ASK} — defer to the user (used by permission hooks)</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum HookAction {
    PROCEED,
    MODIFY,
    BLOCK,
    ASK
}
