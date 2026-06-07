package ai.intelliswarm.swarmai.agent.permission;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.permission.PermissionDecision;

/**
 * Agent-level permission posture. Decides what to do when
 * {@link ai.intelliswarm.swarmai.tool.permission.PermissionRules} returns
 * {@link PermissionDecision#UNMATCHED} (no rule matched).
 *
 * <p>Modes (matches Claude Code's surface):
 * <ul>
 *   <li>{@link #DEFAULT} — read-only tools allowed; everything else asks</li>
 *   <li>{@link #ACCEPT_EDITS} — write tools auto-accepted; dangerous still asks</li>
 *   <li>{@link #PLAN} — read tools allowed; write/dangerous tools denied (plan-only)</li>
 *   <li>{@link #AUTO} — defer to an external classifier (LLM or policy engine)</li>
 *   <li>{@link #DONT_ASK} — unmatched tools are denied (strict allowlist mode)</li>
 *   <li>{@link #BYPASS_PERMISSIONS} — all unmatched tools allowed (yolo)</li>
 * </ul>
 *
 * <p>Deny rules always win regardless of mode — modes only shape the
 * {@link PermissionDecision#UNMATCHED} fallback.
 */
@PublicApi(since = "1.0.24")
public enum PermissionMode {
    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    AUTO,
    DONT_ASK,
    BYPASS_PERMISSIONS;

    /**
     * Resolve {@link PermissionDecision#UNMATCHED} into a concrete decision based
     * on this mode and the tool's declared {@link PermissionLevel}. Other
     * decision values pass through unchanged.
     *
     * <p>{@link #AUTO} is not resolved here — it always passes through and the
     * runtime is expected to consult an external classifier before proceeding.
     */
    public PermissionDecision resolve(PermissionDecision decision, PermissionLevel toolLevel) {
        if (decision != PermissionDecision.UNMATCHED) {
            return decision;
        }
        PermissionLevel level = toolLevel != null ? toolLevel : PermissionLevel.READ_ONLY;
        return switch (this) {
            case DEFAULT -> level == PermissionLevel.READ_ONLY
                    ? PermissionDecision.ALLOW
                    : PermissionDecision.ASK;
            case ACCEPT_EDITS -> switch (level) {
                case READ_ONLY, WORKSPACE_WRITE -> PermissionDecision.ALLOW;
                case DANGEROUS, REQUIRES_APPROVAL -> PermissionDecision.ASK;
            };
            case PLAN -> level == PermissionLevel.READ_ONLY
                    ? PermissionDecision.ALLOW
                    : PermissionDecision.DENY;
            case AUTO -> PermissionDecision.UNMATCHED; // external classifier decides
            case DONT_ASK -> PermissionDecision.DENY;
            case BYPASS_PERMISSIONS -> PermissionDecision.ALLOW;
        };
    }
}
