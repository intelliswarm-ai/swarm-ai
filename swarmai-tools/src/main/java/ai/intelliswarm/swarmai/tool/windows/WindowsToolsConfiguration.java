package ai.intelliswarm.swarmai.tool.windows;

import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import ai.intelliswarm.swarmai.tool.safety.ConsoleApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.SupervisedMutationGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.AwtScreenCapturer;
import ai.intelliswarm.swarmai.tool.windows.internal.DefaultPowerShellRunner;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PowerShellRunner;
import ai.intelliswarm.swarmai.tool.windows.internal.ScreenCapturer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * Spring wiring for the Windows PC-control tool category.
 *
 * <p>The whole category is opt-in: nothing is registered unless
 * {@code swarmai.tools.windows.enabled=true}. This keeps non-Windows
 * deployments (CI on Linux, dev on macOS) free of tools that would
 * fail at runtime, and keeps the dangerous side of the toolbox off
 * by default for users who haven't asked for it.
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.tools.windows", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WindowsToolsProperties.class)
public class WindowsToolsConfiguration {

    // ---------- supporting beans ----------

    @Bean
    @ConditionalOnMissingBean
    public PowerShellRunner windowsPowerShellRunner() {
        return new DefaultPowerShellRunner();
    }

    @Bean
    @ConditionalOnMissingBean
    public ScreenCapturer windowsScreenCapturer() {
        return new AwtScreenCapturer();
    }

    @Bean
    public PathGuard windowsPathGuard(WindowsToolsProperties props) {
        return new PathGuard(props.getFilesystem().getAllowedRoots());
    }

    /**
     * Falls back to a console-prompt approval handler if no other
     * {@link ApprovalGateHandler} is present (typically from {@code swarmai-enterprise}).
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ApprovalGateHandler.class)
    public ConsoleApprovalGateHandler windowsConsoleApprovalGateHandler() {
        return new ConsoleApprovalGateHandler();
    }

    @Bean
    @ConditionalOnMissingBean(MutationGuard.class)
    public MutationGuard windowsMutationGuard(ApprovalGateHandler approvalHandler,
                                              WindowsToolsProperties props) {
        return new SupervisedMutationGuard(approvalHandler, props.getApprovalTimeout(), "default");
    }

    // ---------- tools ----------

    @Bean
    public WindowsFileSystemTool windowsFileSystemTool(PathGuard pathGuard,
                                                       MutationGuard mutationGuard,
                                                       WindowsToolsProperties props) {
        return new WindowsFileSystemTool(pathGuard, mutationGuard,
                props.getFilesystem().isDryRunDefault());
    }

    @Bean
    public WindowsProcessTool windowsProcessTool(PowerShellRunner ps,
                                                 MutationGuard mutationGuard,
                                                 WindowsToolsProperties props) {
        return new WindowsProcessTool(ps, mutationGuard,
                props.getProcess().getDenylist(),
                props.getProcess().getTimeout(),
                props.getFilesystem().isDryRunDefault());
    }

    @Bean
    public WindowsWindowTool windowsWindowTool(PowerShellRunner ps,
                                               MutationGuard mutationGuard,
                                               WindowsToolsProperties props) {
        return new WindowsWindowTool(ps, mutationGuard,
                props.getWindow().getTimeout(),
                props.getFilesystem().isDryRunDefault());
    }

    @Bean
    public WindowsShortcutTool windowsShortcutTool(PathGuard pathGuard,
                                                   PowerShellRunner ps,
                                                   MutationGuard mutationGuard,
                                                   WindowsToolsProperties props) {
        return new WindowsShortcutTool(pathGuard, ps, mutationGuard,
                props.getProcess().getTimeout(),
                props.getFilesystem().isDryRunDefault());
    }

    @Bean
    public WindowsScreenshotTool windowsScreenshotTool(ScreenCapturer capturer,
                                                       PowerShellRunner ps,
                                                       MutationGuard mutationGuard,
                                                       WindowsToolsProperties props) {
        Path outputDir = WindowsScreenshotTool.resolveOutputDir(props.getScreenshot().getOutputDir());
        return new WindowsScreenshotTool(capturer, ps, mutationGuard, outputDir,
                props.getWindow().getTimeout(),
                props.getFilesystem().isDryRunDefault());
    }

    // ---------- Spring AI function beans (one per tool) ----------

    @Bean
    @Description("Read or modify files in user-owned directories on Windows. "
            + "Read ops run freely; mutations are routed through an approval gate.")
    public Function<WindowsFileSystemTool.Request, String> windows_filesystem(WindowsFileSystemTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("List, start, or kill processes on Windows. 'kill' refuses denylisted system processes.")
    public Function<WindowsProcessTool.Request, String> windows_process(WindowsProcessTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("List and manipulate top-level desktop windows on Windows: focus, minimize, maximize, restore, close.")
    public Function<WindowsWindowTool.Request, String> windows_window(WindowsWindowTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("List, inspect, or create Windows .lnk shortcuts via WScript.Shell.")
    public Function<WindowsShortcutTool.Request, String> windows_shortcut(WindowsShortcutTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("Capture the primary monitor, a specific monitor, or a named window's bounds to a PNG file.")
    public Function<WindowsScreenshotTool.Request, String> windows_screenshot(WindowsScreenshotTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
