package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import ai.intelliswarm.swarmai.tool.windows.WindowsToolsConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OsToolsConfiguration wiring")
class OsToolsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OsToolsConfiguration.class, WindowsToolsConfiguration.class)
            .withPropertyValues(
                    "swarmai.tools.os.enabled=true",
                    "swarmai.tools.windows.enabled=true",
                    "swarmai.tools.os.auto-approve=true",
                    "swarmai.tools.windows.auto-approve=true"
            );

    @Test
    @DisplayName("OS + Windows tool categories can be enabled together")
    void osAndWindowsCanBootTogether() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("osAuditLogger");
            assertThat(context).hasBean("windowsAuditLogger");
            assertThat(context).hasBean("osPathGuard");
            assertThat(context).hasBean("windowsPathGuard");
            assertThat(context).hasSingleBean(MutationGuard.class);
            assertThat(context).hasBean("os_filesystem");
        });
    }
}
