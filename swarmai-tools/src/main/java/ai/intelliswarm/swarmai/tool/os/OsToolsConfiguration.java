package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import ai.intelliswarm.swarmai.tool.os.shared.BashRunner;
import ai.intelliswarm.swarmai.tool.os.shared.OsContext;
import ai.intelliswarm.swarmai.tool.os.shared.PathGuard;
import ai.intelliswarm.swarmai.tool.safety.AuditLogger;
import ai.intelliswarm.swarmai.tool.safety.AuditingMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.AutoApproveMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.ConsoleApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.safety.KillSwitchMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.SupervisedMutationGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring wiring for the cross-platform OS-control tool category — runs on
 * Windows, macOS, and Linux. Gated on {@code swarmai.tools.os.enabled=true}
 * so the dangerous half of the toolbox is opt-in for users who haven't asked.
 *
 * <p>The Windows-specific {@code WindowsToolsConfiguration} stays in place and
 * remains the path for the PowerShell-flavored window/screenshot/shortcut
 * tools that don't yet have cross-platform peers — the two configurations are
 * independently togglable.
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.tools.os", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OsToolsProperties.class)
public class OsToolsConfiguration {

    // ---------- supporting beans ----------

    @Bean
    @ConditionalOnMissingBean
    public OsContext osContext() {
        return new OsContext();
    }

    @Bean
    @ConditionalOnMissingBean
    public BashRunner osBashRunner() {
        return new BashRunner();
    }

    @Bean
    public PathGuard osPathGuard(OsToolsProperties props) {
        return new PathGuard(props.getFilesystem().getAllowedRoots());
    }

    /** Falls back to a console-prompt approval handler if no enterprise variant is present. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(ApprovalGateHandler.class)
    public ConsoleApprovalGateHandler osConsoleApprovalGateHandler() {
        return new ConsoleApprovalGateHandler();
    }

    @Bean
    public AuditLogger osAuditLogger(OsToolsProperties props) {
        return new AuditLogger(java.nio.file.Paths.get(PathGuard.expand(props.getAuditDir())));
    }

    /**
     * Guard chain (outermost → innermost):
     * {@link KillSwitchMutationGuard} → {@link AuditingMutationGuard} → inner guard
     * (Supervised by default, AutoApprove if {@code swarmai.tools.os.auto-approve=true}).
     */
    @Bean
    @ConditionalOnMissingBean(MutationGuard.class)
    public MutationGuard osMutationGuard(ApprovalGateHandler approvalHandler,
                                         AuditLogger auditLogger,
                                         OsToolsProperties props) {
        MutationGuard inner = props.isAutoApprove()
                ? new AutoApproveMutationGuard()
                : new SupervisedMutationGuard(approvalHandler, props.getApprovalTimeout(), "default");
        MutationGuard audited = new AuditingMutationGuard(inner, auditLogger);
        return new KillSwitchMutationGuard(audited);
    }

    // ---------- tools ----------

    @Bean
    public FileSystemTool osFileSystemTool(PathGuard pathGuard,
                                           MutationGuard mutationGuard,
                                           OsToolsProperties props) {
        return new FileSystemTool(pathGuard, mutationGuard,
                props.getFilesystem().isDryRunDefault());
    }

    @Bean
    public ProcessTool osProcessTool(OsContext osContext,
                                     BashRunner bash,
                                     MutationGuard mutationGuard,
                                     OsToolsProperties props) {
        return new ProcessTool(osContext, bash, mutationGuard,
                props.getProcess().getDenylist(),
                props.getProcess().getTimeout(),
                props.getFilesystem().isDryRunDefault());
    }

    // ---------- Spring AI function beans ----------

    @Bean
    @Description("Read or modify files in user-owned directories. Cross-platform — Windows, macOS, Linux. " +
            "Read ops run freely; mutations route through the approval gate.")
    public Function<FileSystemTool.Request, String> os_filesystem(FileSystemTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }

    @Bean
    @Description("List, start, or kill processes. Cross-platform via java.lang.ProcessHandle and ps/kill on POSIX. " +
            "'kill' refuses denylisted system processes (kernel_task, systemd, winlogon, …).")
    public Function<ProcessTool.Request, String> os_process(ProcessTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
