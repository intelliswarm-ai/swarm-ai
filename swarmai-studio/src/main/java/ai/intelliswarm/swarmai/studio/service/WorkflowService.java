package ai.intelliswarm.swarmai.studio.service;

import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service layer for the SwarmAI Studio.
 * Provides workflow listing, detail inspection, graph construction,
 * event querying, and metrics aggregation.
 *
 * Gracefully handles the case where EventStore or DecisionTracer
 * are not available (returns empty results instead of errors).
 */
@Service
public class WorkflowService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowService.class);

    private final EventStore eventStore;
    private final DecisionTracer decisionTracer;

    @Autowired
    public WorkflowService(
            @Autowired(required = false) EventStore eventStore,
            @Autowired(required = false) DecisionTracer decisionTracer) {
        this.eventStore = eventStore;
        this.decisionTracer = decisionTracer;
    }

    /**
     * Lists all workflows with summary information, sorted by start time descending.
     *
     * @return list of workflow summary maps
     */
    public List<Map<String, Object>> listWorkflows() {
        if (eventStore == null) {
            return Collections.emptyList();
        }

        List<String> correlationIds = eventStore.getAllCorrelationIds();
        List<Map<String, Object>> workflows = new ArrayList<>();

        for (String correlationId : correlationIds) {
            try {
                Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
                if (recordingOpt.isPresent()) {
                    WorkflowRecording recording = recordingOpt.get();
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("correlationId", recording.getCorrelationId());
                    summary.put("swarmId", recording.getSwarmId());
                    summary.put("status", recording.getStatus());
                    summary.put("startTime", recording.getStartTime() != null
                            ? recording.getStartTime().toString() : null);
                    summary.put("endTime", recording.getEndTime() != null
                            ? recording.getEndTime().toString() : null);
                    summary.put("durationMs", recording.getDurationMs());
                    summary.put("eventCount", eventStore.getEventCount(correlationId));

                    WorkflowRecording.WorkflowSummary ws = recording.getSummary();
                    if (ws != null) {
                        summary.put("uniqueAgents", ws.getUniqueAgents());
                        summary.put("uniqueTasks", ws.getUniqueTasks());
                        summary.put("totalTokens", ws.getTotalTokens());
                        summary.put("errorCount", ws.getErrorCount());
                    }

                    workflows.add(summary);
                }
            } catch (Exception e) {
                logger.warn("Failed to create recording summary for correlationId={}", correlationId, e);
            }
        }

        // Sort by startTime descending (most recent first)
        workflows.sort((a, b) -> {
            String startA = (String) a.get("startTime");
            String startB = (String) b.get("startTime");
            if (startA == null && startB == null) return 0;
            if (startA == null) return 1;
            if (startB == null) return -1;
            return startB.compareTo(startA);
        });

        return workflows;
    }

    /**
     * Returns the full workflow recording for a given correlation ID.
     *
     * @param correlationId the workflow correlation ID
     * @return full recording map, or empty map if not found
     */
    public Map<String, Object> getWorkflow(String correlationId) {
        if (eventStore == null) {
            return Collections.emptyMap();
        }

        Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
        return recordingOpt.map(WorkflowRecording::toMap).orElse(Collections.emptyMap());
    }

    /**
     * Builds a visual graph representation of the workflow.
     * Extracts agents as circle nodes, tasks as box nodes, and creates
     * edges based on event relationships. Supports all four process types:
     * SEQUENTIAL, HIERARCHICAL, PARALLEL, and ITERATIVE.
     *
     * @param correlationId the workflow correlation ID
     * @return map with "nodes" and "edges" lists
     */
    public Map<String, Object> getWorkflowGraph(String correlationId) {
        if (eventStore == null) {
            return Map.of("nodes", Collections.emptyList(), "edges", Collections.emptyList());
        }

        List<EnrichedSwarmEvent> events = eventStore.getByCorrelationId(correlationId);
        if (events.isEmpty()) {
            return Map.of("nodes", Collections.emptyList(), "edges", Collections.emptyList());
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Extract task IDs from event messages since ObservabilityContext
        // doesn't propagate across threads (agentId/taskId fields are null).
        // Messages follow patterns: "Starting task: <id>", "Completed task: <id>",
        // "Starting parallel task: <id>", "Iteration 1/3 started", etc.
        List<String> taskOrder = new ArrayList<>();
        Map<String, String> taskStatus = new LinkedHashMap<>();
        boolean isParallel = false;
        boolean hasIterations = false;
        int iterationCount = 0;
        List<String> parallelTaskIds = new ArrayList<>();

        for (EnrichedSwarmEvent event : events) {
            String type = event.getType().name();
            String msg = event.getMessage() != null ? event.getMessage() : "";

            // Detect process type from PROCESS_STARTED message
            if (type.equals("PROCESS_STARTED") && msg.toLowerCase().contains("parallel")) {
                isParallel = true;
            }

            // Extract task IDs from messages
            String taskId = extractTaskId(msg);
            if (taskId != null) {
                if (type.equals("TASK_STARTED")) {
                    if (!taskOrder.contains(taskId)) {
                        taskOrder.add(taskId);
                    }
                    taskStatus.put(taskId, "running");
                    if (msg.contains("parallel")) {
                        parallelTaskIds.add(taskId);
                    }
                } else if (type.equals("TASK_COMPLETED")) {
                    taskStatus.put(taskId, "completed");
                } else if (type.equals("TASK_FAILED")) {
                    taskStatus.put(taskId, "failed");
                }
            }

            // Detect iterations
            if (type.startsWith("ITERATION_")) {
                hasIterations = true;
                if (type.equals("ITERATION_STARTED")) {
                    iterationCount++;
                }
            }
        }

        // Build a swarm start node
        String swarmId = events.get(0).getSwarmId();
        nodes.add(makeNode("swarm", swarmId != null ? swarmId : "Swarm", "swarm",
                "circle", "#4fc3f7", Map.of("swarmId", swarmId != null ? swarmId : "")));

        // Build task nodes
        for (int i = 0; i < taskOrder.size(); i++) {
            String tid = taskOrder.get(i);
            String status = taskStatus.getOrDefault(tid, "pending");
            String shortId = tid.length() > 8 ? tid.substring(0, 8) : tid;
            String label = "Task " + (i + 1) + "\n" + shortId;
            nodes.add(makeNode("task-" + tid, label, "task", "box",
                    getStatusColor(status), Map.of("taskId", tid, "status", status, "index", i + 1)));
        }

        // Build edges based on process type
        if (isParallel && parallelTaskIds.size() > 1) {
            // Fan-out from swarm to parallel tasks
            for (String ptid : parallelTaskIds) {
                edges.add(makeEdge("swarm", "task-" + ptid, "parallel", false));
            }
            // Non-parallel tasks (e.g., synthesis) connect from last parallel task
            for (String tid : taskOrder) {
                if (!parallelTaskIds.contains(tid)) {
                    // This is a sequential task after the parallel group
                    for (String ptid : parallelTaskIds) {
                        edges.add(makeEdge("task-" + ptid, "task-" + tid, "join", false));
                    }
                }
            }
        } else if (hasIterations) {
            // For iterative: swarm → first task, tasks chain sequentially
            if (!taskOrder.isEmpty()) {
                edges.add(makeEdge("swarm", "task-" + taskOrder.get(0), "start", false));
            }
            for (int i = 0; i < taskOrder.size() - 1; i++) {
                edges.add(makeEdge("task-" + taskOrder.get(i), "task-" + taskOrder.get(i + 1), "next", false));
            }
            // Add reviewer node and feedback loop
            nodes.add(makeNode("reviewer", "Reviewer\n(iteration " + iterationCount + "x)",
                    "reviewer", "diamond", "#9B59B6", Map.of("iterations", iterationCount)));
            if (!taskOrder.isEmpty()) {
                String lastTask = taskOrder.get(taskOrder.size() - 1);
                edges.add(makeEdge("task-" + lastTask, "reviewer", "review", false));
                edges.add(makeEdge("reviewer", "task-" + taskOrder.get(0), "refine", true));
            }
        } else {
            // Sequential: swarm → task1 → task2 → ...
            if (!taskOrder.isEmpty()) {
                edges.add(makeEdge("swarm", "task-" + taskOrder.get(0), "start", false));
            }
            for (int i = 0; i < taskOrder.size() - 1; i++) {
                edges.add(makeEdge("task-" + taskOrder.get(i), "task-" + taskOrder.get(i + 1), "next", false));
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("processInfo", detectProcessInfo(events));
        return graph;
    }

    /**
     * Extracts a task ID from an event message.
     * Patterns: "Starting task: <id>", "Completed task: <id>",
     * "Starting parallel task: <id>", "Delegated task started: <id> ..."
     */
    private String extractTaskId(String message) {
        if (message == null) return null;
        // Pattern: "...task: <uuid-or-id>"
        int idx = message.lastIndexOf("task: ");
        if (idx >= 0) {
            String after = message.substring(idx + 6).trim();
            // Take until space or end
            int spaceIdx = after.indexOf(' ');
            return spaceIdx > 0 ? after.substring(0, spaceIdx) : after;
        }
        // Pattern: "...task: <id> (iteration N)"
        idx = message.lastIndexOf("task: ");
        if (idx >= 0) {
            String after = message.substring(idx + 6).trim();
            int parenIdx = after.indexOf('(');
            if (parenIdx > 0) return after.substring(0, parenIdx).trim();
        }
        return null;
    }

    private Map<String, Object> makeNode(String id, String label, String type,
                                          String shape, String color, Map<String, Object> metadata) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("label", label);
        node.put("type", type);
        node.put("shape", shape);
        node.put("color", color);
        node.put("group", type + "s");
        node.put("metadata", metadata);
        return node;
    }

    private Map<String, Object> makeEdge(String from, String to, String label, boolean dashes) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("label", label);
        edge.put("dashes", dashes);
        return edge;
    }

    /**
     * Returns the list of events for a workflow, optionally filtered by event type.
     *
     * @param correlationId the workflow correlation ID
     * @param eventType     optional event type filter (e.g., "TASK_STARTED")
     * @return list of event maps
     */
    public List<Map<String, Object>> getWorkflowEvents(String correlationId, String eventType) {
        if (eventStore == null) {
            return Collections.emptyList();
        }

        List<EnrichedSwarmEvent> events;
        if (eventType != null && !eventType.isBlank()) {
            events = eventStore.getByEventType(correlationId, eventType);
        } else {
            events = eventStore.getByCorrelationId(correlationId);
        }

        return events.stream()
                .map(EnrichedSwarmEvent::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Returns detailed event information for a specific task within a workflow.
     *
     * @param correlationId the workflow correlation ID
     * @param taskId        the task ID
     * @return map with task detail including all related events
     */
    public Map<String, Object> getTaskDetail(String correlationId, String taskId) {
        if (eventStore == null) {
            return Collections.emptyMap();
        }

        List<EnrichedSwarmEvent> events = eventStore.getByTaskId(correlationId, taskId);
        if (events.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", taskId);
        detail.put("correlationId", correlationId);
        detail.put("status", determineTaskStatus(events, taskId));
        detail.put("eventCount", events.size());

        // Extract agent info
        events.stream()
                .filter(e -> e.getAgentId() != null)
                .findFirst()
                .ifPresent(e -> {
                    detail.put("agentId", e.getAgentId());
                    detail.put("agentRole", e.getAgentRole());
                });

        // Timing
        events.stream()
                .filter(e -> e.getEventInstant() != null)
                .min(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .ifPresent(e -> detail.put("startTime", e.getEventInstant().toString()));

        events.stream()
                .filter(e -> e.getEventInstant() != null)
                .max(Comparator.comparing(EnrichedSwarmEvent::getEventInstant))
                .ifPresent(e -> detail.put("endTime", e.getEventInstant().toString()));

        // Total duration
        long totalDuration = events.stream()
                .filter(e -> e.getDurationMs() != null)
                .mapToLong(EnrichedSwarmEvent::getDurationMs)
                .sum();
        detail.put("totalDurationMs", totalDuration);

        // Token usage
        long promptTokens = events.stream()
                .filter(e -> e.getPromptTokens() != null)
                .mapToLong(EnrichedSwarmEvent::getPromptTokens)
                .sum();
        long completionTokens = events.stream()
                .filter(e -> e.getCompletionTokens() != null)
                .mapToLong(EnrichedSwarmEvent::getCompletionTokens)
                .sum();
        detail.put("promptTokens", promptTokens);
        detail.put("completionTokens", completionTokens);
        detail.put("totalTokens", promptTokens + completionTokens);

        // Tools used
        Set<String> tools = events.stream()
                .map(EnrichedSwarmEvent::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        detail.put("toolsUsed", new ArrayList<>(tools));

        // Errors
        List<Map<String, Object>> errors = events.stream()
                .filter(e -> e.getType().name().contains("FAILED"))
                .map(e -> {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("errorType", e.getErrorType());
                    error.put("errorMessage", e.getErrorMessage());
                    error.put("timestamp", e.getEventInstant() != null
                            ? e.getEventInstant().toString() : null);
                    return error;
                })
                .collect(Collectors.toList());
        detail.put("errors", errors);

        // Full event timeline
        detail.put("events", events.stream()
                .map(EnrichedSwarmEvent::toMap)
                .collect(Collectors.toList()));

        return detail;
    }

    /**
     * Aggregates overall metrics across all workflows.
     *
     * @return map with metrics overview
     */
    public Map<String, Object> getMetricsOverview() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        if (eventStore == null) {
            metrics.put("totalWorkflows", 0);
            metrics.put("totalEvents", 0);
            metrics.put("totalTokens", 0L);
            metrics.put("successRate", 0.0);
            return metrics;
        }

        List<String> correlationIds = eventStore.getAllCorrelationIds();
        int totalWorkflows = correlationIds.size();
        int totalEvents = eventStore.getTotalEventCount();

        long totalPromptTokens = 0;
        long totalCompletionTokens = 0;
        int completedWorkflows = 0;
        int failedWorkflows = 0;

        for (String correlationId : correlationIds) {
            try {
                Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
                if (recordingOpt.isPresent()) {
                    WorkflowRecording recording = recordingOpt.get();
                    String status = recording.getStatus();

                    if ("completed".equals(status)) {
                        completedWorkflows++;
                    } else if ("failed".equals(status)) {
                        failedWorkflows++;
                    }

                    WorkflowRecording.WorkflowSummary summary = recording.getSummary();
                    if (summary != null) {
                        totalPromptTokens += summary.getTotalPromptTokens();
                        totalCompletionTokens += summary.getTotalCompletionTokens();
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to process metrics for correlationId={}", correlationId, e);
            }
        }

        metrics.put("totalWorkflows", totalWorkflows);
        metrics.put("totalEvents", totalEvents);
        metrics.put("totalPromptTokens", totalPromptTokens);
        metrics.put("totalCompletionTokens", totalCompletionTokens);
        metrics.put("totalTokens", totalPromptTokens + totalCompletionTokens);
        metrics.put("completedWorkflows", completedWorkflows);
        metrics.put("failedWorkflows", failedWorkflows);

        double successRate = totalWorkflows > 0
                ? (double) completedWorkflows / totalWorkflows * 100.0
                : 0.0;
        metrics.put("successRate", Math.round(successRate * 100.0) / 100.0);

        return metrics;
    }

    // ---- Internal helpers ----

    /**
     * Determines the current status of a task based on its events.
     */
    private String determineTaskStatus(List<EnrichedSwarmEvent> allEvents, String taskId) {
        List<EnrichedSwarmEvent> taskEvents = allEvents.stream()
                .filter(e -> taskId.equals(e.getTaskId()))
                .collect(Collectors.toList());

        boolean hasCompleted = taskEvents.stream()
                .anyMatch(e -> "TASK_COMPLETED".equals(e.getType().name()));
        boolean hasFailed = taskEvents.stream()
                .anyMatch(e -> "TASK_FAILED".equals(e.getType().name()));
        boolean hasStarted = taskEvents.stream()
                .anyMatch(e -> "TASK_STARTED".equals(e.getType().name()));
        boolean hasSkipped = taskEvents.stream()
                .anyMatch(e -> "TASK_SKIPPED".equals(e.getType().name()));

        if (hasFailed) return "failed";
        if (hasCompleted) return "completed";
        if (hasSkipped) return "skipped";
        if (hasStarted) return "running";
        return "pending";
    }

    /**
     * Returns the color code for a task status.
     */
    private String getStatusColor(String status) {
        switch (status) {
            case "completed": return "#27AE60";  // green
            case "running":   return "#F39C12";  // amber
            case "failed":    return "#E74C3C";  // red
            case "skipped":   return "#95A5A6";  // light gray
            case "pending":
            default:          return "#BDC3C7";  // gray
        }
    }

    /**
     * Builds metadata map for a task node.
     */
    private Map<String, Object> buildTaskMetadata(List<EnrichedSwarmEvent> allEvents, String taskId) {
        List<EnrichedSwarmEvent> taskEvents = allEvents.stream()
                .filter(e -> taskId.equals(e.getTaskId()))
                .collect(Collectors.toList());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", taskId);
        metadata.put("eventCount", taskEvents.size());

        // Duration
        taskEvents.stream()
                .filter(e -> e.getDurationMs() != null)
                .mapToLong(EnrichedSwarmEvent::getDurationMs)
                .max()
                .ifPresent(d -> metadata.put("durationMs", d));

        // Token usage
        long tokens = taskEvents.stream()
                .filter(e -> e.getPromptTokens() != null && e.getCompletionTokens() != null)
                .mapToLong(e -> e.getPromptTokens() + e.getCompletionTokens())
                .sum();
        if (tokens > 0) {
            metadata.put("totalTokens", tokens);
        }

        // Tools used
        Set<String> tools = taskEvents.stream()
                .map(EnrichedSwarmEvent::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (!tools.isEmpty()) {
            metadata.put("toolsUsed", new ArrayList<>(tools));
        }

        return metadata;
    }

    /**
     * Detects process type information from events.
     */
    private Map<String, Object> detectProcessInfo(List<EnrichedSwarmEvent> events) {
        Map<String, Object> info = new LinkedHashMap<>();

        // Try to detect from metadata
        events.stream()
                .filter(e -> "PROCESS_STARTED".equals(e.getType().name()))
                .findFirst()
                .ifPresent(e -> {
                    Map<String, Object> metadata = e.getMetadata();
                    if (metadata != null && metadata.get("processType") != null) {
                        info.put("processType", metadata.get("processType"));
                    }
                });

        // Heuristic detection if not in metadata
        if (!info.containsKey("processType")) {
            boolean hasIterations = events.stream()
                    .anyMatch(e -> e.getType().name().startsWith("ITERATION_"));
            boolean hasDelegation = events.stream()
                    .anyMatch(e -> {
                        Map<String, Object> meta = e.getMetadata();
                        return meta != null && meta.containsKey("delegatedBy");
                    });

            if (hasIterations) {
                info.put("processType", "ITERATIVE");
            } else if (hasDelegation) {
                info.put("processType", "HIERARCHICAL");
            } else {
                info.put("processType", "SEQUENTIAL");
            }
        }

        // Counts
        long uniqueAgents = events.stream()
                .map(EnrichedSwarmEvent::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        long uniqueTasks = events.stream()
                .map(EnrichedSwarmEvent::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        info.put("agentCount", uniqueAgents);
        info.put("taskCount", uniqueTasks);
        info.put("eventCount", events.size());

        return info;
    }

    /**
     * Truncates a label string to fit within graph node display.
     */
    private String truncateLabel(String text, int maxLength) {
        if (text == null) return "unknown";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
}
