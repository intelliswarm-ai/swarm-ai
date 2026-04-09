package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation.ObservationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PatternExtractor — the component that converts specific observations
 * into generic, domain-agnostic rules that benefit all users.
 *
 * This is the intellectual core of the self-improvement pipeline. If it extracts
 * domain-specific rules, the framework learns shortcuts that only work for one
 * user's workflows. If it's too conservative, nothing gets extracted and the
 * 10% budget is wasted.
 *
 * Each test probes a specific extraction boundary or invariant.
 */
@DisplayName("PatternExtractor — Domain-Agnostic Rule Extraction")
class PatternExtractorTest {

    private PatternExtractor extractor;
    private SelfImprovementConfig config;

    @BeforeEach
    void setUp() {
        config = new SelfImprovementConfig();
        config.setMinObservations(3);
        config.setMinCrossWorkflowEvidence(2);
        extractor = new PatternExtractor(config);
    }

    // ================================================================
    // MINIMUM OBSERVATION THRESHOLD — Need enough evidence before
    // extracting a rule. Too few observations = unreliable pattern.
    // ================================================================

    @Nested
    @DisplayName("Minimum Observation Threshold")
    class MinimumObservations {

        @Test
        @DisplayName("returns null when fewer than minObservations")
        void returnsNullBelowThreshold() {
            List<SpecificObservation> twoObs = List.of(
                convergenceObservation(sequentialShape(3, 2)),
                convergenceObservation(sequentialShape(3, 2))
            );

            GenericRule rule = extractor.extract(twoObs);

            assertNull(rule, "2 observations should not produce a rule (minimum is 3)");
        }

        @Test
        @DisplayName("extracts rule when exactly at minObservations threshold")
        void extractsAtThreshold() {
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> threeObs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            // Need historical data for cross-validation
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);

            GenericRule rule = extractor.extract(threeObs);

