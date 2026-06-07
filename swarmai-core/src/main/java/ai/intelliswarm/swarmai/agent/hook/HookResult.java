package ai.intelliswarm.swarmai.agent.hook;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Hook reply. {@link HookAction#MODIFY} carries a {@code modifiedPayload}
 * whose concrete type depends on the event being handled (e.g. a
 * {@code String} for a user-prompt event, a {@code List<ConversationTurn>}
 * for a pre-compact event).
 *
 * <p>The runtime is responsible for casting and validating the payload; this
 * type stays loose to keep one result class usable across every event.
 *
 * @param action          desired outcome
 * @param message         human-readable reason (logged + surfaced when BLOCK / ASK)
 * @param modifiedPayload replacement payload for MODIFY; {@code null} otherwise
 */
@PublicApi(since = "1.0.24")
public record HookResult(HookAction action, String message, Object modifiedPayload) {

    private static final HookResult PROCEED_INSTANCE =
            new HookResult(HookAction.PROCEED, null, null);

    public static HookResult proceed() {
        return PROCEED_INSTANCE;
    }

    public static HookResult block(String reason) {
        return new HookResult(HookAction.BLOCK, reason, null);
    }

    public static HookResult modify(Object newPayload) {
        return new HookResult(HookAction.MODIFY, null, newPayload);
    }

    public static HookResult modify(Object newPayload, String reason) {
        return new HookResult(HookAction.MODIFY, reason, newPayload);
    }

    public static HookResult ask(String prompt) {
        return new HookResult(HookAction.ASK, prompt, null);
    }
}
