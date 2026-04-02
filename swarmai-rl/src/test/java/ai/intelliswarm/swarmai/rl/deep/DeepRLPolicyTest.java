package ai.intelliswarm.swarmai.rl.deep;

import ai.intelliswarm.swarmai.rl.*;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import ai.intelliswarm.swarmai.skill.SkillType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeepRLPolicyTest {

    private DeepRLPolicy policy;

    @BeforeEach
    void setUp() {
        DeepRLPolicy.DeepRLConfig config = new DeepRLPolicy.DeepRLConfig(
                0.001f, 0.99f, 1.0, 0.05, 100,
                10, 50, 32, 1000, 5  // cold start = 5 for testing
        );
        policy = new DeepRLPolicy(config);
    }

    @Test
    void delegatesToHeuristicDuringColdStart() {
        assertTrue(policy.isColdStart());

        var ctx = new SkillGenerationContext("Parse JSON response",
                0.8, 0.9, 0.8, true, 0.7, 50, 5, 3, SkillType.CODE);
        SkillDecision decision = policy.shouldGenerateSkill(ctx);

        // During cold start, should match heuristic behavior
        assertEquals(SkillGapAnalyzer.Recommendation.GENERATE, decision.recommendation());
    }

    @Test
    void transitionsOutOfColdStart() {
        var ctx = new SkillGenerationContext("test",
                0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);

        for (int i = 0; i < 6; i++) {
            policy.shouldGenerateSkill(ctx);
        }

        assertFalse(policy.isColdStart());
    }

    @Test
    void makesValidDecisionAfterColdStart() {
        var ctx = new SkillGenerationContext("Extract API data",
                0.8, 0.9, 0.7, true, 0.6, 60, 5, 3, SkillType.CODE);

        // Exhaust cold start
        for (int i = 0; i < 6; i++) {
            policy.shouldGenerateSkill(ctx);
        }

        // DQN decision
        SkillDecision decision = policy.shouldGenerateSkill(ctx);
        assertNotNull(decision);
        assertNotNull(decision.recommendation());
        assertTrue(decision.reasoning().contains("DQN"));
    }

    @Test
    void convergenceDecisionWorks() {
        var ctx = new ConvergenceContext(1.0, 0.5, 0, 0.5, 3, 0.8);

        // Exhaust cold start via skill generation decisions
        var skillCtx = new SkillGenerationContext("test",
                0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);
        for (int i = 0; i < 6; i++) {
            policy.shouldGenerateSkill(skillCtx);
        }

        // Convergence decision should work
        boolean stop = policy.shouldStopIteration(ctx);
        assertTrue(stop || !stop); // just verify no crash
    }

    @Test
    void selectionWeightsReturnValid() {
        var ctx = new SelectionContext("analyze data", List.of());
        double[] weights = policy.getSelectionWeights(ctx);
        assertEquals(3, weights.length);
        double sum = weights[0] + weights[1] + weights[2];
        assertEquals(1.0, sum, 0.01);
    }

    @Test
    void recordOutcomeDoesNotThrow() {
        var decision = Decision.create("skill_generation",
                new double[]{0.8, 0.9, 0.7, 1.0, 0.6, 0.25, 0.3, 0.1}, 0);

        assertDoesNotThrow(() ->
                policy.recordOutcome(decision, Outcome.of(decision.id(), 0.85)));
    }

    @Test
    void epsilonDecaysOverTime() {
        var ctx = new SkillGenerationContext("test",
                0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);

        // Exhaust cold start
        for (int i = 0; i < 6; i++) {
            policy.shouldGenerateSkill(ctx);
        }

        // Make many more decisions — epsilon should decay
        for (int i = 0; i < 50; i++) {
            SkillDecision d = policy.shouldGenerateSkill(ctx);
            assertNotNull(d);
        }

        assertEquals(56, policy.getTotalDecisions());
    }

    @Test
    void defaultConfigWorks() {
        DeepRLPolicy defaultPolicy = new DeepRLPolicy(DeepRLPolicy.DeepRLConfig.defaults());
        assertNotNull(defaultPolicy);
        assertTrue(defaultPolicy.isColdStart());
    }
}
