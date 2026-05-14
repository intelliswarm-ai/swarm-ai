package ai.intelliswarm.swarmai.tool.finance;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.util.List;

/**
 * One-liner registration of every finance tool. Useful when:
 *
 * <ul>
 *   <li>You're <b>not</b> using Spring component-scan and want the full
 *       finance suite in your {@code Agent}: {@code agent.registerTools(FinanceToolBundle.instances())}</li>
 *   <li>You want to register tools into a custom {@code ToolCatalog} without
 *       Spring's {@code @Component} machinery</li>
 *   <li>Tests need a deterministic list of finance tools without booting
 *       a Spring context</li>
 * </ul>
 *
 * <p>This is the curated set — Spring-using callers can rely on
 * {@code @ComponentScan} picking up every {@code @Component}-annotated finance
 * tool automatically. The bundle is intentionally not the source of truth;
 * it's a convenience facade.
 */
@PublicApi(since = "1.0.24")
public final class FinanceToolBundle {

    private FinanceToolBundle() {}

    /** Every finance tool in the curated bundle, freshly instantiated. */
    public static List<BaseTool> instances() {
        return List.of(
                new DcfModelTool(),
                new LboModelTool(),
                new CompsAnalysisTool(),
                new TearSheetTool());
    }

    /** Names of the tools the bundle contains — for documentation / discovery. */
    public static List<String> toolNames() {
        return instances().stream().map(BaseTool::getFunctionName).toList();
    }
}
