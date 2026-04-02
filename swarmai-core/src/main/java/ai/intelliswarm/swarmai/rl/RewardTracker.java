package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks decisions and matches them with delayed outcomes to compute rewards.
 *
 * <p>The RL reward signal for skill generation is delayed — a skill's effectiveness
 * is only known after several uses. This tracker:
 * <ol>
 *   <li>Records pending decisions when skills are generated</li>
 *   <li>Matches skill usage outcomes back to the original decision</li>
 *   <li>Feeds computed rewards to the {@link PolicyEngine}</li>
 * </ol>
 *
 * <p>Can be used standalone or as a Spring {@code @EventListener} on {@link SwarmEvent}.
 *
 * <pre>{@code
 * RewardTracker tracker = new RewardTracker(learningPolicy);
 *
 * // When a skill generation decision is made
 * tracker.trackDecision("skill-123", decision);
 *
 * // Later, when the skill has been used enough
 * tracker.recordSkillOutcome("skill-123", 0.85);  // 85% effectiveness
 * }</pre>
 */
public class RewardTracker {

    private static final Logger logger = LoggerFactory.getLogger(RewardTracker.class);

    private final PolicyEngine policyEngine;
    private final ConcurrentHashMap<String, Decision> pendingDecisions = new ConcurrentHashMap<>();
    private int totalRewardsProcessed = 0;

    public RewardTracker(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    /**
     * Tracks a decision for later reward matching.
     *
     * @param skillId  the generated skill's ID (used to match with outcome)
     * @param decision the decision that was made
     */
    public void trackDecision(String skillId, Decision decision) {
        pendingDecisions.put(skillId, decision);
        logger.debug("[RewardTracker] Tracking decision for skill '{}': type={}, action={}",
                skillId, decision.type(), decision.actionIndex());
    }

    /**
     * Records a convergence decision outcome.
     *
     * @param decision      the convergence decision
     * @param madeProgress  true if the next iteration made progress
     */
    public void recordConvergenceOutcome(Decision decision, boolean madeProgress) {
        double reward = madeProgress ? 1.0 : -1.0;
        Outcome outcome = Outcome.of(decision.id(), reward);
        policyEngine.recordOutcome(decision, outcome);
        totalRewardsProcessed++;
        logger.debug("[RewardTracker] Convergence reward: action={} progress={} reward={}",
                decision.actionIndex(), madeProgress, reward);
    }

    /**
     * Records a skill outcome when the skill has been used enough times.
     *
     * @param skillId       the skill's ID
     * @param effectiveness the skill's effectiveness (successCount / usageCount)
     */
    public void recordSkillOutcome(String skillId, double effectiveness) {
        Decision decision = pendingDecisions.remove(skillId);
        if (decision == null) {
            logger.debug("[RewardTracker] No pending decision for skill '{}'", skillId);
            return;
        }

        Outcome outcome = Outcome.of(decision.id(), effectiveness);
        policyEngine.recordOutcome(decision, outcome);
        totalRewardsProcessed++;
        logger.debug("[RewardTracker] Skill reward: skill='{}' effectiveness={:.3f}",
                skillId, effectiveness);
    }

    /**
     * Records a skill selection outcome.
     *
     * @param weights       the weight vector that was used
     * @param taskSucceeded whether the task completed successfully
     */
    public void recordSelectionOutcome(double[] weights, boolean taskSucceeded) {
        Decision decision = Decision.create("selection", weights, 0);
        Outcome outcome = Outcome.of(decision.id(), taskSucceeded ? 1.0 : 0.0);
        policyEngine.recordOutcome(decision, outcome);
        totalRewardsProcessed++;
    }

    /**
     * Handles a SwarmEvent for automatic reward tracking.
     * Call this from a Spring {@code @EventListener} or manually.
     */
    public void onSwarmEvent(SwarmEvent event) {
        switch (event.getType()) {
            case SKILL_GENERATED -> {
                // Track that a skill was generated — will match with later outcome
                String skillId = (String) event.getMetadata().get("skillId");
                String decisionId = (String) event.getMetadata().get("decisionId");
                if (skillId != null && decisionId != null) {
                    double[] state = (double[]) event.getMetadata().get("stateVector");
                    int action = event.getMetadata().containsKey("actionIndex")
                            ? ((Number) event.getMetadata().get("actionIndex")).intValue() : 0;
                    trackDecision(skillId, new Decision(decisionId, "skill_generation",
                            state != null ? state : new double[0], action,
                            java.time.Instant.now()));
                }
            }
            case SKILL_PROMOTED -> {
                // Promoted skills are valuable — high reward
                String skillId = (String) event.getMetadata().get("skillId");
                if (skillId != null) {
                    recordSkillOutcome(skillId, 1.0);
                }
            }
            case ITERATION_REVIEW_PASSED -> {
                // Iteration was approved — positive signal
                logger.debug("[RewardTracker] Iteration approved — positive signal");
            }
            default -> {
                // Other events — no action needed
            }
        }
    }

    /**
     * Returns the number of pending decisions awaiting outcomes.
     */
    public int getPendingCount() {
        return pendingDecisions.size();
    }

    /**
     * Returns the total number of rewards processed.
     */
    public int getTotalRewardsProcessed() {
        return totalRewardsProcessed;
    }
}
