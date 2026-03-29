package ai.intelliswarm.swarmai.observability.replay;

import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Serializable workflow recording for replay and analysis.
 * Contains all events, timeline, and summary statistics.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowRecording implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final String correlationId;
    private final String swarmId;
    private final Instant startTime;
    private final Instant endTime;
    private final long durationMs;
    private final String status;

    private final List<EventRecord> timeline;
    private final WorkflowSummary summary;
    private final Map<String, Object> configuration;

    private WorkflowRecording(Builder builder) {
        this.correlationId = builder.correlationId;
        this.swarmId = builder.swarmId;
        this.startTime = builder.startTime;
        this.endTime = builder.endTime;
        this.durationMs = builder.durationMs;
        this.status = builder.status;
        this.timeline = builder.timeline != null ? new ArrayList<>(builder.timeline) : new ArrayList<>();
        this.summary = builder.summary;
        this.configuration = builder.configuration != null ? new HashMap<>(builder.configuration) : new HashMap<>();
    }

    /**
     * Creates a recording from a list of enriched events.
     */
    public static WorkflowRecording fromEvents(List<EnrichedSwarmEvent> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("Events list cannot be null or empty");
        }

        // Sort events by timestamp
        List<EnrichedSwarmEvent> sortedEvents = events.stream()
                .sorted(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .collect(Collectors.toList());

        EnrichedSwarmEvent firstEvent = sortedEvents.get(0);
        EnrichedSwarmEvent lastEvent = sortedEvents.get(sortedEvents.size() - 1);

        String correlationId = firstEvent.getCorrelationId();
        String swarmId = firstEvent.getSwarmId();
        Instant startTime = firstEvent.getEventInstant();
        Instant endTime = lastEvent.getEventInstant();

        // Convert events to timeline
        List<EventRecord> timeline = sortedEvents.stream()
                .map(EventRecord::fromEvent)
                .collect(Collectors.toList());

        // Determine status
        String status = determineStatus(sortedEvents);

        // Calculate summary
        WorkflowSummary summary = calculateSummary(sortedEvents);

        return builder()
                .correlationId(correlationId)
                .swarmId(swarmId)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(endTime.toEpochMilli() - startTime.toEpochMilli())
                .status(status)
                .timeline(timeline)
                .summary(summary)
                .build();
    }

    private static String determineStatus(List<EnrichedSwarmEvent> events) {
        boolean hasCompleted = events.stream()
                .anyMatch(e -> e.getType().name().contains("COMPLETED"));
        boolean hasFailed = events.stream()
                .anyMatch(e -> e.getType().name().contains("FAILED"));

        if (hasFailed) return "failed";
        if (hasCompleted) return "completed";
        return "unknown";
    }

    private static WorkflowSummary calculateSummary(List<EnrichedSwarmEvent> events) {
        int totalEvents = events.size();

        Set<String> uniqueAgents = events.stream()
                .map(EnrichedSwarmEvent::getAgentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> uniqueTasks = events.stream()
                .map(EnrichedSwarmEvent::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<String> uniqueTools = events.stream()
                .map(EnrichedSwarmEvent::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        long totalPromptTokens = events.stream()
                .filter(e -> e.getPromptTokens() != null)
                .mapToLong(EnrichedSwarmEvent::getPromptTokens)
                .sum();

        long totalCompletionTokens = events.stream()
                .filter(e -> e.getCompletionTokens() != null)
                .mapToLong(EnrichedSwarmEvent::getCompletionTokens)
                .sum();

        long totalDurationMs = events.stream()
                .filter(e -> e.getDurationMs() != null)
                .mapToLong(EnrichedSwarmEvent::getDurationMs)
                .sum();

        int errorCount = (int) events.stream()
                .filter(e -> e.getType().name().contains("FAILED"))
                .count();

        return new WorkflowSummary(
                totalEvents,
                uniqueAgents.size(),
                uniqueTasks.size(),
                uniqueTools.size(),
                totalPromptTokens,
                totalCompletionTokens,
                totalDurationMs,
                errorCount
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Serializes the recording to JSON.
     */
    public String toJson() {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize recording to JSON", e);
        }
    }

    /**
     * Deserializes a recording from JSON.
     */
    public static WorkflowRecording fromJson(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = OBJECT_MAPPER.readValue(json, Map.class);
            return fromMap(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize recording from JSON", e);
        }
    }

    /**
     * Saves the recording to a file.
     */
    public void saveToFile(File file) throws IOException {
        try (Writer writer = new FileWriter(file)) {
            writer.write(toJson());
        }
    }

    /**
     * Loads a recording from a file.
     */
    public static WorkflowRecording loadFromFile(File file) throws IOException {
        try (Reader reader = new FileReader(file)) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
            return fromJson(content.toString());
        }
    }

    /**
     * Converts the recording to a map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("correlationId", correlationId);
        map.put("swarmId", swarmId);
        map.put("startTime", startTime != null ? startTime.toString() : null);
        map.put("endTime", endTime != null ? endTime.toString() : null);
        map.put("durationMs", durationMs);
        map.put("status", status);
        map.put("timeline", timeline.stream().map(EventRecord::toMap).collect(Collectors.toList()));
        map.put("summary", summary != null ? summary.toMap() : null);
        map.put("configuration", configuration);
        return map;
    }

    /**
     * Creates a recording from a map.
     */
    @SuppressWarnings("unchecked")
    public static WorkflowRecording fromMap(Map<String, Object> map) {
        Builder builder = builder()
                .correlationId((String) map.get("correlationId"))
                .swarmId((String) map.get("swarmId"))
                .durationMs(((Number) map.get("durationMs")).longValue())
                .status((String) map.get("status"));

        if (map.get("startTime") != null) {
            builder.startTime(Instant.parse((String) map.get("startTime")));
        }
        if (map.get("endTime") != null) {
            builder.endTime(Instant.parse((String) map.get("endTime")));
        }
        if (map.get("timeline") != null) {
            List<Map<String, Object>> timelineList = (List<Map<String, Object>>) map.get("timeline");
            builder.timeline(timelineList.stream()
                    .map(EventRecord::fromMap)
                    .collect(Collectors.toList()));
        }
        if (map.get("summary") != null) {
            builder.summary(WorkflowSummary.fromMap((Map<String, Object>) map.get("summary")));
        }
        if (map.get("configuration") != null) {
            builder.configuration((Map<String, Object>) map.get("configuration"));
        }

        return builder.build();
    }

    // Getters

    public String getCorrelationId() {
        return correlationId;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getStatus() {
        return status;
    }

    public List<EventRecord> getTimeline() {
        return new ArrayList<>(timeline);
    }

    public WorkflowSummary getSummary() {
        return summary;
    }

    public Map<String, Object> getConfiguration() {
        return new HashMap<>(configuration);
    }

    public static class Builder {
        private String correlationId;
        private String swarmId;
        private Instant startTime;
        private Instant endTime;
        private long durationMs;
        private String status;
        private List<EventRecord> timeline;
        private WorkflowSummary summary;
        private Map<String, Object> configuration;

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder swarmId(String swarmId) {
            this.swarmId = swarmId;
            return this;
        }

        public Builder startTime(Instant startTime) {
            this.startTime = startTime;
            return this;
        }

        public Builder endTime(Instant endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder timeline(List<EventRecord> timeline) {
            this.timeline = timeline;
            return this;
        }

        public Builder summary(WorkflowSummary summary) {
            this.summary = summary;
            return this;
        }

        public Builder configuration(Map<String, Object> configuration) {
            this.configuration = configuration;
            return this;
        }

        public WorkflowRecording build() {
            return new WorkflowRecording(this);
        }
    }

    /**
     * Single event record in the timeline.
     */
    public static class EventRecord implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String eventType;
        private final String message;
        private final Instant timestamp;
        private final Long elapsedMs;
        private final Long durationMs;
        private final String agentId;
        private final String agentRole;
        private final String taskId;
        private final String toolName;
        private final String status;
        private final String errorType;
        private final Map<String, Object> attributes;

        public EventRecord(
                String eventType,
                String message,
                Instant timestamp,
                Long elapsedMs,
                Long durationMs,
                String agentId,
                String agentRole,
                String taskId,
                String toolName,
                String status,
                String errorType,
                Map<String, Object> attributes) {
            this.eventType = eventType;
            this.message = message;
            this.timestamp = timestamp;
            this.elapsedMs = elapsedMs;
            this.durationMs = durationMs;
            this.agentId = agentId;
            this.agentRole = agentRole;
            this.taskId = taskId;
            this.toolName = toolName;
            this.status = status;
            this.errorType = errorType;
            this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
        }

        public static EventRecord fromEvent(EnrichedSwarmEvent event) {
            return new EventRecord(
                    event.getType().name(),
                    event.getMessage(),
                    event.getEventInstant(),
                    event.getElapsedSinceStartMs(),
                    event.getDurationMs(),
                    event.getAgentId(),
                    event.getAgentRole(),
                    event.getTaskId(),
                    event.getToolName(),
                    event.getStatus(),
                    event.getErrorType(),
                    event.getAttributes()
            );
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("eventType", eventType);
            map.put("message", message);
            map.put("timestamp", timestamp != null ? timestamp.toString() : null);
            map.put("elapsedMs", elapsedMs);
            map.put("durationMs", durationMs);
            map.put("agentId", agentId);
            map.put("agentRole", agentRole);
            map.put("taskId", taskId);
            map.put("toolName", toolName);
            map.put("status", status);
            map.put("errorType", errorType);
            map.put("attributes", attributes);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static EventRecord fromMap(Map<String, Object> map) {
            return new EventRecord(
                    (String) map.get("eventType"),
                    (String) map.get("message"),
                    map.get("timestamp") != null ? Instant.parse((String) map.get("timestamp")) : null,
                    map.get("elapsedMs") != null ? ((Number) map.get("elapsedMs")).longValue() : null,
                    map.get("durationMs") != null ? ((Number) map.get("durationMs")).longValue() : null,
                    (String) map.get("agentId"),
                    (String) map.get("agentRole"),
                    (String) map.get("taskId"),
                    (String) map.get("toolName"),
                    (String) map.get("status"),
                    (String) map.get("errorType"),
                    (Map<String, Object>) map.get("attributes")
            );
        }

        // Getters
        public String getEventType() { return eventType; }
        public String getMessage() { return message; }
        public Instant getTimestamp() { return timestamp; }
        public Long getElapsedMs() { return elapsedMs; }
        public Long getDurationMs() { return durationMs; }
        public String getAgentId() { return agentId; }
        public String getAgentRole() { return agentRole; }
        public String getTaskId() { return taskId; }
        public String getToolName() { return toolName; }
        public String getStatus() { return status; }
        public String getErrorType() { return errorType; }
        public Map<String, Object> getAttributes() { return new HashMap<>(attributes); }
    }

    /**
     * Summary statistics for a workflow.
     */
    public static class WorkflowSummary implements Serializable {
        private static final long serialVersionUID = 1L;

        private final int totalEvents;
        private final int uniqueAgents;
        private final int uniqueTasks;
        private final int uniqueTools;
        private final long totalPromptTokens;
        private final long totalCompletionTokens;
        private final long totalDurationMs;
        private final int errorCount;

        public WorkflowSummary(
                int totalEvents,
                int uniqueAgents,
                int uniqueTasks,
                int uniqueTools,
                long totalPromptTokens,
                long totalCompletionTokens,
                long totalDurationMs,
                int errorCount) {
            this.totalEvents = totalEvents;
            this.uniqueAgents = uniqueAgents;
            this.uniqueTasks = uniqueTasks;
            this.uniqueTools = uniqueTools;
            this.totalPromptTokens = totalPromptTokens;
            this.totalCompletionTokens = totalCompletionTokens;
            this.totalDurationMs = totalDurationMs;
            this.errorCount = errorCount;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("totalEvents", totalEvents);
            map.put("uniqueAgents", uniqueAgents);
            map.put("uniqueTasks", uniqueTasks);
            map.put("uniqueTools", uniqueTools);
            map.put("totalPromptTokens", totalPromptTokens);
            map.put("totalCompletionTokens", totalCompletionTokens);
            map.put("totalTokens", totalPromptTokens + totalCompletionTokens);
            map.put("totalDurationMs", totalDurationMs);
            map.put("errorCount", errorCount);
            return map;
        }

        public static WorkflowSummary fromMap(Map<String, Object> map) {
            return new WorkflowSummary(
                    ((Number) map.get("totalEvents")).intValue(),
                    ((Number) map.get("uniqueAgents")).intValue(),
                    ((Number) map.get("uniqueTasks")).intValue(),
                    ((Number) map.get("uniqueTools")).intValue(),
                    ((Number) map.get("totalPromptTokens")).longValue(),
                    ((Number) map.get("totalCompletionTokens")).longValue(),
                    ((Number) map.get("totalDurationMs")).longValue(),
                    ((Number) map.get("errorCount")).intValue()
            );
        }

        // Getters
        public int getTotalEvents() { return totalEvents; }
        public int getUniqueAgents() { return uniqueAgents; }
        public int getUniqueTasks() { return uniqueTasks; }
        public int getUniqueTools() { return uniqueTools; }
        public long getTotalPromptTokens() { return totalPromptTokens; }
        public long getTotalCompletionTokens() { return totalCompletionTokens; }
        public long getTotalTokens() { return totalPromptTokens + totalCompletionTokens; }
        public long getTotalDurationMs() { return totalDurationMs; }
        public int getErrorCount() { return errorCount; }
    }
}
