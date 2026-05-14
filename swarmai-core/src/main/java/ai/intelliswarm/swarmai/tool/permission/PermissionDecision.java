package ai.intelliswarm.swarmai.tool.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Outcome of {@link PermissionRules#evaluate(PermissionRequest)} before mode
 * resolution. The runtime resolves {@link #UNMATCHED} into a concrete action
 * based on the active {@link ai.intelliswarm.swarmai.agent.permission.PermissionMode}.
 *
 * <ul>
 *   <li>{@link #ALLOW} — at least one allow rule matched and no deny rule did</li>
 *   <li>{@link #ASK} — an ask rule matched (and no deny did); the runtime should
 *       prompt the user before proceeding</li>
 *   <li>{@link #DENY} — a deny rule matched (deny rules always win)</li>
 *   <li>{@link #UNMATCHED} — no rule matched; defer to mode default</li>
 * </ul>
 */
@PublicApi(since = "1.0.24")
public enum PermissionDecision {
    ALLOW,
    ASK,
    DENY,
    UNMATCHED
}
