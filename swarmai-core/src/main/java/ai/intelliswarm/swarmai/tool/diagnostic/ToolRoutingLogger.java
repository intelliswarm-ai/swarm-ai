package ai.intelliswarm.swarmai.tool.diagnostic;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Logs tool routing metadata (category, trigger-when / avoid-when, tags) at
 * workflow startup so adopters can see exactly which tools the swarm can reach
 * and how they self-describe their routing constraints.
 *
 * <p>Prints a grouped-by-category block that answers "what tools do I have,
 * and when should the agent reach for each one?" — useful in any example that
 * wires several tools.
 */
public final class ToolRoutingLogger {

    private static final Logger logger = LoggerFactory.getLogger(ToolRoutingLogger.class);

    private ToolRoutingLogger() {
        // Static helper.
    }

    /**
     * Logs the routing metadata of each tool, grouped by category. For each
     * tool prints: function name, description, USE WHEN (trigger), AVOID WHEN,
     * and tags — whichever are set.
     */
    public static void log(List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) {
            logger.info("Tool Routing Metadata: no tools registered");
            return;
        }

        logger.info("Tool Routing Metadata ({} tools):", tools.size());
        Map<String, List<BaseTool>> byCategory = tools.stream()
                .collect(Collectors.groupingBy(BaseTool::getCategory));

        for (Map.Entry<String, List<BaseTool>> entry : byCategory.entrySet()) {
            logger.info("  [{}]", entry.getKey().toUpperCase());
            for (BaseTool tool : entry.getValue()) {
                StringBuilder meta = new StringBuilder();
                meta.append("    ").append(tool.getFunctionName())
                        .append(": ").append(tool.getDescription());
                if (tool.getTriggerWhen() != null) {
                    meta.append(" | USE WHEN: ").append(tool.getTriggerWhen());
                }
                if (tool.getAvoidWhen() != null) {
                    meta.append(" | AVOID WHEN: ").append(tool.getAvoidWhen());
                }
                if (!tool.getTags().isEmpty()) {
                    meta.append(" | Tags: ").append(String.join(", ", tool.getTags()));
                }
                logger.info("{}", meta);
            }
        }
    }
}
