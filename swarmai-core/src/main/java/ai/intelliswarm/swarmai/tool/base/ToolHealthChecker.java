package ai.intelliswarm.swarmai.tool.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Verifies tool health and requirements before agent assignment.
 * Runs smoke tests and requirement checks to filter out non-operational tools.
 */
public class ToolHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(ToolHealthChecker.class);
    private static final int SMOKE_TEST_TIMEOUT_SECONDS = 5;

    /**
     * Check all tools and return only the operational ones.
     * Logs warnings for tools that fail requirements or smoke tests.
     */
    public static List<BaseTool> filterOperational(List<BaseTool> tools) {
        List<BaseTool> operational = new ArrayList<>();

        for (BaseTool tool : tools) {
            HealthCheckResult result = check(tool);
            if (result.healthy()) {
                operational.add(tool);
            } else {
                logger.warn("Tool '{}' excluded: {}", tool.getFunctionName(), result.issues());
            }
        }

        if (operational.size() < tools.size()) {
            logger.info("Tool health check: {}/{} tools operational",
                operational.size(), tools.size());
        }

        return operational;
    }

    /**
     * Run health check on a single tool: requirements + smoke test.
     */
    public static HealthCheckResult check(BaseTool tool) {
        List<String> issues = new ArrayList<>();

        // 1. Check requirements
        ToolRequirements reqs = tool.getRequirements();
        if (reqs != null && !reqs.isEmpty()) {
            issues.addAll(reqs.checkSatisfied());
        }

        // 2. Run smoke test (with timeout)
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> future = executor.submit(tool::smokeTest);
            String smokeResult = future.get(SMOKE_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            executor.shutdownNow();

            if (smokeResult != null) {
                issues.add("Smoke test failed: " + smokeResult);
            }
        } catch (TimeoutException e) {
            issues.add("Smoke test timed out after " + SMOKE_TEST_TIMEOUT_SECONDS + "s");
        } catch (Exception e) {
            issues.add("Smoke test error: " + e.getMessage());
        }

        return new HealthCheckResult(issues.isEmpty(), issues);
    }

    /**
     * Run health checks on all tools and return a summary report.
     */
    public static Map<String, HealthCheckResult> checkAll(List<BaseTool> tools) {
        Map<String, HealthCheckResult> results = new LinkedHashMap<>();
        for (BaseTool tool : tools) {
            results.put(tool.getFunctionName(), check(tool));
        }
        return results;
    }

    public record HealthCheckResult(boolean healthy, List<String> issues) {}
}
