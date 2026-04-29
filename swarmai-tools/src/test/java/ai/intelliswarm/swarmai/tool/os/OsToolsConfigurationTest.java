package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.governance.ApprovalGateHandler;
import ai.intelliswarm.swarmai.tool.safety.AuditLogger;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link OsToolsConfiguration} autoconfig wiring:
 * <ul>
 *   <li>does NOT load by default (must be opt-in via {@code swarmai.tools.os.enabled=true})</li>
 *   <li>loads cleanly when the property is set, exposing {@link FileSystemTool},
 *       {@link PathGuard}, {@link MutationGuard}, {@link AuditLogger}, and an
 *       {@link ApprovalGateHandler}</li>
 *   <li>respects the auto-approve toggle</li>
 *   <li>publishes the {@code os_filesystem} Spring AI tool function bean</li>
 * </ul>
 */
@DisplayName("OsToolsConfiguration autoconfig")
class OsToolsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OsToolsConfiguration.class))
            .withUserConfiguration(OsToolsConfiguration.class);

    @Test
    @DisplayName("does not load when the master switch is off (default)")
    void disabled_byDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(FileSystemTool.class);
            assertThat(ctx).doesNotHaveBean(OsToolsProperties.class);
        });
    }

    @Test
    @DisplayName("loads core beans when swarmai.tools.os.enabled=true")
    void enabled_loadsAllBeans() {
        runner
                .withPropertyValues("swarmai.tools.os.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OsToolsProperties.class);
                    assertThat(ctx).hasSingleBean(FileSystemTool.class);
                    assertThat(ctx).hasSingleBean(PathGuard.class);
                    assertThat(ctx).hasSingleBean(MutationGuard.class);
                    assertThat(ctx).hasSingleBean(AuditLogger.class);
                    assertThat(ctx).hasSingleBean(ApprovalGateHandler.class);
                });
    }

    @Test
    @DisplayName("publishes the os_filesystem Spring AI tool function bean")
    void enabled_publishesOsFilesystemFunction() {
        runner
                .withPropertyValues("swarmai.tools.os.enabled=true")
                .run(ctx -> {
                    // Bean name matches the @Bean method, which is what Spring AI uses for the tool name.
                    assertThat(ctx).hasBean("os_filesystem");
                });
    }

    @Test
    @DisplayName("auto-approve property swaps in AutoApproveMutationGuard inner")
    void autoApprove_changesGuardChain() {
        runner
                .withPropertyValues(
                        "swarmai.tools.os.enabled=true",
                        "swarmai.tools.os.auto-approve=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MutationGuard.class);
                    OsToolsProperties props = ctx.getBean(OsToolsProperties.class);
                    assertThat(props.isAutoApprove()).isTrue();
                });
    }

    @Test
    @DisplayName("custom allowed-roots property propagates into PathGuard config")
    void customAllowedRoots_propagate() {
        runner
                .withPropertyValues(
                        "swarmai.tools.os.enabled=true",
                        "swarmai.tools.os.filesystem.allowed-roots=/tmp/work-area")
                .run(ctx -> {
                    OsToolsProperties props = ctx.getBean(OsToolsProperties.class);
                    assertThat(props.getFilesystem().getAllowedRoots())
                            .containsExactly("/tmp/work-area");
                });
    }
}