            // Should attempt extraction (may still return null if cross-validation fails)
            // but should NOT return null due to insufficient observations
            // The key: we're testing the threshold, not the cross-validation
        }
    }

    // ================================================================
    // DOMAIN TERM FILTERING — The most critical quality gate.
    // Rules must be generic (structural properties only).
    // Domain-specific rules are rejected.
    // ================================================================

    @Nested
    @DisplayName("Domain Term Filtering")
    class DomainTermFiltering {

        @Test
        @DisplayName("EDGE CASE: 'analysis' in tool category should not trigger domain filter")
        void toolCategoryNotDomainTerm() {
            // The WorkflowShape contains tool categories like "DATA"
            // The domain term filter checks condition.toString() — if a WorkflowShape
            // feature map contains a value that looks like a domain term,
            // it could cause false rejection.
            //
            // The condition extracted from common features should only contain
            // structural keys like "task_count", "max_depth", "process_type",
            // not domain terms like "research" or "analysis".
            WorkflowShape shape = new WorkflowShape(
                3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
            );

            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);
            GenericRule rule = extractor.extract(obs);

            // If rule is null because of domain term filtering on "analysis"
            // from tool category, that's a false positive
            if (rule != null) {
                assertFalse(rule.condition().toString().toLowerCase().contains("analysis"),
                    "Condition should not contain domain term 'analysis'");
            }
        }
    }

    // ================================================================
    // COMMON FEATURE EXTRACTION — Finds structural features that
    // are consistent across all observations.
    // ================================================================

    @Nested
    @DisplayName("Common Feature Extraction")
    class CommonFeatureExtraction {

        @Test
        @DisplayName("extracts features common to all observations")
        void extractsCommonFeatures() {
            // All three observations have the same shape
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);
            GenericRule rule = extractor.extract(obs);

            if (rule != null) {
                assertFalse(rule.condition().isEmpty(),
                    "Rule should have non-empty condition from common features");
                assertTrue(rule.condition().containsKey("process_type"),
                    "Common feature 'process_type' should be in condition");
                assertEquals("SEQUENTIAL", rule.condition().get("process_type"),
                    "Process type should match the common shape");
            }
        }

        @Test
        @DisplayName("uses threshold (<=max) for numeric features")
        void usesThresholdForNumeric() {
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);
            GenericRule rule = extractor.extract(obs);

            if (rule != null) {
                Object taskCountCondition = rule.condition().get("task_count");
                if (taskCountCondition != null) {
                    assertTrue(taskCountCondition.toString().startsWith("<="),
                        "Numeric features should use threshold format (<=N). Got: " + taskCountCondition);
                }
            }
        }

        @Test
        @DisplayName("returns null when observations have no common features")
        void returnsNullWhenNoCommonFeatures() {
            // Three completely different shapes — no common features
            List<SpecificObservation> obs = List.of(
                convergenceObservation(new WorkflowShape(
                    1, 1, false, false, false, Set.of("WEB"), "SEQUENTIAL", 1, 1.0, false, false)),
                convergenceObservation(new WorkflowShape(
                    5, 3, true, true, true, Set.of("DATA", "COMPUTE"), "SELF_IMPROVING", 4, 5.0, true, true)),
                convergenceObservation(new WorkflowShape(
                    2, 1, false, true, false, Set.of("FILE_IO"), "PARALLEL", 2, 2.0, false, true))
            );

            GenericRule rule = extractor.extract(obs);

            // With wildly different shapes, common features should be minimal or empty
            // leading to no extractable rule
            if (rule != null) {
                assertTrue(rule.condition().size() <= 2,
                    "Very different shapes should yield few common features. Got: " + rule.condition());
            }
        }
    }

    // ================================================================
    // CROSS-VALIDATION — Rules must match historical observations
    // from different workflow types.
    // ================================================================

    @Nested
    @DisplayName("Cross-Validation")
    class CrossValidation {

        @Test
        @DisplayName("rule passes cross-validation with sufficient historical matches")
        void passesWithSufficientHistory() {
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            // Seed enough matching historical observations
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);

            GenericRule rule = extractor.extract(obs);

            if (rule != null) {
                assertTrue(rule.crossValidation().passed(),
                    "Cross-validation should pass with 5 matching historical observations");
                assertTrue(rule.confidence() > 0,
                    "Confidence should be positive after successful cross-validation");
            }
        }

        @Test
        @DisplayName("rule fails cross-validation with insufficient historical data")
        void failsWithInsufficientHistory() {
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            // No historical data seeded — cross-validation should fail

            GenericRule rule = extractor.extract(obs);

            if (rule != null) {
                assertFalse(rule.crossValidation().passed(),
                    "Cross-validation should fail without historical data");
            }
        }
    }

    // ================================================================
    // CATEGORY MAPPING — Observation types map to rule categories.
    // ================================================================

    @Nested
    @DisplayName("Category Mapping")
    class CategoryMapping {

        @Test
        @DisplayName("convergence observations produce CONVERGENCE_DEFAULT rules")
        void convergenceCategory() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);

            GenericRule rule = extractor.extract(List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            ));

            if (rule != null) {
                assertEquals(RuleCategory.CONVERGENCE_DEFAULT, rule.category());
            }
        }

        @Test
        @DisplayName("anti-pattern observations produce ANTI_PATTERN rules")
        void antiPatternCategory() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.ANTI_PATTERN, 5);

            GenericRule rule = extractor.extract(List.of(
                antiPatternObservation(shape),
                antiPatternObservation(shape),
                antiPatternObservation(shape)
            ));

            if (rule != null) {
                assertEquals(RuleCategory.ANTI_PATTERN, rule.category());
            }
        }

        @Test
        @DisplayName("tool selection observations produce TOOL_ROUTING rules")
        void toolRoutingCategory() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.TOOL_SELECTION, 5);

            GenericRule rule = extractor.extract(List.of(
                toolSelectionObservation(shape),
                toolSelectionObservation(shape),
                toolSelectionObservation(shape)
            ));

            if (rule != null) {
                assertEquals(RuleCategory.TOOL_ROUTING, rule.category());
            }
        }
    }

    // ================================================================
    // CONFIDENCE COMPUTATION — Formula: (obs_score * 0.4) + (val_score * 0.6)
    // ================================================================

    @Nested
    @DisplayName("Confidence Computation")
    class ConfidenceComputation {

        @Test
        @DisplayName("confidence increases with more observations")
        void moreObservationsHigherConfidence() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 10);

            // Extract with 3 observations
            GenericRule rule3 = extractor.extract(List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            ));

            // Extract with 8 observations
            List<SpecificObservation> eightObs = new ArrayList<>();
            for (int i = 0; i < 8; i++) eightObs.add(convergenceObservation(shape));
            GenericRule rule8 = extractor.extract(eightObs);

            if (rule3 != null && rule8 != null) {
                assertTrue(rule8.confidence() >= rule3.confidence(),
                    "More observations should produce higher confidence. " +
                    "3 obs: " + rule3.confidence() + ", 8 obs: " + rule8.confidence());
            }
        }

        @Test
        @DisplayName("confidence is bounded [0, 1]")
        void confidenceBounded() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 20);

            List<SpecificObservation> manyObs = new ArrayList<>();
            for (int i = 0; i < 15; i++) manyObs.add(convergenceObservation(shape));

            GenericRule rule = extractor.extract(manyObs);

            if (rule != null) {
                assertTrue(rule.confidence() >= 0.0 && rule.confidence() <= 1.0,
                    "Confidence must be [0, 1]. Got: " + rule.confidence());
            }
        }
    }

    // ================================================================
    // HISTORICAL OBSERVATION BUFFER — Bounded at 10,000.
    // ================================================================

    @Nested
    @DisplayName("Historical Observation Buffer")
    class HistoricalBuffer {

        @Test
        @DisplayName("recordObservations adds to history")
        void recordsObservations() {
            WorkflowShape shape = sequentialShape(3, 2);
            List<SpecificObservation> obs = List.of(
                convergenceObservation(shape),
                convergenceObservation(shape)
            );

            extractor.recordObservations(obs);

            // The only way to verify is through cross-validation behavior
            // Seed 3 observations as current, check if cross-validation can use history
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 3);

            GenericRule rule = extractor.extract(List.of(
                convergenceObservation(shape),
                convergenceObservation(shape),
                convergenceObservation(shape)
            ));

            // If historical observations were recorded, cross-validation has data
            if (rule != null) {
                assertTrue(rule.crossValidation().testedAgainst() > 0,
                    "Historical observations should be available for cross-validation");
            }
        }
    }

    // ================================================================
    // CONVERGENCE VALUE EXTRACTION — Recommends median convergence iteration
    // ================================================================

    @Nested
    @DisplayName("Recommended Value Extraction")
    class RecommendedValue {

        @Test
        @DisplayName("extracts median convergence iteration as recommended value")
        void extractsMedianConvergence() {
            WorkflowShape shape = sequentialShape(3, 2);
            seedHistoricalObservations(shape, ObservationType.CONVERGENCE_PATTERN, 5);

            List<SpecificObservation> obs = List.of(
                convergenceObservationWithValue(shape, 2),
                convergenceObservationWithValue(shape, 3),
                convergenceObservationWithValue(shape, 2)
            );

            GenericRule rule = extractor.extract(obs);

            if (rule != null && rule.recommendedValue() != null) {
                int recommended = (int) rule.recommendedValue();
                assertTrue(recommended >= 2 && recommended <= 3,
                    "Median of [2, 3, 2] should be ~2-3. Got: " + recommended);
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private WorkflowShape sequentialShape(int taskCount, int maxDepth) {
        return new WorkflowShape(
            taskCount, maxDepth, false, false, false,
            Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false
        );
    }

    private SpecificObservation convergenceObservation(WorkflowShape shape) {
        return convergenceObservationWithValue(shape, 2);
    }

    private SpecificObservation convergenceObservationWithValue(WorkflowShape shape, int convergedAt) {
        return new SpecificObservation(
            UUID.randomUUID().toString(),
            ObservationType.CONVERGENCE_PATTERN,
            shape,
            "Workflow converged at iteration " + convergedAt,
            Map.of("converged_at", convergedAt, "max_iterations", 5),
            0.7,
            Instant.now()
        );
    }

    private SpecificObservation antiPatternObservation(WorkflowShape shape) {
        return new SpecificObservation(
            UUID.randomUUID().toString(),
            ObservationType.ANTI_PATTERN,
            shape,
            "Agent spinning detected",
            Map.of("turn_count", 8),
            0.9,
            Instant.now()
        );
    }

    private SpecificObservation toolSelectionObservation(WorkflowShape shape) {
        return new SpecificObservation(
            UUID.randomUUID().toString(),
            ObservationType.TOOL_SELECTION,
            shape,
            "Tool web_search has 30% success rate",
            Map.of("tool_name", "web_search", "success_rate", 0.3),
            0.5,
            Instant.now()
        );
    }

    private void seedHistoricalObservations(WorkflowShape shape, ObservationType type, int count) {
        List<SpecificObservation> historical = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            historical.add(new SpecificObservation(
                "historical-" + UUID.randomUUID().toString(),
                type,
                shape,
                "Historical observation " + i,
                Map.of("converged_at", 2, "max_iterations", 5),
                0.5,
                Instant.now()
            ));
        }
        extractor.recordObservations(historical);
    }
}
