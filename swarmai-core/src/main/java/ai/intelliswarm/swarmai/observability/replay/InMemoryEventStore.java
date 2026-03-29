package ai.intelliswarm.swarmai.observability.replay;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of EventStore.
 * Suitable for development and testing. Events are lost on restart.
 * Bean created by ObservabilityAutoConfiguration when observability is enabled.
 */
public class InMemoryEventStore implements EventStore {

    private final ObservabilityProperties properties;

    // Primary storage: correlationId -> list of events
    private final Map<String, List<EnrichedSwarmEvent>> eventsByCorrelation;

    // Index: swarmId -> correlationIds
    private final Map<String, Set<String>> correlationsBySwarm;

    public InMemoryEventStore(ObservabilityProperties properties) {
        this.properties = properties;
        this.eventsByCorrelation = new ConcurrentHashMap<>();
        this.correlationsBySwarm = new ConcurrentHashMap<>();
    }

    @Override
    public void store(EnrichedSwarmEvent event) {
        if (!properties.isReplayEnabled()) return;

        String correlationId = event.getCorrelationId();
        if (correlationId == null) {
            return;
        }

        // Enforce max events limit
        int maxEvents = properties.getReplay().getMaxEventsInMemory();
        if (getTotalEventCount() >= maxEvents) {
            evictOldest();
        }

        eventsByCorrelation.computeIfAbsent(correlationId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(event);

        String swarmId = event.getSwarmId();
        if (swarmId != null) {
            correlationsBySwarm.computeIfAbsent(swarmId, k -> ConcurrentHashMap.newKeySet())
                    .add(correlationId);
        }
    }

    @Override
    public List<EnrichedSwarmEvent> getByCorrelationId(String correlationId) {
        List<EnrichedSwarmEvent> events = eventsByCorrelation.get(correlationId);
        if (events == null) {
            return Collections.emptyList();
        }
        return events.stream()
                .sorted(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnrichedSwarmEvent> getBySwarmId(String swarmId) {
        Set<String> correlationIds = correlationsBySwarm.get(swarmId);
        if (correlationIds == null) {
            return Collections.emptyList();
        }

        return correlationIds.stream()
                .flatMap(corrId -> getByCorrelationId(corrId).stream())
                .sorted(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnrichedSwarmEvent> getByAgentId(String correlationId, String agentId) {
        return getByCorrelationId(correlationId).stream()
                .filter(e -> agentId.equals(e.getAgentId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnrichedSwarmEvent> getByTaskId(String correlationId, String taskId) {
        return getByCorrelationId(correlationId).stream()
                .filter(e -> taskId.equals(e.getTaskId()))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnrichedSwarmEvent> getByTimeRange(Instant start, Instant end) {
        return eventsByCorrelation.values().stream()
                .flatMap(List::stream)
                .filter(e -> {
                    Instant ts = e.getEventInstant();
                    return ts != null && !ts.isBefore(start) && ts.isBefore(end);
                })
                .sorted(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .collect(Collectors.toList());
    }

    @Override
    public List<EnrichedSwarmEvent> getByEventType(String correlationId, String eventType) {
        return getByCorrelationId(correlationId).stream()
                .filter(e -> eventType.equals(e.getType().name()))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getAllCorrelationIds() {
        return new ArrayList<>(eventsByCorrelation.keySet());
    }

    @Override
    public int getEventCount(String correlationId) {
        List<EnrichedSwarmEvent> events = eventsByCorrelation.get(correlationId);
        return events != null ? events.size() : 0;
    }

    @Override
    public int getTotalEventCount() {
        return eventsByCorrelation.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    @Override
    public boolean hasEvents(String correlationId) {
        List<EnrichedSwarmEvent> events = eventsByCorrelation.get(correlationId);
        return events != null && !events.isEmpty();
    }

    @Override
    public int deleteByCorrelationId(String correlationId) {
        List<EnrichedSwarmEvent> removed = eventsByCorrelation.remove(correlationId);
        if (removed == null) {
            return 0;
        }

        // Clean up swarm index
        correlationsBySwarm.values().forEach(set -> set.remove(correlationId));

        return removed.size();
    }

    @Override
    public int deleteOlderThan(Instant before) {
        int deleted = 0;

        Iterator<Map.Entry<String, List<EnrichedSwarmEvent>>> iterator =
                eventsByCorrelation.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, List<EnrichedSwarmEvent>> entry = iterator.next();
            List<EnrichedSwarmEvent> events = entry.getValue();

            if (events.isEmpty()) {
                continue;
            }

            // Check if all events in this correlation are older
            boolean allOlder = events.stream()
                    .allMatch(e -> e.getEventInstant() != null && e.getEventInstant().isBefore(before));

            if (allOlder) {
                iterator.remove();
                correlationsBySwarm.values().forEach(set -> set.remove(entry.getKey()));
                deleted += events.size();
            }
        }

        return deleted;
    }

    @Override
    public Optional<WorkflowRecording> createRecording(String correlationId) {
        List<EnrichedSwarmEvent> events = getByCorrelationId(correlationId);
        if (events.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(WorkflowRecording.fromEvents(events));
    }

    @Override
    public void clear() {
        eventsByCorrelation.clear();
        correlationsBySwarm.clear();
    }

    /**
     * Evicts the oldest correlation's events to make room for new ones.
     */
    private void evictOldest() {
        Optional<String> oldest = eventsByCorrelation.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .min(Comparator.comparing(e -> e.getValue().get(0).getTimestamp()))
                .map(Map.Entry::getKey);

        oldest.ifPresent(this::deleteByCorrelationId);
    }

    /**
     * Gets storage statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCorrelations", eventsByCorrelation.size());
        stats.put("totalEvents", getTotalEventCount());
        stats.put("maxEventsAllowed", properties.getReplay().getMaxEventsInMemory());

        // Events per correlation distribution
        DoubleSummaryStatistics eventsPerCorrelation = eventsByCorrelation.values().stream()
                .mapToDouble(List::size)
                .summaryStatistics();

        stats.put("avgEventsPerCorrelation", eventsPerCorrelation.getAverage());
        stats.put("maxEventsInCorrelation", (long) eventsPerCorrelation.getMax());

        return stats;
    }
}
