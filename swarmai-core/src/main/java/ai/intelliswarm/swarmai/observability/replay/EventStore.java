package ai.intelliswarm.swarmai.observability.replay;

import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Interface for storing and retrieving enriched swarm events.
 * Supports event persistence for replay and analysis.
 */
public interface EventStore {

    /**
     * Stores an event.
     *
     * @param event The event to store
     */
    void store(EnrichedSwarmEvent event);

    /**
     * Retrieves all events for a correlation ID.
     *
     * @param correlationId The correlation ID
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getByCorrelationId(String correlationId);

    /**
     * Retrieves all events for a swarm ID.
     *
     * @param swarmId The swarm ID
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getBySwarmId(String swarmId);

    /**
     * Retrieves all events for an agent ID within a correlation.
     *
     * @param correlationId The correlation ID
     * @param agentId The agent ID
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getByAgentId(String correlationId, String agentId);

    /**
     * Retrieves all events for a task ID within a correlation.
     *
     * @param correlationId The correlation ID
     * @param taskId The task ID
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getByTaskId(String correlationId, String taskId);

    /**
     * Retrieves events within a time range.
     *
     * @param start Start of the range (inclusive)
     * @param end End of the range (exclusive)
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getByTimeRange(Instant start, Instant end);

    /**
     * Retrieves events by type within a correlation.
     *
     * @param correlationId The correlation ID
     * @param eventType The event type name
     * @return List of events in chronological order
     */
    List<EnrichedSwarmEvent> getByEventType(String correlationId, String eventType);

    /**
     * Gets all unique correlation IDs in the store.
     *
     * @return List of correlation IDs
     */
    List<String> getAllCorrelationIds();

    /**
     * Gets the count of events for a correlation ID.
     *
     * @param correlationId The correlation ID
     * @return Number of events
     */
    int getEventCount(String correlationId);

    /**
     * Gets the total event count in the store.
     *
     * @return Total number of events
     */
    int getTotalEventCount();

    /**
     * Checks if events exist for a correlation ID.
     *
     * @param correlationId The correlation ID
     * @return true if events exist
     */
    boolean hasEvents(String correlationId);

    /**
     * Deletes all events for a correlation ID.
     *
     * @param correlationId The correlation ID
     * @return Number of events deleted
     */
    int deleteByCorrelationId(String correlationId);

    /**
     * Deletes events older than a certain time.
     *
     * @param before Delete events before this timestamp
     * @return Number of events deleted
     */
    int deleteOlderThan(Instant before);

    /**
     * Creates a workflow recording from stored events.
     *
     * @param correlationId The correlation ID
     * @return Optional containing the recording if events exist
     */
    Optional<WorkflowRecording> createRecording(String correlationId);

    /**
     * Clears all events from the store.
     */
    void clear();
}
