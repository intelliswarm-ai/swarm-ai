package ai.intelliswarm.swarmai.selfimproving.config;

import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.health.SelfImprovementHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfImprovementAutoConfigurationTest {

    @Test
    void evolutionMatchRequiresSameWorkflowShapeAndConfiguredProcess() {
        LedgerStore.StoredEvolution evolution = new LedgerStore.StoredEvolution(
                "swarm-1",
                "PROCESS_TYPE_CHANGE",
                "independent tasks are parallelizable",
                "{\"processType\":\"SEQUENTIAL\",\"taskCount\":3,\"maxDependencyDepth\":0,\"agentCount\":2,\"configuration\":{}}",
                "{\"processType\":\"SEQUENTIAL\",\"taskCount\":3,\"maxDependencyDepth\":0,\"agentCount\":2,\"configuration\":{\"processType\":\"PARALLEL\"}}",
                Instant.now()
        );

        assertTrue(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 3, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.HIERARCHICAL, 3, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 4, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 3, 1));
    }

    @Test
    void evolutionMatchRejectsMalformedJson() {
        LedgerStore.StoredEvolution malformed = new LedgerStore.StoredEvolution(
                "swarm-2",
                "PROCESS_TYPE_CHANGE",
                "bad payload",
                "{bad-json",
                "{\"configuration\":{\"processType\":\"PARALLEL\"}}",
                Instant.now()
        );

        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                malformed, ProcessType.SEQUENTIAL, 2, 0));
    }

    /**
     * Regression: when {@code spring-boot-actuator} is not on the classpath, the
     * self-improving autoconfig must still load. The health indicator bean lives
     * in a nested configuration class so its return-type ({@code HealthIndicator})
     * is never reached by Spring's reflection until the conditional approves.
     *
     * <p>This test simulates "no actuator on classpath" by hiding
     * {@code org.springframework.boot.actuate.health} from the application context
     * classloader and asserting that:
     * <ul>
     *   <li>Context loads without {@link NoClassDefFoundError}</li>
     *   <li>{@link SelfImprovementHealthIndicator} bean is NOT registered</li>
     * </ul>
     */
    @Test
    void healthIndicator_isOptional_whenActuatorNotOnClasspath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SelfImprovementAutoConfiguration.class))
                .withPropertyValues("swarmai.self-improving.enabled=true")
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.boot.actuate.health"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(SelfImprovementHealthIndicator.class);
                });
    }

    /**
     * Counter-test: when actuator IS on the classpath, the health indicator
     * bean is loaded as expected. Locks in that we didn't break the happy path.
     */
    @Test
    void healthIndicator_isLoaded_whenActuatorOnClasspath() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SelfImprovementAutoConfiguration.class))
                .withPropertyValues("swarmai.self-improving.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // Bean is conditional on actuator being on classpath. The test classpath
                    // includes spring-boot-actuator transitively, so it should be present.
                    assertThat(ctx).hasSingleBean(SelfImprovementHealthIndicator.class);
                });
    }
}
