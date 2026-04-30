package ai.intelliswarm.swarmai.agent.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;

/**
 * Utility bridge from a {@code Flux<AgentEvent>} to one of Spring's HTTP
 * server-sent-event surfaces. Two flavours, one method each:
 *
 * <ul>
 *   <li>{@link #toSseEmitter(Flux)} — for <b>Spring MVC</b> controllers that
 *       return {@code SseEmitter}. Subscribes to the upstream Flux on the
 *       caller's thread and forwards each event with a name matching its
 *       subtype ({@code agent_started}, {@code text_delta}, etc.) so a
 *       JavaScript {@code EventSource}'s {@code addEventListener('text_delta', …)}
 *       picks them up cleanly.</li>
 *   <li>{@link #toServerSentEvents(Flux)} — for <b>WebFlux</b> controllers that
 *       return {@code Flux<ServerSentEvent<AgentEvent>>}. Reactive end-to-end,
 *       no subscription bridging needed.</li>
 * </ul>
 *
 * <p>Both name events identically — the SSE event-name strings are part of the
 * public contract so frontend code can move between MVC and WebFlux backends
 * without changing.
 *
 * <p>JSON serialization of the {@link AgentEvent} payload is whatever Spring's
 * {@code MappingJackson2HttpMessageConverter} (MVC) or
 * {@code Jackson2JsonEncoder} (WebFlux) is configured with — typically Jackson
 * with the Java time / Jdk8 / parameter-names modules from
 * {@code spring-boot-starter-json}, which round-trips records cleanly.
 *
 * @since 1.1.0
 */
public final class AgentEventSse {

    private static final Logger logger = LoggerFactory.getLogger(AgentEventSse.class);

    /**
     * Stable string names for each {@link AgentEvent} subtype, used as the SSE
     * {@code event:} field. Frontend listeners subscribe by these names.
     */
    public static String eventName(AgentEvent e) {
        return switch (e) {
            case AgentEvent.AgentStarted ignored   -> "agent_started";
            case AgentEvent.TextDelta ignored      -> "text_delta";
            case AgentEvent.ToolCallStart ignored  -> "tool_call_start";
            case AgentEvent.ToolCallEnd ignored    -> "tool_call_end";
            case AgentEvent.AgentFinished ignored  -> "agent_finished";
            case AgentEvent.AgentError ignored     -> "agent_error";
        };
    }

    /**
     * Bridge to the servlet-stack {@link SseEmitter}. Default heartbeat is
     * {@code 0L} (never timeout — the underlying servlet container's read
     * timeout governs idle teardown).
     */
    public static SseEmitter toSseEmitter(Flux<AgentEvent> stream) {
        return toSseEmitter(stream, 0L);
    }

    /**
     * Bridge to the servlet-stack {@link SseEmitter} with an explicit emitter
     * timeout (millis; 0 = no timeout).
     */
    public static SseEmitter toSseEmitter(Flux<AgentEvent> stream, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);

        Disposable subscription = stream.subscribe(
                evt -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(eventName(evt))
                                .data(evt));
                    } catch (IOException io) {
                        // Client disconnected mid-stream — most common case. Tear down.
                        logger.debug("AgentEventSse: client write failed, completing emitter: {}",
                                io.getMessage());
                        emitter.completeWithError(io);
                    }
                },
                err -> {
                    // Defensive — Agent.executeTaskStreaming() already converts errors
                    // to terminal AgentError events on the success channel, so this
                    // branch is rarely reached. Logged at warn level if it does fire.
                    logger.warn("AgentEventSse: upstream Flux signalled onError ({}); "
                            + "completing emitter with error", err.toString());
                    emitter.completeWithError(err);
                },
                emitter::complete);

        // Wire bidirectional cleanup: if the HTTP client disconnects (timeout /
        // tab closed / browser crashed), the SseEmitter signals it via these
        // callbacks — we then dispose the upstream subscription so the LLM call
        // is cancelled and tokens stop being generated.
        emitter.onTimeout(subscription::dispose);
        emitter.onError(t -> subscription.dispose());
        emitter.onCompletion(subscription::dispose);
        return emitter;
    }

    /**
     * Bridge to a WebFlux-style {@code Flux<ServerSentEvent<AgentEvent>>}.
     * Each upstream event becomes one SSE message with its event-name and
     * the raw {@link AgentEvent} as the data payload.
     *
     * <p>Heartbeat is not added here — let WebFlux ping clients via its built-in
     * keep-alive. Add a {@code .mergeWith(Flux.interval(...).map(...))} in the
     * controller if your reverse-proxy needs explicit comments to keep the
     * connection alive (most don't with HTTP/2).
     */
    public static Flux<ServerSentEvent<AgentEvent>> toServerSentEvents(Flux<AgentEvent> stream) {
        return stream.map(evt -> ServerSentEvent.<AgentEvent>builder(evt)
                .event(eventName(evt))
                .build());
    }

    /**
     * Same as {@link #toServerSentEvents(Flux)} but emits a periodic comment
     * heartbeat so reverse proxies (nginx etc.) with idle timeouts don't
     * close a quiet stream. The comments are SSE-protocol-level and ignored
     * by browser EventSource implementations.
     */
    public static Flux<ServerSentEvent<AgentEvent>> toServerSentEventsWithHeartbeat(
            Flux<AgentEvent> stream, Duration heartbeatInterval) {
        Flux<ServerSentEvent<AgentEvent>> events = toServerSentEvents(stream);
        Flux<ServerSentEvent<AgentEvent>> heartbeats = Flux.interval(heartbeatInterval)
                .map(tick -> ServerSentEvent.<AgentEvent>builder()
                        .comment("heartbeat")
                        .build());
        return Flux.merge(events, heartbeats).takeUntilOther(events.then());
    }

    private AgentEventSse() { }
}
