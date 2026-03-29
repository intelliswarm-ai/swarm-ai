package ai.intelliswarm.swarmai.observability.decision;

import ai.intelliswarm.swarmai.observability.config.ObservabilityProperties;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traces and explains agent decisions.
 * Answers "WHY did an agent make this decision?" by capturing context,
 * prompts, responses, and providing human-readable explanations.
 * Bean created by ObservabilityAutoConfiguration when decision tracing is enabled.
 */
public class DecisionTracer {

    private final ObservabilityProperties properties;
    private final Map<String, DecisionTree> decisionTrees;

    public DecisionTracer(ObservabilityProperties properties) {
        this.properties = properties;
        this.decisionTrees = new ConcurrentHashMap<>();
    }

    /**
     * Checks if decision tracing is enabled.
     */
    public boolean isEnabled() {
        return properties.isDecisionTracingEnabled();
    }

    /**
     * Starts tracing for a new workflow.
     */
    public void startTrace(String correlationId, String swarmId) {
        if (!isEnabled()) return;

        DecisionTree tree = new DecisionTree(correlationId, swarmId);
        decisionTrees.put(correlationId, tree);
    }

    /**
     * Completes tracing for a workflow.
     */
    public void completeTrace(String correlationId) {
        if (!isEnabled()) return;

        DecisionTree tree = decisionTrees.get(correlationId);
        if (tree != null) {
            tree.complete();
        }
    }

    /**
     * Records a decision node.
     */
    public void recordDecision(DecisionNode node) {
        if (!isEnabled() || node == null) return;

        String correlationId = node.getCorrelationId();
        if (correlationId == null) {
            ObservabilityContext ctx = ObservabilityContext.currentOrNull();
            if (ctx != null) {
                correlationId = ctx.getCorrelationId();
            }
        }

        if (correlationId != null) {
            DecisionTree tree = decisionTrees.get(correlationId);
            if (tree != null) {
                tree.addNode(node);
            }
        }
    }

    /**
     * Creates and records a decision using current context.
     */
    public DecisionNode.Builder createDecision() {
        ObservabilityContext ctx = ObservabilityContext.currentOrNull();

        DecisionNode.Builder builder = DecisionNode.builder();

        if (ctx != null) {
            builder.correlationId(ctx.getCorrelationId())
                    .spanId(ctx.getSpanId())
                    .parentSpanId(ctx.getParentSpanId())
                    .agentId(ctx.getAgentId())
                    .taskId(ctx.getTaskId());
        }

        return builder;
    }

    /**
     * Gets the decision tree for a correlation ID.
     */
    public Optional<DecisionTree> getDecisionTree(String correlationId) {
        return Optional.ofNullable(decisionTrees.get(correlationId));
    }

    /**
     * Gets a specific decision node.
     */
    public Optional<DecisionNode> getDecisionNode(String correlationId, String nodeId) {
        DecisionTree tree = decisionTrees.get(correlationId);
        if (tree == null) {
            return Optional.empty();
        }
        return tree.getNodeById(nodeId);
    }

    /**
     * Generates a human-readable explanation for a decision.
     */
    public String explainDecision(String correlationId, String nodeId) {
        Optional<DecisionNode> nodeOpt = getDecisionNode(correlationId, nodeId);

        if (nodeOpt.isEmpty()) {
            return "Decision not found: " + nodeId;
        }

        DecisionNode node = nodeOpt.get();
        return generateExplanation(node);
    }

    /**
     * Generates a human-readable explanation for the entire workflow.
     */
    public String explainWorkflow(String correlationId) {
        Optional<DecisionTree> treeOpt = getDecisionTree(correlationId);

        if (treeOpt.isEmpty()) {
            return "Workflow not found: " + correlationId;
        }

        DecisionTree tree = treeOpt.get();
        return generateWorkflowExplanation(tree);
    }

    /**
     * Generates a detailed explanation for a single decision.
     */
    private String generateExplanation(DecisionNode node) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Decision Explanation ===\n\n");

        // Agent context
        sb.append("AGENT:\n");
        sb.append("  Role: ").append(node.getAgentRole()).append("\n");
        if (node.getAgentGoal() != null) {
            sb.append("  Goal: ").append(truncate(node.getAgentGoal(), 200)).append("\n");
        }
        if (node.getAgentBackstory() != null) {
            sb.append("  Backstory: ").append(truncate(node.getAgentBackstory(), 200)).append("\n");
        }
        sb.append("\n");

        // Task context
        sb.append("TASK:\n");
        sb.append("  ID: ").append(node.getTaskId()).append("\n");
        sb.append("  Description: ").append(truncate(node.getTaskDescription(), 300)).append("\n");
        if (node.getExpectedOutput() != null) {
            sb.append("  Expected Output: ").append(truncate(node.getExpectedOutput(), 200)).append("\n");
        }
        sb.append("\n");

