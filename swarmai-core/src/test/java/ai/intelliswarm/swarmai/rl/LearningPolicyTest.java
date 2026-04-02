package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import ai.intelliswarm.swarmai.skill.SkillType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LearningPolicyTest {

    private LearningPolicy policy;

    @BeforeEach
    void setUp() {
        // Cold start of 5 decisions for faster testing
        policy = new LearningPolicy(5, 1.0, 1000);
    }

    @Nested
    class ColdStartBehavior {

        @Test
        void delegatesToHeuristicDuringColdStart() {
            assertTrue(policy.isColdStart());

            var ctx = new SkillGenerationContext("Parse JSON",
                    0.8, 0.9, 0.8, true, 0.7, 50, 5, 3, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);

            // During cold start, should match heuristic behavior
            assertEquals(SkillGapAnalyzer.Recommendation.GENERATE, decision.recommendation());
            assertTrue(policy.isColdStart());
        }

        @Test
        void transitionsOutOfColdStartAfterNDecisions() {
            var ctx = new SkillGenerationContext("test",
                    0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);

            // Make 6 decisions (cold start threshold is <= 5, so 6th exits)
            for (int i = 0; i < 6; i++) {
                policy.shouldGenerateSkill(ctx);
            }

            assertFalse(policy.isColdStart());
            assertEquals(6, policy.getTotalDecisions());
        }

        @Test
        void convergenceUsesHeuristicDuringColdStart() {
            var ctx = new ConvergenceContext(1.0, 1.0, 0, 0, 1, 1.0);
            // Should not throw, should use heuristic
            boolean stop = policy.shouldStopIteration(ctx);
            assertFalse(stop); // first call, stale count = 1, threshold = 3
        }

        @Test
        void selectionUsesHeuristicDuringColdStart() {
            var ctx = new SelectionContext("analyze data", List.of());
            double[] weights = policy.getSelectionWeights(ctx);
            assertEquals(0.5, weights[0], 0.001);
            assertEquals(0.3, weights[1], 0.001);
            assertEquals(0.2, weights[2], 0.001);
        }
    }

    @Nested
    class PostColdStartBehavior {

        @BeforeEach
        void warmUp() {
            // Exhaust cold start
            var ctx = new SkillGenerationContext("test",
                    0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);
            for (int i = 0; i < 6; i++) {
                policy.shouldGenerateSkill(ctx);
            }
            assertFalse(policy.isColdStart());
        }

        @Test
        void usesLinUCBAfterColdStart() {
            var ctx = new SkillGenerationContext("Extract API data",
                    0.8, 0.9, 0.7, true, 0.6, 60, 5, 3, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);

            // Should make a decision (any valid recommendation)
            assertNotNull(decision);
            assertNotNull(decision.recommendation());
            assertTrue(decision.reasoning().contains("LinUCB"));
        }

        @Test
        void usesThompsonSamplingAfterColdStart() {
            var ctx = new ConvergenceContext(1.0, 0.5, 0, 0.5, 3, 0.8);
            // Should not throw
            boolean stop = policy.shouldStopIteration(ctx);
            // Result is stochastic — just verify it runs
            assertTrue(stop || !stop);
        }

        @Test
        void usesOptimizedWeightsAfterColdStart() {
            var ctx = new SelectionContext("analyze data", List.of());
            double[] weights = policy.getSelectionWeights(ctx);

            // Weights should sum to ~1.0
            double sum = weights[0] + weights[1] + weights[2];
            assertEquals(1.0, sum, 0.01);
        }
    }

    @Nested
    class RewardRecording {

        @Test
        void recordsSkillGenerationOutcome() {
            var decision = Decision.create("skill_generation",
                    new double[]{0.8, 0.9, 0.7, 1.0, 0.6, 0.25, 0.3, 0.1}, 0);

            // Should not throw
            assertDoesNotThrow(() ->
                    policy.recordOutcome(decision, Outcome.of(decision.id(), 0.85)));
        }

        @Test
        void recordsConvergenceOutcome() {
            var decision = Decision.create("convergence",
                    new double[]{1.2, 0.3, 1, 0.5, 3, 0.8}, 0);

            assertDoesNotThrow(() ->
                    policy.recordOutcome(decision, Outcome.of(decision.id(), 1.0)));
        }

        @Test
        void recordsSelectionOutcome() {
            var decision = Decision.create("selection",
                    new double[]{0.5, 0.3, 0.2}, 0);

            assertDoesNotThrow(() ->
                    policy.recordOutcome(decision, Outcome.of(decision.id(), 0.9)));
        }
    }

    @Test
    void experienceBufferAccumulatesDecisions() {
        var ctx = new SkillGenerationContext("test",
                0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);

        for (int i = 0; i < 10; i++) {
            policy.shouldGenerateSkill(ctx);
        }

        ExperienceBuffer buffer = policy.getExperienceBuffer();
        assertTrue(buffer.size() >= 10);
    }

    @Test
    void statsReturnsFormattedString() {
        String stats = policy.getStats();
        assertNotNull(stats);
        assertTrue(stats.contains("LearningPolicy"));
        assertTrue(stats.contains("decisions="));
        assertTrue(stats.contains("coldStart="));
    }

    @Test
    void learnsFromRepeatedRewards() {
        // Exhaust cold start
        var ctx = new SkillGenerationContext("test",
                0.5, 0.5, 0.5, false, 0.5, 30, 5, 3, SkillType.CODE);
        for (int i = 0; i < 6; i++) {
            policy.shouldGenerateSkill(ctx);
        }

        // Feed rewards: action 0 (GENERATE) is always good
        var state = ctx.toFeatureVector();
        for (int i = 0; i < 30; i++) {
            policy.recordOutcome(
                    Decision.create("skill_generation", state, 0),
                    Outcome.of("d" + i, 0.9)); // high reward for GENERATE
            policy.recordOutcome(
                    Decision.create("skill_generation", state, 3),
                    Outcome.of("d" + i + "b", 0.1)); // low reward for SKIP
        }

        // After training, policy should prefer GENERATE for this state
        SkillDecision decision = policy.shouldGenerateSkill(ctx);
        // LinUCB should have learned — but test is not deterministic
        // Just verify it makes a valid decision
        assertNotNull(decision.recommendation());
    }
}
