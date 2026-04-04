package ai.intelliswarm.swarmai.selfimproving.model;

import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.task.Task;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Domain-agnostic structural description of a workflow.
 * Captures WHAT the workflow looks like (task count, depth, tool categories)
 * without HOW it's used (no domain terms like "research" or "analysis").
 * This is the basis for extracting generic improvement rules.
 */
public record WorkflowShape(
        int taskCount,
        int maxDependencyDepth,
        boolean hasSkillGeneration,
        boolean hasParallelTasks,
        boolean hasCyclicDependencies,
        Set<String> toolCategories,
        String processType,
        int agentCount,
        double avgToolsPerAgent,
        boolean hasBudgetConstraint,
        boolean hasGovernanceGates
) {

    public static WorkflowShape from(List<Task> tasks, Process process, Map<String, Object> inputs) {
        int taskCount = tasks.size();
        int maxDepth = computeMaxDepth(tasks);
        boolean parallel = tasks.stream().anyMatch(t -> t.getDependencies() != null && t.getDependencies().isEmpty());
        boolean skillGen = "SELF_IMPROVING".equals(process.getType().name())
                || "SWARM".equals(process.getType().name());

        Set<String> toolCats = tasks.stream()
                .filter(t -> t.getAgent() != null && t.getAgent().getTools() != null)
                .flatMap(t -> t.getAgent().getTools().stream())
                .map(tool -> categorize(tool.getFunctionName()))
                .collect(Collectors.toSet());

        long distinctAgents = tasks.stream()
                .filter(t -> t.getAgent() != null)
                .map(t -> t.getAgent().getRole())
                .distinct()
                .count();

        double avgTools = tasks.stream()
                .filter(t -> t.getAgent() != null && t.getAgent().getTools() != null)
                .mapToInt(t -> t.getAgent().getTools().size())
                .average()
                .orElse(0.0);

        boolean hasBudget = inputs.containsKey("__budgetTracker");
        boolean hasGov = inputs.containsKey("__governanceEngine");

        return new WorkflowShape(
                taskCount, maxDepth, skillGen, parallel, false,
                toolCats, process.getType().name(), (int) distinctAgents,
                avgTools, hasBudget, hasGov
        );
    }

    /**
     * Returns a feature vector for pattern matching and similarity comparison.
     */
    public Map<String, Object> toFeatureMap() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("task_count", taskCount);
        features.put("max_depth", maxDependencyDepth);
        features.put("has_skill_gen", hasSkillGeneration);
        features.put("has_parallel", hasParallelTasks);
        features.put("tool_category_count", toolCategories.size());
        features.put("process_type", processType);
        features.put("agent_count", agentCount);
        features.put("avg_tools_per_agent", avgToolsPerAgent);
        features.put("has_budget", hasBudgetConstraint);
        features.put("has_governance", hasGovernanceGates);
        return features;
    }

    /**
     * Tests whether this shape matches a rule condition.
     */
    public boolean matches(Map<String, Object> condition) {
        Map<String, Object> features = toFeatureMap();
        for (var entry : condition.entrySet()) {
            Object actual = features.get(entry.getKey());
            if (actual == null) return false;
            if (!matchesValue(actual, entry.getValue())) return false;
        }
        return true;
    }

    private static boolean matchesValue(Object actual, Object expected) {
        if (expected instanceof String s && s.startsWith("<=")) {
            return ((Number) actual).doubleValue() <= Double.parseDouble(s.substring(2).trim());
        }
        if (expected instanceof String s && s.startsWith(">=")) {
            return ((Number) actual).doubleValue() >= Double.parseDouble(s.substring(2).trim());
        }
        return Objects.equals(actual, expected);
    }

    private static int computeMaxDepth(List<Task> tasks) {
        Map<String, Task> byId = tasks.stream()
                .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));
        int maxDepth = 0;
        for (Task task : tasks) {
            maxDepth = Math.max(maxDepth, depth(task, byId, new HashSet<>()));
        }
        return maxDepth;
    }

    private static int depth(Task task, Map<String, Task> byId, Set<String> visited) {
        if (task.getDependencies() == null || task.getDependencies().isEmpty()) return 0;
        if (visited.contains(task.getId())) return 0;
        visited.add(task.getId());
        int max = 0;
        for (String depId : task.getDependencies()) {
            Task dep = byId.get(depId);
            if (dep != null) {
                max = Math.max(max, 1 + depth(dep, byId, visited));
            }
        }
        return max;
    }

    private static String categorize(String toolName) {
        if (toolName == null) return "UNKNOWN";
        String lower = toolName.toLowerCase();
        if (lower.contains("web") || lower.contains("http") || lower.contains("scrape")) return "WEB";
        if (lower.contains("file") || lower.contains("pdf") || lower.contains("directory")) return "FILE_IO";
        if (lower.contains("csv") || lower.contains("json") || lower.contains("xml") || lower.contains("database") || lower.contains("data")) return "DATA";
        if (lower.contains("calc") || lower.contains("code") || lower.contains("shell")) return "COMPUTE";
        if (lower.contains("email") || lower.contains("slack")) return "COMMUNICATION";
        if (lower.contains("search")) return "SEARCH";
        return "OTHER";
    }
}
