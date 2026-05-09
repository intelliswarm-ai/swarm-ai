package ai.intelliswarm.swarmai.agent.wait;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 3 of the pause-resume primitive: a Spring web {@code @RestController}
 * that exposes a {@link SignalDispatcher} via HTTP. External systems (CI
 * webhooks, batch-job completion hooks, manual operator commands via
 * {@code curl}) deliver signals by POSTing to the configured base path.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/signals/{key}} — body becomes the signal payload (text/plain
 *       or empty); returns 200 with {@code delivered=true|false} so the caller
 *       knows whether a wait was matched.</li>
 *   <li>{@code GET /api/signals/_pending} — diagnostics: list of pending waits.</li>
 *   <li>{@code GET /api/signals/_health} — basic liveness check.</li>
 * </ul>
 *
 * <h2>Security</h2>
 * This controller has no built-in auth. Wire it behind your usual security
 * filter (Spring Security, an API gateway, network ACL). Production deployments
 * should also rate-limit the {@code POST} path and constrain the {@code key}
 * pattern to prevent unbounded namespace pollution.
 *
 * <h2>Wiring</h2>
 * Loaded automatically when {@code spring-boot-starter-web} is on the
 * classpath AND the harness has a {@link SignalDispatcher} bean. Disable
 * with the property {@code swarmai.signal.webhook.enabled=false}.
 *
 * @see WaitForSignalTool the agent-side counterpart that registers the waits
 * @see WallClockSignalSource sibling that drives the same dispatcher from a wall-clock timer
 */
@RestController
@RequestMapping("${swarmai.signal.webhook.base-path:/api/signals}")
public final class SignalWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(SignalWebhookController.class);

    private final SignalDispatcher dispatcher;
    private final int maxPayloadBytes;

    public SignalWebhookController(SignalDispatcher dispatcher,
                                    @Value("${swarmai.signal.webhook.max-payload-bytes:65536}") int maxPayloadBytes) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.maxPayloadBytes = maxPayloadBytes;
    }

    /**
     * Deliver a signal. Content type is intentionally permissive — the body
     * (if any) becomes the signal payload as a string.
     */
    @PostMapping(value = "/{key}",
                 consumes = MediaType.ALL_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> signal(
            @PathVariable("key") String key,
            @RequestBody(required = false) String payload) {
        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(error("key must not be blank"));
        }
        String body = (payload == null) ? "" : payload;
        if (body.length() > maxPayloadBytes) {
            return ResponseEntity.badRequest().body(error(
                    "payload exceeds max-payload-bytes=" + maxPayloadBytes
                            + " (got " + body.length() + ")"));
        }
        boolean delivered;
        try {
            delivered = dispatcher.deliver(key, body);
        } catch (RuntimeException e) {
            logger.warn("dispatcher.deliver({}) failed: {}", key, e.getMessage());
            return ResponseEntity.internalServerError().body(error(e.getMessage()));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", key);
        response.put("delivered", delivered);
        response.put("payloadBytes", body.length());
        if (!delivered) {
            response.put("note", "no matching pending wait — signal dropped");
        }
        return ResponseEntity.ok(response);
    }

    /** Diagnostic: list every pending wait with status + age. */
    @GetMapping(value = "/_pending", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> pending() {
        List<WaitForSignalRequest> waits = dispatcher.pendingWaits();
        List<Map<String, Object>> rows = new java.util.ArrayList<>(waits.size());
        for (WaitForSignalRequest w : waits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", w.id());
            row.put("signalKey", w.signalKey());
            row.put("reason", w.reason());
            row.put("pausedAt", w.pausedAt().toString());
            w.expiresAtOpt().ifPresent(exp -> row.put("expiresAt", exp.toString()));
            rows.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pendingCount", waits.size());
        response.put("droppedSignals", dispatcher.droppedSignals());
        response.put("waits", rows);
        return ResponseEntity.ok(response);
    }

    /** Liveness check. */
    @GetMapping(value = "/_health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "up");
        response.put("pendingCount", dispatcher.pendingCount());
        return ResponseEntity.ok(response);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
