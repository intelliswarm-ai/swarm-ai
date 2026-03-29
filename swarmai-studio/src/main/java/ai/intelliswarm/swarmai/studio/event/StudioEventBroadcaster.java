package ai.intelliswarm.swarmai.studio.event;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import ai.intelliswarm.swarmai.studio.StudioProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Broadcasts SwarmEvents to connected Studio SSE clients in real time.
 *
 * Maintains a list of active SseEmitter connections and a circular buffer
 * of recent events so that newly connected clients can catch up on
 * recent activity before receiving the live stream.
 */
@Component
@ConditionalOnProperty(prefix = "swarmai.studio", name = "enabled", havingValue = "true")
public class StudioEventBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(StudioEventBroadcaster.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    private final StudioProperties properties;
    private final ObjectMapper objectMapper;

    /** All active SSE emitters (unfiltered subscribers). */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** SSE emitters filtered by swarmId. Key = swarmId, Value = list of emitters for that swarm. */
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> filteredEmitters = new LinkedHashMap<>();
    private final ReentrantLock filteredLock = new ReentrantLock();

    /** Circular buffer of recent enriched events for replay. */
    private final LinkedList<Map<String, Object>> eventBuffer = new LinkedList<>();
    private final ReentrantLock bufferLock = new ReentrantLock();

    public StudioEventBroadcaster(StudioProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Creates a new SSE subscription that receives all workflow events.
     * The emitter is initialized with a replay of recent buffered events.
     *
     * @return a configured SseEmitter
     */
    public SseEmitter subscribe() {
        if (emitters.size() >= properties.getMaxSseConnections()) {
            logger.warn("Maximum SSE connections ({}) reached, rejecting new subscriber",
                    properties.getMaxSseConnections());
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new IllegalStateException("Maximum SSE connections reached"));
            return rejected;
        }

        SseEmitter emitter = createEmitter();
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // Replay buffered events
        replayBufferedEvents(emitter, null);

        logger.debug("New SSE subscriber connected (total: {})", emitters.size());
        return emitter;
    }

    /**
     * Creates a new SSE subscription filtered by swarmId.
     * Only events matching the given swarmId will be sent to this emitter.
     *
     * @param swarmId the swarm ID to filter on
     * @return a configured SseEmitter
     */
    public SseEmitter subscribe(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return subscribe();
        }

        int totalConnections = emitters.size() + countFilteredEmitters();
        if (totalConnections >= properties.getMaxSseConnections()) {
            logger.warn("Maximum SSE connections ({}) reached, rejecting filtered subscriber",
                    properties.getMaxSseConnections());
            SseEmitter rejected = new SseEmitter(0L);
            rejected.completeWithError(new IllegalStateException("Maximum SSE connections reached"));
            return rejected;
        }

        SseEmitter emitter = createEmitter();

        filteredLock.lock();
        try {
            filteredEmitters.computeIfAbsent(swarmId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        } finally {
            filteredLock.unlock();
        }

        emitter.onCompletion(() -> removeFilteredEmitter(swarmId, emitter));
        emitter.onTimeout(() -> removeFilteredEmitter(swarmId, emitter));
        emitter.onError(e -> removeFilteredEmitter(swarmId, emitter));

        // Replay buffered events filtered by swarmId
        replayBufferedEvents(emitter, swarmId);

        logger.debug("New filtered SSE subscriber connected for swarmId={}", swarmId);
        return emitter;
    }

    /**
     * Listens for Spring application events of type SwarmEvent.
     * Enriches the event and broadcasts it to all connected SSE clients.
     */
    @EventListener
    public void onSwarmEvent(SwarmEvent event) {
        Map<String, Object> eventData;

        if (event instanceof EnrichedSwarmEvent) {
            eventData = ((EnrichedSwarmEvent) event).toMap();
        } else {
            eventData = new HashMap<>();
            eventData.put("type", event.getType().name());
            eventData.put("message", event.getMessage());
            eventData.put("swarmId", event.getSwarmId());
            eventData.put("eventTime", event.getEventTime().toString());
            if (event.getMetadata() != null && !event.getMetadata().isEmpty()) {
                eventData.put("metadata", event.getMetadata());
            }
        }

        // Add to circular buffer
        addToBuffer(eventData);

        String swarmId = event.getSwarmId();

        // Broadcast to all unfiltered subscribers
        broadcastToEmitters(emitters, eventData);

        // Broadcast to filtered subscribers matching this swarmId
        if (swarmId != null) {
            filteredLock.lock();
            try {
                CopyOnWriteArrayList<SseEmitter> filtered = filteredEmitters.get(swarmId);
                if (filtered != null && !filtered.isEmpty()) {
                    broadcastToEmitters(filtered, eventData);
                }
            } finally {
                filteredLock.unlock();
            }
        }
    }

    /**
     * Returns the number of currently active SSE connections (filtered + unfiltered).
     */
    public int getActiveConnectionCount() {
        return emitters.size() + countFilteredEmitters();
    }

    /**
     * Returns the number of events currently in the replay buffer.
     */
    public int getBufferedEventCount() {
        bufferLock.lock();
        try {
            return eventBuffer.size();
        } finally {
            bufferLock.unlock();
        }
    }

    // ---- Internal helpers ----

    private SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // Send an initial connection event
        try {
            Map<String, Object> connectionEvent = new HashMap<>();
            connectionEvent.put("type", "STUDIO_CONNECTED");
            connectionEvent.put("message", "Connected to SwarmAI Studio event stream");
            connectionEvent.put("timestamp", java.time.Instant.now().toString());

            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(toJson(connectionEvent)));
        } catch (IOException e) {
            logger.debug("Failed to send connection event to new emitter", e);
        }
        return emitter;
    }

    private void addToBuffer(Map<String, Object> eventData) {
        bufferLock.lock();
        try {
            eventBuffer.addLast(eventData);
            while (eventBuffer.size() > properties.getEventBufferSize()) {
                eventBuffer.removeFirst();
            }
        } finally {
            bufferLock.unlock();
        }
    }

    private void replayBufferedEvents(SseEmitter emitter, String swarmIdFilter) {
        List<Map<String, Object>> snapshot;
        bufferLock.lock();
        try {
            snapshot = new ArrayList<>(eventBuffer);
        } finally {
            bufferLock.unlock();
        }

        for (Map<String, Object> eventData : snapshot) {
            if (swarmIdFilter != null) {
                Object eventSwarmId = eventData.get("swarmId");
                if (!swarmIdFilter.equals(eventSwarmId)) {
                    continue;
                }
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("replay")
                        .data(toJson(eventData)));
            } catch (IOException e) {
                logger.debug("Failed to replay buffered event to emitter", e);
                break;
            }
        }
    }

    private void broadcastToEmitters(CopyOnWriteArrayList<SseEmitter> targetEmitters,
                                     Map<String, Object> eventData) {
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : targetEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("swarm-event")
                        .data(toJson(eventData)));
            } catch (IOException | IllegalStateException e) {
                deadEmitters.add(emitter);
            }
        }

        if (!deadEmitters.isEmpty()) {
            targetEmitters.removeAll(deadEmitters);
            logger.debug("Removed {} dead SSE emitters", deadEmitters.size());
        }
    }

    private void removeFilteredEmitter(String swarmId, SseEmitter emitter) {
        filteredLock.lock();
        try {
            CopyOnWriteArrayList<SseEmitter> list = filteredEmitters.get(swarmId);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    filteredEmitters.remove(swarmId);
                }
            }
        } finally {
            filteredLock.unlock();
        }
    }

    private int countFilteredEmitters() {
        filteredLock.lock();
        try {
            return filteredEmitters.values().stream()
                    .mapToInt(CopyOnWriteArrayList::size)
                    .sum();
        } finally {
            filteredLock.unlock();
        }
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("Failed to serialize event data to JSON", e);
            return "{}";
        }
    }
}
