package ai.intelliswarm.swarmai.llm.anthropic;

import ai.intelliswarm.swarmai.api.PublicApi;

/**
 * Why the model stopped emitting tokens. Mirrors Anthropic's {@code stop_reason}.
 */
@PublicApi(since = "1.0.24")
public enum StopReason {
    /** Natural end of response. */
    END_TURN,
    /** Output token cap reached. */
    MAX_TOKENS,
    /** A stop sequence matched. */
    STOP_SEQUENCE,
    /** Model requested at least one tool — caller must run them and resume. */
    TOOL_USE,
    /** Model paused for inline thinking; resume by replying. */
    PAUSE_TURN,
    /** Refusal or safety stop. */
    REFUSAL,
    /** Anything the SDK returned that this enum doesn't yet cover. */
    UNKNOWN
}
