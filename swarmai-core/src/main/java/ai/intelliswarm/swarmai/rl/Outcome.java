package ai.intelliswarm.swarmai.rl;

import java.time.Instant;

/**
 * Records the outcome of a decision for reward computation.
 *
 * @param decisionId the decision this outcome corresponds to
 * @param reward     the reward signal (-1.0 to 1.0 typically)
 * @param timestamp  when the outcome was observed
 */
public record Outcome(
        String decisionId,
        double reward,
        Instant timestamp
) {
    public static Outcome of(String decisionId, double reward) {
        return new Outcome(decisionId, reward, Instant.now());
    }
}
