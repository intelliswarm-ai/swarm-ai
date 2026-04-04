package ai.intelliswarm.swarmai.eval.scenario;

import ai.intelliswarm.swarmai.eval.scoring.ScenarioResult;

/**
 * A single evaluation scenario that tests a specific framework capability.
 * Each scenario sets up preconditions, executes the capability, and scores the result.
 */
public interface EvalScenario {

    /** Unique identifier for this scenario. */
    String id();

    /** Human-readable name. */
    String name();

    /** Category: CORE, ORCHESTRATION, ENTERPRISE, RESILIENCE, DSL */
    String category();

    /** Description of what this scenario tests. */
    String description();

    /**
     * Execute the scenario and return a scored result.
     * Implementations should catch exceptions and return a failed result
     * rather than throwing.
     */
    ScenarioResult execute();
}
