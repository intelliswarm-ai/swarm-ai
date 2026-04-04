package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.agent.resilience.LlmCircuitBreaker;
import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;
import ai.intelliswarm.swarmai.health.MemoryHealthIndicator;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;

/**
 * Resilience & production readiness scenarios.
 */
public class ResilienceScenarios {

    /** Verifies circuit breaker initializes and is in CLOSED state. */
    public static EvalScenario circuitBreakerInitialization() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "resilience-circuit-breaker"; }
            @Override public String name() { return "Circuit Breaker Initialization"; }
            @Override public String category() { return "RESILIENCE"; }
            @Override public String description() { return "Verify LlmCircuitBreaker starts in CLOSED state"; }

            @Override
            protected ScenarioResult doExecute() {
                LlmCircuitBreaker cb = new LlmCircuitBreaker();
                boolean closed = cb.getState() == CircuitBreaker.State.CLOSED;

                // Execute a successful call
                String result = cb.execute(() -> "success");
                boolean success = "success".equals(result);

                boolean valid = closed && success;
                return valid
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Circuit breaker starts CLOSED and executes successfully", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "CB state=" + cb.getState() + ", result=" + result, Duration.ZERO);
            }
        };
    }

    /** Verifies health indicators report UP. */
    public static EvalScenario healthIndicators() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "resilience-health"; }
            @Override public String name() { return "Health Indicators"; }
            @Override public String category() { return "RESILIENCE"; }
            @Override public String description() { return "Verify memory health indicator reports UP"; }

            @Override
            protected ScenarioResult doExecute() {
                MemoryHealthIndicator indicator = new MemoryHealthIndicator(new InMemoryMemory());
                Health health = indicator.health();
                boolean up = Status.UP.equals(health.getStatus());

                return up
                        ? ScenarioResult.pass(id(), name(), category(), 100.0,
                        "Memory health indicator reports UP", Duration.ZERO)
                        : ScenarioResult.fail(id(), name(), category(),
                        "Health status: " + health.getStatus(), Duration.ZERO);
            }
        };
    }

    /** Verifies config validation exists. */
    public static EvalScenario configValidation() {
        return new AbstractEvalScenario() {
            @Override public String id() { return "resilience-config-validation"; }
            @Override public String name() { return "Configuration Validator"; }
            @Override public String category() { return "RESILIENCE"; }
            @Override public String description() { return "Verify ConfigurationValidator class exists"; }

            @Override
            protected ScenarioResult doExecute() {
                try {
                    Class.forName("ai.intelliswarm.swarmai.config.ConfigurationValidator");
                    return ScenarioResult.pass(id(), name(), category(), 100.0,
                            "ConfigurationValidator is available", Duration.ZERO);
                } catch (ClassNotFoundException e) {
                    return ScenarioResult.fail(id(), name(), category(),
                            "ConfigurationValidator not found", Duration.ZERO);
                }
            }
        };
    }

    public static List<EvalScenario> all() {
        return List.of(
                circuitBreakerInitialization(),
                healthIndicators(),
                configValidation()
        );
    }
}
