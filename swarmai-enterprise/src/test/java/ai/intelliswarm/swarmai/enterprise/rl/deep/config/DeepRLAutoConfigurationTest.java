package ai.intelliswarm.swarmai.enterprise.rl.deep.config;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import ai.intelliswarm.swarmai.rl.PolicyEngine;
import ai.intelliswarm.swarmai.rl.RewardTracker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.FilteredClassLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepRLAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepRLAutoConfiguration.class))
            .withUserConfiguration(TestLicenseConfig.class);

    @Test
    void doesNotRegisterPolicyEngineWhenLicenseMissingFeature() {
        contextRunner.withPropertyValues("test.deep-rl-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("deepRLPolicyEngine");
                    assertThat(context).doesNotHaveBean("deepRLRewardTracker");
                    assertThat(context.getBeansOfType(PolicyEngine.class)).isEmpty();
                });
    }

    @Test
    void registersPolicyEngineAndRewardTrackerWhenFeaturePresent() {
        contextRunner.withPropertyValues("test.deep-rl-enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("deepRLPolicyEngine");
                    assertThat(context).hasBean("deepRLRewardTracker");
                    assertThat(context.getBean(RewardTracker.class)).isNotNull();
                });
    }

    @Test
    void doesNotActivateWithoutDeepRlPolicyOnClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader("ai.intelliswarm.swarmai.enterprise.rl.deep.DeepRLPolicy"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean("deepRLPolicyEngine");
                    assertThat(context).doesNotHaveBean("deepRLRewardTracker");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestLicenseConfig {
        @Bean
        LicenseManager licenseManager(org.springframework.core.env.Environment environment) {
            boolean enabled = environment.getProperty("test.deep-rl-enabled", Boolean.class, false);
            LicenseManager manager = mock(LicenseManager.class);
            when(manager.hasFeature("deep-rl")).thenReturn(enabled);
            when(manager.getEdition()).thenReturn(ai.intelliswarm.swarmai.spi.LicenseProvider.Edition.BUSINESS);
            return manager;
        }
    }
}
