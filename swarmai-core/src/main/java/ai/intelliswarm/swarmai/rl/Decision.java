package ai.intelliswarm.swarmai.rl;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a decision made by the policy engine for reward tracking.
 *
 * @param id          unique decision identifier
 * @param type        decision type: "skill_generation", "convergence", "selection"
 * @param stateVector the feature vector at the time of the decision
 * @param actionIndex the action chosen (index into the action space)
 * @param timestamp   when the decision was made
 */
public record Decision(
        String id,
        String type,
        double[] stateVector,
        int actionIndex,
        Instant timestamp
) {
    public static Decision create(String type, double[] stateVector, int actionIndex) {
        return new Decision(UUID.randomUUID().toString(), type, stateVector, actionIndex, Instant.now());
    }
}
