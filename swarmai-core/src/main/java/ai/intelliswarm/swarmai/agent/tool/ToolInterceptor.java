package ai.intelliswarm.swarmai.agent.tool;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;

/**
 * In-process middleware that wraps every tool invocation. Multiple
 * implementations compose into a chain ordered by {@link #order()} (ascending).
 *
 * <p>{@link #before} runs first — may block the call or rewrite its
 * arguments. {@link #after} runs last — may rewrite the result. Defaults
 * are no-ops so implementers only override what they need.
 *
 * <p>Typical uses:
 * <ul>
 *   <li>audit logging (record every tool call to a sink)</li>
 *   <li>argument redaction (strip secrets from before/after payloads)</li>
 *   <li>cross-file mutation serialisation (lock per path before file writes)</li>
 *   <li>per-tool rate limiting (cap calls/minute for expensive tools)</li>
 *   <li>response truncation (cap large tool outputs at a token budget)</li>
 * </ul>
 *
 * <p>Inspired by the {@code beforeToolCall}/{@code afterToolCall} hook
 * shape in the <a href="https://github.com/earendil-works/pi">pi</a> agent
 * toolkit, adapted to the swarm-ai SPI.
 *
 * @since 1.0.28
 */
@PublicApi(since = "1.0.28")
public interface ToolInterceptor {

    /**
     * Called before a tool runs. Default returns {@link BeforeResult#proceed()}.
     *
     * @param toolName the registered tool function name
     * @param args     the LLM-supplied argument map (immutable view)
     */
    default BeforeResult before(String toolName, Map<String, Object> args) {
        return BeforeResult.proceed();
    }

    /**
     * Called after a tool runs (whether it succeeded or returned an error
     * string). Default returns {@link AfterResult#unchanged()}.
     *
     * @param toolName the registered tool function name
     * @param args     the (possibly-rewritten) argument map the tool received
     * @param result   the tool's output ({@code String} or structured payload)
     */
    default AfterResult after(String toolName, Map<String, Object> args, Object result) {
        return AfterResult.unchanged();
    }

    /**
     * Ordering hint — smaller values run earlier. Defaults to 0. Use negative
     * to run before built-ins (permission, audit), positive to run after.
     */
    default int order() {
        return 0;
    }

    /**
     * Outcome of {@link #before}. Use {@link #proceed()} (no change),
     * {@link #block(String)} (stop the call with a reason that goes back to
     * the LLM as the result), or {@link #rewriteArgs(Map)} (continue with a
     * mutated argument map — useful for redaction or default-value injection).
     */
    @PublicApi(since = "1.0.28")
    record BeforeResult(boolean block, String blockReason, Map<String, Object> rewrittenArgs) {

        private static final BeforeResult PROCEED = new BeforeResult(false, null, null);

        public static BeforeResult proceed() { return PROCEED; }

        public static BeforeResult block(String reason) {
            return new BeforeResult(true, reason, null);
        }

        public static BeforeResult rewriteArgs(Map<String, Object> args) {
            return new BeforeResult(false, null, args);
        }
    }

    /**
     * Outcome of {@link #after}. Use {@link #unchanged()} (let the result
     * through) or {@link #replace(Object)} (substitute the result the LLM
     * sees — useful for redaction, formatting, or post-processing).
     */
    @PublicApi(since = "1.0.28")
    record AfterResult(boolean replace, Object replacementResult) {

        private static final AfterResult UNCHANGED = new AfterResult(false, null);

        public static AfterResult unchanged() { return UNCHANGED; }

        public static AfterResult replace(Object result) {
            return new AfterResult(true, result);
        }
    }
}