        // Input context
        if (node.getInputContext() != null) {
            sb.append("INPUT CONTEXT:\n");
            sb.append("  ").append(truncate(node.getInputContext(), 500)).append("\n\n");
        }

        // Decision
        sb.append("DECISION:\n");
        if (node.getDecision() != null) {
            sb.append("  ").append(truncate(node.getDecision(), 300)).append("\n\n");
        }

        // Reasoning analysis
        sb.append("WHY THIS DECISION?\n");
        sb.append(analyzeDecision(node));
        sb.append("\n");

        // Tools used
        if (!node.getToolsUsed().isEmpty()) {
            sb.append("TOOLS USED:\n");
            node.getToolsUsed().forEach(tool -> sb.append("  - ").append(tool).append("\n"));
            sb.append("\n");
        }

        // Timing
        sb.append("TIMING:\n");
        sb.append("  Latency: ").append(node.getLatencyMs()).append("ms\n");
        sb.append("  Timestamp: ").append(node.getTimestamp()).append("\n");

        return sb.toString();
    }

    /**
     * Analyzes why a decision was made based on context.
     */
    private String analyzeDecision(DecisionNode node) {
        StringBuilder analysis = new StringBuilder();

        // Role alignment analysis
        if (node.getAgentRole() != null && node.getDecision() != null) {
            analysis.append("  - The agent's role as '")
                    .append(node.getAgentRole())
                    .append("' influenced the approach taken.\n");
        }

        // Goal pursuit analysis
        if (node.getAgentGoal() != null) {
            analysis.append("  - The decision aligns with the agent's goal: ")
                    .append(truncate(node.getAgentGoal(), 100))
                    .append("\n");
        }

        // Context influence
        if (node.getInputContext() != null && !node.getInputContext().isEmpty()) {
            analysis.append("  - Prior context from previous tasks informed this decision.\n");
        }

        // Tool usage
        if (!node.getToolsUsed().isEmpty()) {
            analysis.append("  - The agent used ")
                    .append(node.getToolsUsed().size())
                    .append(" tool(s) to gather information: ")
                    .append(String.join(", ", node.getToolsUsed()))
                    .append("\n");
        }

        // Reasoning if available
        if (node.getReasoning() != null) {
            analysis.append("  - Agent's stated reasoning: ")
                    .append(truncate(node.getReasoning(), 200))
                    .append("\n");
        }

        if (analysis.length() == 0) {
            analysis.append("  - Insufficient context to analyze decision.\n");
        }

        return analysis.toString();
    }

    /**
     * Generates an explanation for the entire workflow.
     */
    private String generateWorkflowExplanation(DecisionTree tree) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== Workflow Decision Trace ===\n\n");

        DecisionTree.DecisionTreeSummary summary = tree.getSummary();

        sb.append("SUMMARY:\n");
        sb.append("  Correlation ID: ").append(summary.getCorrelationId()).append("\n");
        sb.append("  Swarm ID: ").append(summary.getSwarmId()).append("\n");
        sb.append("  Total Decisions: ").append(summary.getTotalDecisions()).append("\n");
        sb.append("  Unique Agents: ").append(summary.getUniqueAgents()).append("\n");
        sb.append("  Unique Tasks: ").append(summary.getUniqueTasks()).append("\n");
        sb.append("  Total Duration: ").append(summary.getTotalDurationMs()).append("ms\n");
        sb.append("  Avg Decision Latency: ").append(summary.getAvgLatencyMs()).append("ms\n");

        if (!summary.getToolsUsed().isEmpty()) {
            sb.append("  Tools Used: ").append(String.join(", ", summary.getToolsUsed())).append("\n");
        }
        sb.append("\n");

        sb.append("DECISION TIMELINE:\n");
        int index = 1;
        for (DecisionNode node : tree.getAllNodes()) {
            sb.append("  ").append(index++).append(". ");
            sb.append("[").append(node.getAgentRole()).append("] ");
            if (node.getDecision() != null) {
                sb.append(truncate(node.getDecision(), 80));
            } else {
                sb.append(truncate(node.getTaskDescription(), 80));
            }
            sb.append(" (").append(node.getLatencyMs()).append("ms)\n");
        }

        return sb.toString();
    }

    /**
     * Removes old decision trees to free memory.
     */
    public void cleanup(String correlationId) {
        decisionTrees.remove(correlationId);
    }

    /**
     * Clears all decision trees.
     */
    public void clearAll() {
        decisionTrees.clear();
    }

    /**
     * Gets the count of active decision trees.
     */
    public int getActiveTraceCount() {
        return decisionTrees.size();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
