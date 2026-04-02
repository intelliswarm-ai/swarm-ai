package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import ai.intelliswarm.swarmai.skill.SkillType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicPolicyTest {

    private HeuristicPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new HeuristicPolicy();
    }

    @Nested
    class SkillGenerationDecisions {

        @Test
        void highScoreRecommendsGenerate() {
            // clarity=0.8, novelty=0.9, skillNovelty=0.8, complexity=true, reuse=0.7
            // score = 0.8*0.20 + 0.9*0.30 + 0.8*0.20 + 0.15 + 0.7*0.15 = 0.16+0.27+0.16+0.15+0.105 = 0.845
            var ctx = new SkillGenerationContext("Parse JSON API response",
                    0.8, 0.9, 0.8, true, 0.7, 50, 5, 3, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            assertEquals(SkillGapAnalyzer.Recommendation.GENERATE, decision.recommendation());
            assertTrue(decision.confidence() >= 0.60);
        }

        @Test
        void mediumScoreRecommendsGenerateSimple() {
            // clarity=0.5, novelty=0.5, skillNovelty=0.5, complexity=false, reuse=0.3
            // score = 0.5*0.20 + 0.5*0.30 + 0.5*0.20 + 0.05 + 0.3*0.15 = 0.10+0.15+0.10+0.05+0.045 = 0.445
            var ctx = new SkillGenerationContext("Analyze something",
                    0.5, 0.5, 0.5, false, 0.3, 30, 5, 3, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            assertEquals(SkillGapAnalyzer.Recommendation.GENERATE_SIMPLE, decision.recommendation());
        }

        @Test
        void lowScoreWithHighCoverageRecommendsUseExisting() {
            // noveltyScore=0.3 means coverage=0.7, which is >= 0.45
            // score will be low but coverage high
            var ctx = new SkillGenerationContext("Search the web",
                    0.3, 0.3, 0.3, false, 0.2, 20, 10, 5, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            assertEquals(SkillGapAnalyzer.Recommendation.USE_EXISTING, decision.recommendation());
        }

        @Test
        void veryLowScoreWithHighNoveltyRecommendsSkip() {
            // High novelty (0.9) means low coverage, so USE_EXISTING won't trigger.
            // But score is still very low → SKIP
            var ctx = new SkillGenerationContext("help",
                    0.1, 0.9, 0.1, false, 0.1, 5, 10, 5, SkillType.CODE);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            assertEquals(SkillGapAnalyzer.Recommendation.SKIP, decision.recommendation());
        }

        @Test
        void promptTypeBlockedBelowThreshold() {
            // Score ~0.5 which is above GENERATE_SIMPLE but below PROMPT_BLOCK (0.70)
            var ctx = new SkillGenerationContext("Summarize data",
                    0.6, 0.6, 0.6, false, 0.4, 30, 5, 3, SkillType.PROMPT);
            SkillDecision decision = policy.shouldGenerateSkill(ctx);
            assertEquals(SkillGapAnalyzer.Recommendation.SKIP, decision.recommendation());
            assertTrue(decision.reasoning().contains("PROMPT"));
        }

        @Test
        void decisionHasUniqueId() {
            var ctx = new SkillGenerationContext("test", 0.5, 0.5, 0.5, false, 0.5,
                    30, 5, 3, SkillType.CODE);
            SkillDecision d1 = policy.shouldGenerateSkill(ctx);
            SkillDecision d2 = policy.shouldGenerateSkill(ctx);
            assertNotEquals(d1.decisionId(), d2.decisionId());
        }
    }

    @Nested
    class ConvergenceDecisions {

        @Test
        void progressResetsStaleCounter() {
            // Stale, stale, then progress → should not stop
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 1, 1.0)); // stale
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 2, 1.0)); // stale
            boolean stop = policy.shouldStopIteration(new ConvergenceContext(1.5, 0.5, 1, 0.5, 3, 1.0)); // progress
            assertFalse(stop);
        }

        @Test
        void threeStaleIterationsStops() {
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 1, 1.0));
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 2, 1.0));
            boolean stop = policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 3, 1.0));
            assertTrue(stop);
        }

        @Test
        void outputGrowthPreventsStale() {
            // Growth > 1.1 means output grew → not stale
            boolean stop = policy.shouldStopIteration(new ConvergenceContext(1.2, 1.0, 0, 0, 1, 1.0));
            assertFalse(stop);
        }

        @Test
        void newSkillsPreventsStale() {
            // New skills generated → agent adapted → not stale
            boolean stop = policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 2, 0, 1, 1.0));
            assertFalse(stop);
        }

        @Test
        void resetClearsState() {
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 1, 1.0));
            policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 2, 1.0));
            policy.reset();
            boolean stop = policy.shouldStopIteration(new ConvergenceContext(1.0, 1.0, 0, 0, 3, 1.0));
            assertFalse(stop); // reset means this is only the 1st stale iteration
        }
    }

    @Nested
    class SelectionWeightDecisions {

        @Test
        void returnsFixedWeights() {
            var ctx = new SelectionContext("analyze data", List.of());
            double[] weights = policy.getSelectionWeights(ctx);
            assertEquals(3, weights.length);
            assertEquals(0.5, weights[0], 0.001);
            assertEquals(0.3, weights[1], 0.001);
            assertEquals(0.2, weights[2], 0.001);
        }

        @Test
        void returnsCopyNotReference() {
            var ctx = new SelectionContext("test", List.of());
            double[] w1 = policy.getSelectionWeights(ctx);
            double[] w2 = policy.getSelectionWeights(ctx);
            assertNotSame(w1, w2);
        }
    }

    @Test
    void recordOutcomeIsNoOp() {
        // Should not throw
        assertDoesNotThrow(() ->
                policy.recordOutcome(
                        Decision.create("test", new double[]{1.0}, 0),
                        Outcome.of("test", 1.0)));
    }
}
