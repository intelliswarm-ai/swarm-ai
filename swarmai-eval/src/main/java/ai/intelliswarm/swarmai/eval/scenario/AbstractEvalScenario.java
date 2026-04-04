package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Base class for eval scenarios with timing, error handling, and logging.
 */
public abstract class AbstractEvalScenario implements EvalScenario {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public ScenarioResult execute() {
        logger.info("[EVAL] Running: {} ({})", name(), category());
        Instant start = Instant.now();
        try {
            ScenarioResult result = doExecute();
            Duration duration = Duration.between(start, Instant.now());
            ScenarioResult timed = new ScenarioResult(
                    result.scenarioId(), result.scenarioName(), result.category(),
                    result.passed(), result.score(), result.message(),
                    duration, Instant.now(), result.details()
            );
            if (timed.passed()) {
                logger.info("[EVAL] PASS: {} (score: {}, {}ms)", name(), timed.score(), duration.toMillis());
            } else {
                logger.warn("[EVAL] FAIL: {} -- {}", name(), timed.message());
            }
            return timed;
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            logger.error("[EVAL] ERROR: {} -- {}", name(), e.getMessage(), e);
            return ScenarioResult.fail(id(), name(), category(),
                    "Exception: " + e.getMessage(), duration,
                    Map.of("exception", e.getClass().getSimpleName()));
        }
    }

    /**
     * Subclasses implement the actual scenario logic here.
     */
    protected abstract ScenarioResult doExecute();
}
