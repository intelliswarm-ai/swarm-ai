package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import ai.intelliswarm.swarmai.tool.safety.AuditLogger;
import ai.intelliswarm.swarmai.tool.safety.AuditingMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.AutoApproveMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.ConsoleApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.safety.KillSwitchMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.safety.SupervisedMutationGuard;
import ai.intelliswarm.swarmai.tool.windows.WindowsFileSystemTool;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring wiring for the cross-platform OS tool category.
 *
 * <p>Activate with {@code swarmai.tools.os.enabled=true}. The whole category is
 * opt-in to keep deployments that don't touch the filesystem free of supervised
 * mutation infrastructure.
 *
 * <p>Bean naming is prefixed with {@code os} (rather than the Windows
 * configuration's {@code windows}) so both autoconfigs can coexist if a user
 * has enabled both — e.g. when they want PowerShell-driven Windows tools at
 * the same time as the cross-platform filesystem tool.
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.tools.os", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OsToolsProperties.class)
public class OsToolsConfiguration {

    // ---------- supporting beans ----------

    @Bean
    public PathGuard osPathGuard(OsToolsProperties props) {
        return new PathGuard(props.getFilesystem().getAllowedRoots());
    }

    /** Falls back to a console-prompt approval handler if no other handler is present. */
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
     * {@link KillSwitchMutationGuard} → {@link AuditingMutationGuard} → inner guard.
     * Inner guard is {@link SupervisedMutationGuard} by default, or
     * {@link AutoApproveMutationGuard} when {@code swarmai.tools.os.auto-approve=true}.
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

    // ---------- Spring AI tool function bindings ----------

    @Bean
    @Description("List files, create directories, move/copy/rename/delete a single file inside the OS allowlist. "
            + "Read ops run freely; mutations are routed through the supervised approval gate.")
    public Function<WindowsFileSystemTool.Request, String> os_filesystem(FileSystemTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
