package ai.intelliswarm.swarmai.state;

import ai.intelliswarm.swarmai.task.Task;

import java.util.List;

/**
 * Generates Mermaid flowchart diagrams from a {@link CompiledSwarm}.
 * Output can be rendered by GitHub, GitLab, Notion, or any Mermaid-compatible viewer.
 *
 * <p>Example output:
 * <pre>{@code
 * graph TD
 *     START([Start]) --> task-1[Research AI trends]
 *     task-1 --> task-2[Write report]
 *     task-2 --> END([End])
 * }</pre>
 */
public class MermaidDiagramGenerator implements DiagramGenerator {

    @Override
    public String generate(CompiledSwarm swarm) {
        StringBuilder sb = new StringBuilder();
        sb.append("graph TD\n");

        List<Task> tasks = swarm.tasks();

        if (tasks.isEmpty()) {
            sb.append("    START([Start]) --> END([End])\n");
            return sb.toString();
        }

        // Find tasks with no dependencies (entry points)
        List<Task> entryTasks = tasks.stream()
                .filter(t -> t.getDependencyTaskIds().isEmpty())
                .toList();

        // Connect START to entry tasks
        for (Task entry : entryTasks) {
            sb.append("    START([Start]) --> ")
                    .append(sanitizeId(entry.getId()))
                    .append("[\"").append(truncate(entry.getDescription(), 40)).append("\"]\n");
        }

        // Connect tasks based on dependencies
        for (Task task : tasks) {
            List<String> deps = task.getDependencyTaskIds();
            if (!deps.isEmpty()) {
                for (String dep : deps) {
                    sb.append("    ")
                            .append(sanitizeId(dep))
                            .append(" --> ")
                            .append(sanitizeId(task.getId()))
                            .append("[\"").append(truncate(task.getDescription(), 40)).append("\"]\n");
                }
            }
        }

        // Find tasks with no dependents (exit points)
        List<String> allDepTargets = tasks.stream()
                .flatMap(t -> t.getDependencyTaskIds().stream())
                .toList();
        List<Task> exitTasks = tasks.stream()
                .filter(t -> tasks.stream()
                        .noneMatch(other -> other.getDependencyTaskIds().contains(t.getId())))
                .toList();

        for (Task exit : exitTasks) {
            sb.append("    ")
                    .append(sanitizeId(exit.getId()))
                    .append(" --> END([End])\n");
        }

        // Style interrupt points
        for (String interruptId : swarm.getInterruptBeforeTaskIds()) {
            sb.append("    style ").append(sanitizeId(interruptId)).append(" stroke:#f66,stroke-width:3px\n");
        }

        // Add process type annotation
        sb.append("    subgraph ").append(swarm.processType().name()).append("\n");
        for (Task task : tasks) {
            sb.append("        ").append(sanitizeId(task.getId())).append("\n");
        }
        sb.append("    end\n");

        return sb.toString();
    }

    private String sanitizeId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "Task";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
