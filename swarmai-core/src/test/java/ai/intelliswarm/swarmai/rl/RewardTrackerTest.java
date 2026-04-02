package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RewardTrackerTest {

    private LearningPolicy policy;
    private RewardTracker tracker;

    @BeforeEach
    void setUp() {
        policy = new LearningPolicy(5, 1.0, 1000);
        tracker = new RewardTracker(policy);
    }

    @Test
    void trackAndResolveSkillDecision() {
        Decision decision = Decision.create("skill_generation",
                new double[]{0.8, 0.9, 0.7, 1.0, 0.6, 0.25, 0.3, 0.1}, 0);

        tracker.trackDecision("skill-123", decision);
        assertEquals(1, tracker.getPendingCount());

        tracker.recordSkillOutcome("skill-123", 0.85);
        assertEquals(0, tracker.getPendingCount());
        assertEquals(1, tracker.getTotalRewardsProcessed());
    }

    @Test
    void unknownSkillOutcomeIsIgnored() {
        tracker.recordSkillOutcome("nonexistent", 0.5);
        assertEquals(0, tracker.getPendingCount());
        assertEquals(0, tracker.getTotalRewardsProcessed());
    }

    @Test
    void recordConvergenceOutcome() {
        Decision decision = Decision.create("convergence",
                new double[]{1.2, 0.3, 1, 0.5, 3, 0.8}, 0);

        tracker.recordConvergenceOutcome(decision, true);
        assertEquals(1, tracker.getTotalRewardsProcessed());

        tracker.recordConvergenceOutcome(decision, false);
        assertEquals(2, tracker.getTotalRewardsProcessed());
    }

    @Test
    void recordSelectionOutcome() {
        tracker.recordSelectionOutcome(new double[]{0.5, 0.3, 0.2}, true);
        assertEquals(1, tracker.getTotalRewardsProcessed());

        tracker.recordSelectionOutcome(new double[]{0.6, 0.25, 0.15}, false);
        assertEquals(2, tracker.getTotalRewardsProcessed());
    }

    @Test
    void handlesSkillPromotedEvent() {
        // Set up a pending decision
        Decision decision = Decision.create("skill_generation",
                new double[]{0.5, 0.5, 0.5, 1.0, 0.5, 0.15, 0.25, 0.1}, 0);
        tracker.trackDecision("promoted-skill", decision);

        // Fire SKILL_PROMOTED event
        SwarmEvent event = new SwarmEvent(this, SwarmEvent.Type.SKILL_PROMOTED,
                "Skill promoted", "swarm-1",
                Map.of("skillId", "promoted-skill"));
        tracker.onSwarmEvent(event);

        assertEquals(0, tracker.getPendingCount()); // resolved
        assertEquals(1, tracker.getTotalRewardsProcessed());
    }

    @Test
    void handlesSkillGeneratedEvent() {
        SwarmEvent event = new SwarmEvent(this, SwarmEvent.Type.SKILL_GENERATED,
                "Skill generated", "swarm-1",
                Map.of("skillId", "gen-skill",
                       "decisionId", "dec-123",
                       "stateVector", new double[]{0.5, 0.5},
                       "actionIndex", 0));
        tracker.onSwarmEvent(event);

        assertEquals(1, tracker.getPendingCount());
    }

    @Test
    void ignoresIrrelevantEvents() {
        SwarmEvent event = new SwarmEvent(this, SwarmEvent.Type.SWARM_STARTED,
                "Started", "swarm-1");
        tracker.onSwarmEvent(event);

        assertEquals(0, tracker.getPendingCount());
        assertEquals(0, tracker.getTotalRewardsProcessed());
    }

    @Test
    void multipleDecisionsTrackedIndependently() {
        Decision d1 = Decision.create("skill_generation", new double[]{0.5, 0.5, 0.5, 1.0, 0.5, 0.15, 0.25, 0.1}, 0);
        Decision d2 = Decision.create("skill_generation", new double[]{0.8, 0.9, 0.7, 1.0, 0.6, 0.3, 0.25, 0.06}, 1);

        tracker.trackDecision("skill-A", d1);
        tracker.trackDecision("skill-B", d2);
        assertEquals(2, tracker.getPendingCount());

        tracker.recordSkillOutcome("skill-A", 0.9);
        assertEquals(1, tracker.getPendingCount());

        tracker.recordSkillOutcome("skill-B", 0.3);
        assertEquals(0, tracker.getPendingCount());
        assertEquals(2, tracker.getTotalRewardsProcessed());
    }
}
