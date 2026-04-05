package ai.intelliswarm.swarmai.selfimproving.model;

/**
 * Classification tier for framework improvements.
 * Determines how the improvement flows into the next release.
 */
public enum ImprovementTier {

    /**
     * Data-only changes (policy weights, routing hints, convergence defaults, skill promotions).
     * Validated automatically by the test suite. Ships without human review.
     */
    TIER_1_AUTOMATIC("Intelligence update — auto-validated by test suite"),

    /**
     * Safe code-adjacent changes (anti-pattern rules, prompt templates, new built-in skills).
     * Auto-generated as a PR. Human approves/rejects but doesn't design.
     */
    TIER_2_REVIEW("Safe change — auto-PR, human approves"),

    /**
     * Structural proposals that require code changes and architectural decisions.
     * Stored as a structured proposal file. Human designs and implements.
     */
    TIER_3_PROPOSAL("Architecture proposal — human designs solution");

    private final String description;

    ImprovementTier(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
