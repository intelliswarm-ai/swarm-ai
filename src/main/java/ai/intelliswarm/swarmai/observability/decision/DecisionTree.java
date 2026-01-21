package ai.intelliswarm.swarmai.observability.decision;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Complete decision trace for a workflow execution.
 * Contains all decision nodes and provides navigation and analysis capabilities.
 */
public class DecisionTree {

    private final String correlationId;
    private final String swarmId;
    private final Instant startTime;
    private Instant endTime;

    private final List<DecisionNode> nodes;
    private final Map<String, DecisionNode> nodesById;
    private final Map<String, List<DecisionNode>> nodesByAgentId;
    private final Map<String, List<DecisionNode>> nodesByTaskId;

    public DecisionTree(String correlationId, String swarmId) {
        this.correlationId = correlationId;
        this.swarmId = swarmId;
        this.startTime = Instant.now();
        this.nodes = new ArrayList<>();
        this.nodesById = new HashMap<>();
        this.nodesByAgentId = new HashMap<>();
        this.nodesByTaskId = new HashMap<>();
    }

    /**
     * Adds a decision node to the tree.
     */
    public synchronized void addNode(DecisionNode node) {
        nodes.add(node);
        nodesById.put(node.getId(), node);

        if (node.getAgentId() != null) {
            nodesByAgentId.computeIfAbsent(node.getAgentId(), k -> new ArrayList<>()).add(node);
        }

        if (node.getTaskId() != null) {
            nodesByTaskId.computeIfAbsent(node.getTaskId(), k -> new ArrayList<>()).add(node);
        }
    }

    /**
     * Marks the tree as complete.
     */
    public void complete() {
        this.endTime = Instant.now();
    }

    /**
     * Gets all decision nodes in chronological order.
     */
    public List<DecisionNode> getAllNodes() {
        return new ArrayList<>(nodes);
    }

    /**
     * Gets a decision node by its ID.
     */
    public Optional<DecisionNode> getNodeById(String nodeId) {
        return Optional.ofNullable(nodesById.get(nodeId));
    }

    /**
     * Gets all decision nodes for a specific agent.
     */
    public List<DecisionNode> getNodesByAgentId(String agentId) {
        return new ArrayList<>(nodesByAgentId.getOrDefault(agentId, Collections.emptyList()));
    }

    /**
     * Gets all decision nodes for a specific task.
     */
    public List<DecisionNode> getNodesByTaskId(String taskId) {
        return new ArrayList<>(nodesByTaskId.getOrDefault(taskId, Collections.emptyList()));
    }

    /**
     * Gets child nodes of a given parent span.
     */
    public List<DecisionNode> getChildNodes(String parentSpanId) {
        return nodes.stream()
                .filter(node -> parentSpanId.equals(node.getParentSpanId()))
                .collect(Collectors.toList());
    }

    /**
     * Gets the root nodes (nodes without parents).
     */
    public List<DecisionNode> getRootNodes() {
        return nodes.stream()
                .filter(node -> node.getParentSpanId() == null)
                .collect(Collectors.toList());
    }

    /**
     * Gets all unique agent IDs in the tree.
     */
    public Set<String> getUniqueAgentIds() {
        return new HashSet<>(nodesByAgentId.keySet());
    }

    /**
     * Gets all unique task IDs in the tree.
     */
    public Set<String> getUniqueTaskIds() {
        return new HashSet<>(nodesByTaskId.keySet());
    }

    /**
     * Gets summary statistics for this decision tree.
     */
    public DecisionTreeSummary getSummary() {
        long totalLatency = nodes.stream()
                .mapToLong(DecisionNode::getLatencyMs)
                .sum();

        long avgLatency = nodes.isEmpty() ? 0 : totalLatency / nodes.size();

        Set<String> allToolsUsed = nodes.stream()
                .flatMap(node -> node.getToolsUsed().stream())
                .collect(Collectors.toSet());

        long totalDurationMs = endTime != null ?
                endTime.toEpochMilli() - startTime.toEpochMilli() : 0;

        return new DecisionTreeSummary(
                correlationId,
                swarmId,
                nodes.size(),
                nodesByAgentId.size(),
                nodesByTaskId.size(),
                totalLatency,
                avgLatency,
                totalDurationMs,
                allToolsUsed
        );
    }

    /**
     * Converts the tree to a map for serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("correlationId", correlationId);
        map.put("swarmId", swarmId);
        map.put("startTime", startTime.toString());
        map.put("endTime", endTime != null ? endTime.toString() : null);
        map.put("nodeCount", nodes.size());
        map.put("nodes", nodes.stream().map(DecisionNode::toMap).collect(Collectors.toList()));
        map.put("summary", getSummary().toMap());
        return map;
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

    public int getNodeCount() {
        return nodes.size();
    }

    /**
     * Summary statistics for a decision tree.
     */
    public static class DecisionTreeSummary {
        private final String correlationId;
        private final String swarmId;
        private final int totalDecisions;
        private final int uniqueAgents;
        private final int uniqueTasks;
        private final long totalLatencyMs;
        private final long avgLatencyMs;
        private final long totalDurationMs;
        private final Set<String> toolsUsed;

        public DecisionTreeSummary(
                String correlationId,
                String swarmId,
                int totalDecisions,
                int uniqueAgents,
                int uniqueTasks,
                long totalLatencyMs,
                long avgLatencyMs,
                long totalDurationMs,
                Set<String> toolsUsed) {
            this.correlationId = correlationId;
            this.swarmId = swarmId;
            this.totalDecisions = totalDecisions;
            this.uniqueAgents = uniqueAgents;
            this.uniqueTasks = uniqueTasks;
            this.totalLatencyMs = totalLatencyMs;
            this.avgLatencyMs = avgLatencyMs;
            this.totalDurationMs = totalDurationMs;
            this.toolsUsed = toolsUsed;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("correlationId", correlationId);
            map.put("swarmId", swarmId);
            map.put("totalDecisions", totalDecisions);
            map.put("uniqueAgents", uniqueAgents);
            map.put("uniqueTasks", uniqueTasks);
            map.put("totalLatencyMs", totalLatencyMs);
            map.put("avgLatencyMs", avgLatencyMs);
            map.put("totalDurationMs", totalDurationMs);
            map.put("toolsUsed", new ArrayList<>(toolsUsed));
            return map;
        }

        // Getters

        public String getCorrelationId() {
            return correlationId;
        }

        public String getSwarmId() {
            return swarmId;
        }

        public int getTotalDecisions() {
            return totalDecisions;
        }

        public int getUniqueAgents() {
            return uniqueAgents;
        }

        public int getUniqueTasks() {
            return uniqueTasks;
        }

        public long getTotalLatencyMs() {
            return totalLatencyMs;
        }

        public long getAvgLatencyMs() {
            return avgLatencyMs;
        }

        public long getTotalDurationMs() {
            return totalDurationMs;
        }

        public Set<String> getToolsUsed() {
            return new HashSet<>(toolsUsed);
        }
    }
}
