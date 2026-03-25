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

        // Track unique agents and tasks
        Map<String, Map<String, Object>> agentNodes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> taskNodes = new LinkedHashMap<>();

        // Track task-to-agent assignments
        Map<String, String> taskToAgent = new LinkedHashMap<>();

        // Track task ordering for sequential edges
        List<String> taskOrder = new ArrayList<>();

        // Track iteration events for ITERATIVE process
        boolean hasIterations = false;
        Set<String> iterationTaskIds = new LinkedHashSet<>();
        String reviewerAgentId = null;

        // Track parallel groups
        Map<String, Set<String>> parallelGroups = new LinkedHashMap<>();
        String currentParallelGroup = null;

        // Track hierarchical delegation
        Map<String, String> delegationEdges = new LinkedHashMap<>();

        for (EnrichedSwarmEvent event : events) {
            String type = event.getType().name();
            String agentId = event.getAgentId();
            String agentRole = event.getAgentRole();
            String taskId = event.getTaskId();

            // Build agent nodes
            if (agentId != null && !agentNodes.containsKey(agentId)) {
                Map<String, Object> agentNode = new LinkedHashMap<>();
                agentNode.put("id", "agent-" + agentId);
                agentNode.put("label", agentRole != null ? agentRole : agentId);
                agentNode.put("type", "agent");
                agentNode.put("shape", "circle");
                agentNode.put("color", "#4A90D9");
                agentNode.put("group", "agents");
                agentNode.put("metadata", Map.of(
                        "agentId", agentId,
                        "agentRole", agentRole != null ? agentRole : ""
                ));
                agentNodes.put(agentId, agentNode);
            }

            // Build task nodes and track relationships
            if (taskId != null) {
                if (!taskNodes.containsKey(taskId)) {
                    String status = determineTaskStatus(events, taskId);
                    String color = getStatusColor(status);

                    Map<String, Object> taskNode = new LinkedHashMap<>();
                    taskNode.put("id", "task-" + taskId);
                    taskNode.put("label", truncateLabel(event.getMessage(), 40));
                    taskNode.put("type", "task");
                    taskNode.put("shape", "box");
                    taskNode.put("status", status);
                    taskNode.put("color", color);
                    taskNode.put("group", "tasks");
                    taskNode.put("metadata", buildTaskMetadata(events, taskId));
                    taskNodes.put(taskId, taskNode);
                    taskOrder.add(taskId);
                }

                // Track agent-to-task assignments
                if (agentId != null && type.equals("TASK_STARTED")) {
                    taskToAgent.put(taskId, agentId);
                }
            }

            // Detect iteration events
            if (type.startsWith("ITERATION_")) {
                hasIterations = true;
                if (taskId != null) {
                    iterationTaskIds.add(taskId);
                }
            }

            // Detect reviewer for iterative workflows
            if (type.equals("ITERATION_REVIEW_PASSED") || type.equals("ITERATION_REVIEW_FAILED")) {
                if (agentId != null) {
                    reviewerAgentId = agentId;
                }
            }

            // Detect parallel process grouping (tasks started close together or via PROCESS events)
            if (type.equals("PROCESS_STARTED")) {
                Map<String, Object> metadata = event.getMetadata();
                if (metadata != null) {
                    Object processType = metadata.get("processType");
                    if ("PARALLEL".equals(processType) || "parallel".equals(processType)) {
                        currentParallelGroup = "parallel-" + (parallelGroups.size() + 1);
                        parallelGroups.put(currentParallelGroup, new LinkedHashSet<>());
                    }
                }
            }

            // Track tasks within parallel groups
            if (currentParallelGroup != null && type.equals("TASK_STARTED") && taskId != null) {
                parallelGroups.get(currentParallelGroup).add(taskId);
            }

            if (type.equals("PROCESS_COMPLETED") && currentParallelGroup != null) {
                currentParallelGroup = null;
            }

            // Detect hierarchical delegation
            if (type.equals("AGENT_STARTED") && agentId != null) {
                Map<String, Object> metadata = event.getMetadata();
                if (metadata != null) {
                    Object delegatedBy = metadata.get("delegatedBy");
                    if (delegatedBy instanceof String) {
                        delegationEdges.put(agentId, (String) delegatedBy);
                    }
                }
            }
        }

        // Add all nodes
        nodes.addAll(agentNodes.values());
        nodes.addAll(taskNodes.values());

        // Add reviewer node for iterative process
        if (hasIterations && reviewerAgentId != null) {
            Map<String, Object> reviewerNode = new LinkedHashMap<>();
            reviewerNode.put("id", "reviewer-" + reviewerAgentId);
            String reviewerRole = agentNodes.containsKey(reviewerAgentId)
                    ? (String) agentNodes.get(reviewerAgentId).get("label")
                    : reviewerAgentId;
            reviewerNode.put("label", reviewerRole + " (Reviewer)");
            reviewerNode.put("type", "reviewer");
            reviewerNode.put("shape", "diamond");
            reviewerNode.put("color", "#9B59B6");
            reviewerNode.put("group", "reviewers");
            reviewerNode.put("metadata", Map.of("agentId", reviewerAgentId, "role", "reviewer"));
            nodes.add(reviewerNode);
        }

        // Build edges: agent -> task assignments
        for (Map.Entry<String, String> entry : taskToAgent.entrySet()) {
            String taskId = entry.getKey();
            String agentId = entry.getValue();

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", "agent-" + agentId);
            edge.put("to", "task-" + taskId);
            edge.put("label", "executes");
            edge.put("dashes", false);
            edges.add(edge);
        }

        // Build edges: sequential task flow
        Set<String> parallelTaskIds = parallelGroups.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        for (int i = 0; i < taskOrder.size() - 1; i++) {
            String fromTask = taskOrder.get(i);
            String toTask = taskOrder.get(i + 1);

            // Skip sequential edges between tasks that are in the same parallel group
            if (parallelTaskIds.contains(fromTask) && parallelTaskIds.contains(toTask)) {
                boolean sameGroup = parallelGroups.values().stream()
                        .anyMatch(group -> group.contains(fromTask) && group.contains(toTask));
                if (sameGroup) {
                    continue;
                }
            }

            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", "task-" + fromTask);
            edge.put("to", "task-" + toTask);
            edge.put("label", "next");
            edge.put("dashes", false);
            edges.add(edge);
        }

        // Build edges: iteration feedback (backward edges)
        if (hasIterations) {
            for (String iterTaskId : iterationTaskIds) {
                if (reviewerAgentId != null) {
                    // Task -> reviewer
                    Map<String, Object> toReviewer = new LinkedHashMap<>();
                    toReviewer.put("from", "task-" + iterTaskId);
                    toReviewer.put("to", "reviewer-" + reviewerAgentId);
                    toReviewer.put("label", "submit for review");
                    toReviewer.put("dashes", false);
                    edges.add(toReviewer);

                    // Reviewer -> task (feedback/refinement edge, dashed)
                    Map<String, Object> fromReviewer = new LinkedHashMap<>();
                    fromReviewer.put("from", "reviewer-" + reviewerAgentId);
                    fromReviewer.put("to", "task-" + iterTaskId);
                    fromReviewer.put("label", "refinement");
                    fromReviewer.put("dashes", true);
                    edges.add(fromReviewer);
                }
            }
        }

        // Build edges: hierarchical delegation
        for (Map.Entry<String, String> entry : delegationEdges.entrySet()) {
            String delegateeId = entry.getKey();
            String delegatorId = entry.getValue();

            if (agentNodes.containsKey(delegatorId) && agentNodes.containsKey(delegateeId)) {
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("from", "agent-" + delegatorId);
                edge.put("to", "agent-" + delegateeId);
                edge.put("label", "delegates");
                edge.put("dashes", false);
                edges.add(edge);
            }
        }

        // Build edges: parallel fan-out / fan-in
        for (Map.Entry<String, Set<String>> group : parallelGroups.entrySet()) {
            Set<String> parallelTasks = group.getValue();
            if (parallelTasks.size() <= 1) continue;

            // Find the task that precedes this parallel group
            String firstParallelTask = parallelTasks.iterator().next();
            int firstIdx = taskOrder.indexOf(firstParallelTask);

            if (firstIdx > 0) {
                String precedingTask = taskOrder.get(firstIdx - 1);
                // Fan-out edges from preceding task to all parallel tasks
                for (String pTask : parallelTasks) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", "task-" + precedingTask);
                    edge.put("to", "task-" + pTask);
                    edge.put("label", "parallel");
                    edge.put("dashes", false);
                    edges.add(edge);
                }
            }

            // Find the task that follows this parallel group
            String lastParallelTask = null;
            int maxIdx = -1;
            for (String pTask : parallelTasks) {
                int idx = taskOrder.indexOf(pTask);
                if (idx > maxIdx) {
                    maxIdx = idx;
                    lastParallelTask = pTask;
                }
            }

            if (lastParallelTask != null && maxIdx < taskOrder.size() - 1) {
                String followingTask = taskOrder.get(maxIdx + 1);
                // Fan-in edges from all parallel tasks to the following task
                for (String pTask : parallelTasks) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("from", "task-" + pTask);
                    edge.put("to", "task-" + followingTask);
                    edge.put("label", "join");
                    edge.put("dashes", false);
                    edges.add(edge);
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        graph.put("processInfo", detectProcessInfo(events));
        return graph;
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
