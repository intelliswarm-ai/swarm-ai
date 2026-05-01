package ai.intelliswarm.swarmai.agent.streaming;

import ai.intelliswarm.swarmai.task.output.TaskOutput;

/**
 * Reactive stream events emitted by {@code Agent.executeTaskStreaming(...)} and
 * {@code Swarm.runStreaming(...)}. Sealed so consumers can {@code switch} on
 * the event subtype with exhaustive matching:
 *
 * <pre>{@code
 * agent.executeTaskStreaming(task, ctx)
 *      .subscribe(evt -> switch (evt) {
 *          case AgentEvent.AgentStarted s   -> ui.markBusy(s.role());
 *          case AgentEvent.TextDelta d      -> ui.appendText(d.text());
 *          case AgentEvent.ToolCallStart t  -> ui.showToolBanner(t.toolName());
 *          case AgentEvent.ToolCallEnd t    -> ui.hideToolBanner(t.toolName());
 *          case AgentEvent.AgentFinished f  -> ui.markIdle(f.taskOutput());
 *          case AgentEvent.AgentError e     -> ui.showError(e.message());
 *      });
 * }</pre>
 *
 * <p>Schema mirrors the standard set used by Inngest AgentKit and Mastra so a
 * shared frontend hook (e.g. {@code useAgent()} for React, or any
 * {@code EventSource}-based JS client) can render output across multiple
 * agent frameworks with minimal adapter code.
 *
 * <p>Every event carries an {@link #agentId()} (so downstream consumers can
 * demux events when multiple agents stream concurrently into one merged
 * {@code Flux}) and a {@link #timestamp()} (millis since epoch, for ordering
 * and latency analysis on the wire).
 *
 * @since 1.1.0  (token-level streaming)
 */
public sealed interface AgentEvent
        permits AgentEvent.AgentStarted,
                AgentEvent.TextDelta,
                AgentEvent.ToolCallStart,
                AgentEvent.ToolCallEnd,
                AgentEvent.AgentFinished,
                AgentEvent.AgentError {

    /** Agent identity — opaque correlation id, stable for the agent's lifetime. */
    String agentId();

    /** Wall-clock millis since epoch when the framework emitted this event. */
    long timestamp();

    /**
     * Emitted exactly once at the start of {@code executeTaskStreaming(...)}.
     * Useful for the UI to flip to a "thinking…" state before any deltas arrive.
     *
     * @param role             agent's declared role (e.g. {@code "Mailbox Analyst"})
     * @param taskDescription  the task being started — same string shown in non-streaming logs
     */
    record AgentStarted(
            String agentId,
            String role,
            String taskDescription,
            long timestamp
    ) implements AgentEvent {}

    /**
     * Per-token-or-chunk text delta from the LLM stream. The granularity is
     * whatever Spring AI's {@code ChatModel.stream()} surfaces — typically a
     * single token, sometimes multi-token chunks for some providers.
     *
     * <p>Concatenating every {@code text()} from start to {@link AgentFinished}
     * reconstructs the full response text. Empty deltas are filtered out
     * before emission.
     */
    record TextDelta(
            String agentId,
            String text,
            long timestamp
    ) implements AgentEvent {}

    /**
     * The LLM proposed a tool call; emitted before the tool is actually invoked.
     * (Phase-2 feature — Phase-1 streaming does not yet emit these. Tool calls
     * still happen, just inside {@link #executeTaskReactive} non-streaming flow.)
     *
     * @param toolName       the bean name / function name registered with Spring AI
     * @param argumentsJson  serialized JSON arguments the LLM proposed
     */
    record ToolCallStart(
            String agentId,
            String toolName,
            String argumentsJson,
            long timestamp
    ) implements AgentEvent {}

    /**
     * Tool execution finished. Exactly one of {@code resultJson} / {@code errorMessage}
     * is non-null.
     */
    record ToolCallEnd(
            String agentId,
            String toolName,
            String resultJson,
            String errorMessage,
            long timestamp
    ) implements AgentEvent {}

    /**
     * Terminal event on success. Carries the same {@link TaskOutput} the
     * non-streaming {@code executeTask(...)} would return — including parsed
     * typed output via {@link TaskOutput#as(Class)}.
     */
    record AgentFinished(
            String agentId,
            TaskOutput taskOutput,
            long timestamp
    ) implements AgentEvent {}

    /**
     * Terminal event on failure. The wrapped Flux emits this and then
     * completes (no {@code onError} signal) so SSE consumers don't have to
     * handle a separate error channel — the UI can show the failure inline.
     *
     * @param exceptionType  simple class name of the underlying exception
     * @param message        the exception message, or empty
     */
    record AgentError(
            String agentId,
            String exceptionType,
            String message,
            long timestamp
    ) implements AgentEvent {}
}
