package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.rl.SkillGenerationContext;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer.GapAnalysis;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer.Recommendation;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SkillGapAnalyzer — the gatekeeper that decides whether a capability gap
 * should trigger skill generation.
 *
 * Philosophy: These tests are designed to REVEAL WEAKNESSES, not confirm happy paths.
 * Each test probes a specific decision boundary, adversarial input, or edge case that
 * could cause the analyzer to make a wrong decision (generating useless skills or
 * blocking genuine capability gaps). Tests that pass trivially are not worth writing.
 *
 * A test that fails here reveals a real gap analysis bug that will waste tokens
 * in production (false positive → useless skill generated) or block self-improvement
 * (false negative → genuine gap ignored).
 */
@DisplayName("SkillGapAnalyzer")
class SkillGapAnalyzerTest {

    private SkillGapAnalyzer analyzer;
    private SkillRegistry emptyRegistry;

    @BeforeEach
    void setUp() {
        analyzer = new SkillGapAnalyzer();
        emptyRegistry = new SkillRegistry();
    }

    // ================================================================
    // TOOL ERROR REJECTION — The #1 source of wasted skill generation.
    // Each test here represents a real false positive observed during
    // development: the reviewer saw a tool error and flagged it as a
    // "capability gap", but the real problem was bad input.
    // ================================================================

    @Nested
    @DisplayName("Tool Error Rejection (false positive prevention)")
    class ToolErrorRejection {

        @ParameterizedTest(name = "rejects network error pattern: \"{0}\"")
        @ValueSource(strings = {
            "Need tool for handling connection refused when scraping",
            "I/O error when accessing the financial API endpoint",
            "UnknownHostException for api.example.com — need alternative",
            "Socket timeout trying to reach the data provider",
            "Network error prevented data collection from source"
        })
        @DisplayName("rejects all network error patterns")
        void rejectsNetworkErrors(String gap) {
            GapAnalysis result = analyzer.analyze(gap, List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation(),
                "Network errors are tool usage problems, not missing capabilities. Gap: " + gap);
            assertEquals(0.0, result.score(), "Rejected gaps should score 0");
        }

        @ParameterizedTest(name = "rejects HTTP error pattern: \"{0}\"")
        @ValueSource(strings = {
            "Got HTTP 404 not found from the financial data endpoint",
            "403 forbidden when accessing the restricted API",
            "401 unauthorized — need authentication for this service",
            "Server returned status=500 internal error"
        })
        @DisplayName("rejects all HTTP error patterns")
        void rejectsHttpErrors(String gap) {
            GapAnalysis result = analyzer.analyze(gap, List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation(),
                "HTTP errors mean the tool reached the server. The URL/auth is wrong, " +
                "not the tool. Gap: " + gap);
        }

        @Test
        @DisplayName("rejects 'web_scrape failed' — existing tool name + failure language")
        void rejectsExistingToolFailure() {
            GapAnalysis result = analyzer.analyze(
                "The web_scrape tool was not effective at extracting structured data from this site",
                List.of(), emptyRegistry);

            assertEquals(Recommendation.SKIP, result.recommendation(),
                "Referencing an existing tool with failure language should be rejected. " +
                "The agent should use the tool differently, not get a new one.");
        }

        @Test
        @DisplayName("EDGE CASE: does not reject error words in legitimate gap descriptions")
        void doesNotRejectLegitimateGapWithErrorWord() {
            // "error" appears in the gap but it's about parsing error logs, not a tool error
            GapAnalysis result = analyzer.analyze(
                "Parse server error log files in JSON format and extract stack traces for each unique exception type, then aggregate counts",
                List.of(), emptyRegistry);

            // This SHOULD be accepted — parsing error logs is a legitimate capability gap
            // If this test fails, the error detection is too aggressive
            assertNotEquals(Recommendation.SKIP, result.recommendation(),
                "WEAKNESS DETECTED: Analyzer is too aggressive rejecting gaps that contain " +
                "'error' in a legitimate context (parsing error logs). " +
                "Recommendation: " + result.recommendation());
        }
    }

    // ================================================================
    // IMPOSSIBLE CAPABILITY DETECTION — Gaps that sound reasonable but
    // can't be implemented with our tool stack. The analyzer must reject
    // these to prevent generating skills that will always fail.
    // ================================================================

    @Nested
    @DisplayName("Impossible Capability Detection")
    class ImpossibleCapabilities {

        @Test
        @DisplayName("rejects sentiment analysis (needs external NLP)")
        void rejectsSentiment() {
            GapAnalysis result = analyzer.analyze(
                "Perform sentiment analysis on customer review texts to classify positive/negative",
                List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation());
        }

        @Test
        @DisplayName("rejects social media API access (needs OAuth)")
        void rejectsSocialMedia() {
            GapAnalysis result = analyzer.analyze(
                "Gather trending topics from Twitter API for market analysis",
                List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation());
        }

        @Test
        @DisplayName("rejects ML model training (needs ML infra)")
        void rejectsMLTraining() {
            GapAnalysis result = analyzer.analyze(
                "Train a machine learning model to predict and classify customer churn risk",
                List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation());
        }

        @Test
        @DisplayName("EDGE CASE: does not reject 'analysis' when it's data analysis, not NLP")
        void doesNotRejectDataAnalysis() {
            // "analysis" appears but this is data analysis, not sentiment analysis
            GapAnalysis result = analyzer.analyze(
                "Perform financial ratio analysis by extracting data from balance sheet CSV and computing debt-to-equity and current ratios",
                List.of(), emptyRegistry);

            assertTrue(result.shouldGenerate(),
                "WEAKNESS DETECTED: Analyzer incorrectly rejects data analysis as 'sentiment analysis'. " +
                "Score: " + result.score() + ", Recommendation: " + result.recommendation());
        }

        @Test
        @DisplayName("EDGE CASE: does not reject 'predict' when it's simple calculation")
        void doesNotRejectSimplePrediction() {
            GapAnalysis result = analyzer.analyze(
                "Calculate projected revenue by extrapolating from quarterly growth rates in the financial CSV data",
                List.of(), emptyRegistry);

            // "predict" is close to ML territory, but this is just extrapolation
            assertNotEquals(Recommendation.SKIP, result.recommendation(),
                "WEAKNESS DETECTED: Analyzer incorrectly rejects simple extrapolation as ML. " +
                "Recommendation: " + result.recommendation());
        }
    }

    // ================================================================
    // META-SKILL REJECTION — Skills that "teach the LLM what it already
    // knows". These waste tokens by injecting methodology/workflow
    // instructions that the LLM can produce from its training data.
    // ================================================================

    @Nested
    @DisplayName("Meta-Skill Rejection")
    class MetaSkillRejection {

        @Test
        @DisplayName("rejects pure methodology (LLM already knows best practices)")
        void rejectsMethodology() {
            GapAnalysis result = analyzer.analyze(
                "Need best practices for conducting competitive analysis with a structured approach",
                List.of(), emptyRegistry);
            assertEquals(Recommendation.SKIP, result.recommendation());
        }

        @Test
        @DisplayName("allows meta-pattern when tool composition is present")
        void allowsMetaWithToolComposition() {
            // Contains "structured approach" (meta) BUT also "extract" and "transform" (tool language)
            GapAnalysis result = analyzer.analyze(
                "Need a structured approach to extract data from XBRL files and transform the XML tags into a comparison table",
                List.of(), emptyRegistry);

            assertNotEquals(Recommendation.SKIP, result.recommendation(),
                "Should allow meta-pattern when tool composition language (extract, transform) is present");
        }

        @Test
        @DisplayName("EDGE CASE: framework + data processing should NOT be rejected")
        void doesNotRejectFrameworkWithDataProcessing() {
            GapAnalysis result = analyzer.analyze(
                "Build a data processing pipeline to parse multiple CSV sources, calculate aggregate metrics, and compose a summary report",
                List.of(), emptyRegistry);

            assertTrue(result.shouldGenerate(),
                "WEAKNESS DETECTED: 'pipeline' triggered meta-skill rejection even though " +
                "this is a genuine data processing task with tool composition. " +
                "Score: " + result.score());
        }
    }

    // ================================================================
    // SCORING BOUNDARY TESTS — Probe the exact thresholds where
    // decisions change. These reveal calibration problems.
    // ================================================================

    @Nested
    @DisplayName("Scoring and Decision Boundaries")
    class ScoringBoundaries {

        @Test
        @DisplayName("null input produces SKIP without exception")
        void handlesNull() {
            assertDoesNotThrow(() -> analyzer.analyze(null, List.of(), emptyRegistry));
            assertEquals(Recommendation.SKIP, analyzer.analyze(null, List.of(), emptyRegistry).recommendation());
        }

        @Test
        @DisplayName("empty string produces SKIP without exception")
        void handlesEmpty() {
            assertEquals(Recommendation.SKIP, analyzer.analyze("", List.of(), emptyRegistry).recommendation());
        }

        @Test
        @DisplayName("whitespace-only string produces SKIP")
        void handlesWhitespace() {
            assertEquals(Recommendation.SKIP, analyzer.analyze("   \t\n  ", List.of(), emptyRegistry).recommendation());
        }

        @Test
        @DisplayName("extremely long gap description does not crash or score > 1.0")
        void handlesVeryLongInput() {
            String longGap = "Parse and extract financial data from JSON API. ".repeat(100);
            GapAnalysis result = analyzer.analyze(longGap, List.of(), emptyRegistry);

            assertNotNull(result);
            assertTrue(result.score() <= 1.0, "Score should never exceed 1.0. Got: " + result.score());
            assertTrue(result.score() >= 0.0, "Score should never be negative. Got: " + result.score());
        }

        @Test
        @DisplayName("score components are bounded [0, 1]")
        void scoreIsBounded() {
            // Test with adversarial input that might push score out of bounds
            String adversarial = "do something fix the help with make it work for any given " +
                "data analysis report search format template validation conversion " +
                "just this one only for this specific";
            GapAnalysis result = analyzer.analyze(adversarial, List.of(), emptyRegistry);

            assertTrue(result.score() >= 0.0 && result.score() <= 1.0,
                "Score must be [0, 1] even for adversarial input. Got: " + result.score());
        }

        @Test
        @DisplayName("coverage check with null tools does not crash")
        void handlesNullTools() {
            GapAnalysis result = analyzer.analyze(
                "Parse financial data from CSV files and generate analysis report",
                null, emptyRegistry);
            assertNotNull(result);
        }

        @Test
        @DisplayName("coverage check with null registry does not crash")
        void handlesNullRegistry() {
            GapAnalysis result = analyzer.analyze(
                "Parse financial data from CSV files and generate analysis report",
                List.of(), null);
            assertNotNull(result);
        }

        @Test
        @DisplayName("specific description scores higher than vague description")
        void specificScoresHigherThanVague() {
            GapAnalysis vague = analyzer.analyze(
                "help with data stuff",
                List.of(), emptyRegistry);

            GapAnalysis specific = analyzer.analyze(
                "Parse CSV financial statements for any given company ticker, " +
                "extract revenue and net income columns, calculate year-over-year " +
                "growth rates, and output a JSON comparison table",
                List.of(), emptyRegistry);

            assertTrue(specific.score() > vague.score(),
                "WEAKNESS DETECTED: Specific gap (" + specific.score() + ") should score " +
                "higher than vague gap (" + vague.score() + ") but doesn't. " +
                "The clarity/novelty scoring may be miscalibrated.");
        }

        @Test
        @DisplayName("parameterized gap scores higher than one-off gap")
        void parameterizedScoresHigherThanOneOff() {
            GapAnalysis oneOff = analyzer.analyze(
                "Just this one time extract data from this specific quarterly earnings page only for AAPL",
                List.of(), emptyRegistry);

            GapAnalysis parameterized = analyzer.analyze(
                "For any given company ticker, extract quarterly earnings data from SEC filings and generate a comparison report",
                List.of(), emptyRegistry);

            assertTrue(parameterized.score() > oneOff.score(),
                "WEAKNESS DETECTED: Parameterized gap (" + parameterized.score() + ") should " +
                "score higher than one-off (" + oneOff.score() + "). Reuse scoring may need tuning.");
        }
    }

    // ================================================================
    // COVERAGE / NOVELTY — Does the analyzer correctly downgrade gaps
    // that are already covered by existing tools?
    // ================================================================

    @Nested
    @DisplayName("Tool Coverage and Novelty")
    class ToolCoverageAndNovelty {

        @Test
        @DisplayName("gap covered by existing tool recommends USE_EXISTING")
        void existingToolReducesNovelty() {
            BaseTool csvTool = createMockTool("csv_analysis",
                "Parse and analyze CSV data files with filtering, statistics, and aggregation");

            GapAnalysis result = analyzer.analyze(
                "Need to parse and analyze CSV data files with statistics",
                List.of(csvTool), emptyRegistry);

            // With a closely matching tool, should NOT recommend generating a new one
            assertFalse(result.recommendation() == Recommendation.GENERATE,
                "Should not GENERATE when existing tool closely matches. " +
                "Coverage: " + result.coverage().coverageLevel() + ", " +
                "Recommendation: " + result.recommendation());
        }

        @Test
        @DisplayName("gap NOT covered by irrelevant tool maintains high novelty")
        void irrelevantToolDoesNotReduceNovelty() {
            BaseTool emailTool = createMockTool("email_sender",
                "Send emails via SMTP with attachments and HTML content");

            GapAnalysis withIrrelevant = analyzer.analyze(
                "Parse XBRL financial documents and extract key SEC filing metrics",
                List.of(emailTool), emptyRegistry);

            GapAnalysis withNoTools = analyzer.analyze(
                "Parse XBRL financial documents and extract key SEC filing metrics",
                List.of(), emptyRegistry);

            // Email tool shouldn't affect XBRL parsing gap
            assertEquals(withNoTools.score(), withIrrelevant.score(), 0.05,
                "Irrelevant tool should not significantly affect score. " +
                "With email tool: " + withIrrelevant.score() + ", " +
                "Without: " + withNoTools.score());
        }

        @Test
        @DisplayName("many tools with partial coverage produce correct aggregate")
        void manyToolsPartialCoverage() {
            List<BaseTool> tools = List.of(
                createMockTool("web_search", "Search the web for information"),
                createMockTool("http_request", "Make HTTP GET/POST requests"),
                createMockTool("json_transform", "Parse and transform JSON data"),
                createMockTool("csv_analysis", "Analyze CSV files"),
                createMockTool("file_read", "Read file contents")
            );

            GapAnalysis result = analyzer.analyze(
                "Search for financial reports on the web, download the JSON data, and transform it into a comparison CSV",
                tools, emptyRegistry);

            // Multiple tools partially cover this gap — the analyzer should recognize
            // that while no single tool handles it, the combination might
            assertTrue(result.coverage().coverageLevel() > 0,
                "Coverage should be non-zero when tools partially match. Got: " + result.coverage().coverageLevel());
        }
    }

    // ================================================================
    // SKILL DEDUPLICATION — Does the analyzer correctly downgrade gaps
    // when a similar skill already exists in the registry?
    // ================================================================

    @Nested
    @DisplayName("Skill Deduplication")
    class SkillDeduplication {

        @Test
        @DisplayName("existing similar skill reduces score")
        void existingSkillReducesScore() {
            SkillRegistry registryWithSkill = new SkillRegistry();
            GeneratedSkill existing = new GeneratedSkill(
                "financial_csv_parser",
                "Parse CSV financial statements and extract key metrics for comparison",
                "test",
                "def result = 'parsed'\nresult",
                Map.of(), List.of());
            existing.setStatus(SkillStatus.VALIDATED);
            registryWithSkill.register(existing);

            String gap = "Parse CSV financial data and extract metrics for reporting";

            GapAnalysis withSkill = analyzer.analyze(gap, List.of(), registryWithSkill);
            GapAnalysis withoutSkill = analyzer.analyze(gap, List.of(), emptyRegistry);

            assertTrue(withoutSkill.score() >= withSkill.score(),
                "Existing similar skill should reduce score (deduplication). " +
                "With skill: " + withSkill.score() + ", Without: " + withoutSkill.score());
        }

        @Test
        @DisplayName("dissimilar existing skill does not reduce score")
        void dissimilarSkillDoesNotReduceScore() {
            SkillRegistry registryWithSkill = new SkillRegistry();
            GeneratedSkill unrelated = new GeneratedSkill(
                "weather_forecast",
                "Get weather forecast data for a given city",
                "test",
                "def result = 'sunny'\nresult",
                Map.of(), List.of());
            unrelated.setStatus(SkillStatus.VALIDATED);
            registryWithSkill.register(unrelated);

            String gap = "Parse XBRL financial documents and extract SEC filing metrics";

            GapAnalysis withSkill = analyzer.analyze(gap, List.of(), registryWithSkill);
            GapAnalysis withoutSkill = analyzer.analyze(gap, List.of(), emptyRegistry);

            assertEquals(withoutSkill.score(), withSkill.score(), 0.05,
                "Dissimilar skill should not affect score. " +
                "With weather skill: " + withSkill.score() + ", Without: " + withoutSkill.score());
        }
    }

    // ================================================================
    // SKILL TYPE RECOMMENDATION — Does the analyzer correctly identify
    // what TYPE of skill to create?
    // ================================================================

    @Nested
    @DisplayName("Skill Type Recommendation")
    class SkillTypeRecommendation {

        @Test
        @DisplayName("data transformation recommends CODE")
        void dataTransformRecommendsCode() {
            GapAnalysis result = analyzer.analyze(
                "Transform and parse XML financial data, extract key fields, convert to JSON",
                List.of(), emptyRegistry);
            assertEquals(SkillType.CODE, result.recommendedType());
        }

        @Test
        @DisplayName("routing task recommends COMPOSITE")
        void routingRecommendsComposite() {
            GapAnalysis result = analyzer.analyze(
                "Route different types of financial documents depending on their format to the appropriate parser",
                List.of(), emptyRegistry);
            assertEquals(SkillType.COMPOSITE, result.recommendedType());
        }

        @Test
        @DisplayName("tool composition + expertise recommends HYBRID")
        void compositionPlusExpertiseRecommendsHybrid() {
            GapAnalysis result = analyzer.analyze(
                "Analyze competitive data by first searching for company info, then extracting key metrics and evaluating market position",
                List.of(), emptyRegistry);
            assertEquals(SkillType.HYBRID, result.recommendedType(),
                "Tool composition (search + extract) combined with expertise (analyze, evaluate) should recommend HYBRID");
        }

        @Test
        @DisplayName("PROMPT skills are blocked below 0.70 score")
        void promptSkillsBlockedBelowThreshold() {
            // A gap that would get PROMPT type recommendation but shouldn't pass the bar
            GapAnalysis result = analyzer.analyze(
                "Recommend strategy and evaluate options for investment",
                List.of(), emptyRegistry);

            if (result.recommendedType() == SkillType.PROMPT) {
                assertEquals(Recommendation.SKIP, result.recommendation(),
                    "PROMPT skills below 0.70 should be blocked — they rarely add real capability");
            }
        }
    }

    // ================================================================
    // CONTEXT BUILDING FOR RL PolicyEngine — Verify the 8-dimensional
    // feature vector is correctly populated for LinUCB decisions.
    // ================================================================

    @Nested
    @DisplayName("SkillGenerationContext building (PolicyEngine integration)")
    class ContextBuilding {

        @Test
        @DisplayName("all 8 feature dimensions are populated and bounded")
        void allDimensionsPopulated() {
            SkillGenerationContext ctx = analyzer.buildContext(
                "Parse and extract financial data from JSON API responses for any company",
                List.of(createMockTool("http_request", "Make HTTP requests")),
                emptyRegistry);

            double[] features = ctx.toFeatureVector();
            assertEquals(8, features.length, "Feature vector should have 8 dimensions");

            for (int i = 0; i < features.length; i++) {
                assertTrue(features[i] >= 0.0 && features[i] <= 1.0,
                    "Feature[" + i + "] = " + features[i] + " is out of bounds [0, 1]");
            }
        }

        @Test
        @DisplayName("different gaps produce different feature vectors")
        void differentGapsProduceDifferentFeatures() {
            SkillGenerationContext simple = analyzer.buildContext(
                "fetch data", List.of(), emptyRegistry);
            SkillGenerationContext complex = analyzer.buildContext(
                "Parse XBRL financial documents, extract all taxonomy elements, cross-reference with SEC EDGAR filing index, compute year-over-year changes for each metric, and generate a structured JSON comparison report for any given company ticker symbol",
                List.of(), emptyRegistry);

            assertNotEquals(simple.clarityScore(), complex.clarityScore(),
                "Different gap quality should produce different clarity scores");
            assertTrue(complex.clarityScore() > simple.clarityScore(),
                "More detailed gap should have higher clarity");
            assertTrue(complex.gapDescriptionLength() > simple.gapDescriptionLength());
        }

        @Test
        @DisplayName("tool count and registry size are correctly reflected")
        void toolAndRegistryCounts() {
            List<BaseTool> tools = List.of(
                createMockTool("tool_a", "Tool A"),
                createMockTool("tool_b", "Tool B"),
                createMockTool("tool_c", "Tool C")
            );

            SkillRegistry reg = new SkillRegistry();
            GeneratedSkill skill = new GeneratedSkill("s1", "Skill 1", "test", "code", Map.of(), List.of());
            skill.setStatus(SkillStatus.VALIDATED);
            reg.register(skill);

            SkillGenerationContext ctx = analyzer.buildContext("Parse data", tools, reg);

            assertEquals(3, ctx.existingToolCount());
            assertEquals(1, ctx.registrySize());
        }

        @Test
        @DisplayName("null registry and null tools produce safe defaults")
        void nullSafety() {
            SkillGenerationContext ctx = analyzer.buildContext("Parse data", null, null);

            assertEquals(0, ctx.existingToolCount());
            assertEquals(0, ctx.registrySize());
            assertEquals(1.0, ctx.skillNoveltyScore(),
                "Null registry should produce max skill novelty (no duplicates possible)");
        }
    }

    // ================================================================
    // ADVERSARIAL INPUTS — Inputs designed to break or confuse the
    // analyzer. Real users won't craft these, but LLM-generated gap
    // descriptions from a hallucinating reviewer might look like these.
    // ================================================================

    @Nested
    @DisplayName("Adversarial and Edge Case Inputs")
    class AdversarialInputs {

        @Test
        @DisplayName("special characters in gap description don't crash analyzer")
        void specialCharacters() {
            assertDoesNotThrow(() -> analyzer.analyze(
                "Parse data with <xml> tags & \"quoted\" values — including ñ, ü, and 日本語",
                List.of(), emptyRegistry));
        }

        @Test
        @DisplayName("gap that is just repeated words should not recommend GENERATE")
        void repeatedWords() {
            GapAnalysis result = analyzer.analyze(
                "data data data data data data data data data data data data data data data",
                List.of(), emptyRegistry);
            // Repeated words should not trigger skill generation
            // Even if novelty is high (no tools), the low clarity should prevent GENERATE
            assertNotEquals(Recommendation.GENERATE, result.recommendation(),
                "WEAKNESS DETECTED: Repeated words should not produce GENERATE recommendation. " +
                "Score: " + result.score() + ". The scoring gives too much weight to novelty/reuse " +
                "relative to clarity. Consider: if clarity < 0.2, cap total score at 0.40.");
        }

        @Test
        @DisplayName("gap with every action verb crammed in")
        void allActionVerbs() {
            GapAnalysis result = analyzer.analyze(
                "Analyze generate extract transform calculate search fetch parse format compare aggregate filter sort",
                List.of(), emptyRegistry);
            // All verbs but no nouns or structure — should not score maximally
            assertTrue(result.score() <= 1.0, "Score must stay bounded. Got: " + result.score());
        }

        @Test
        @DisplayName("tool with empty name and description doesn't crash coverage check")
        void emptyTool() {
            BaseTool emptyTool = createMockTool("", "");
            assertDoesNotThrow(() -> analyzer.analyze(
                "Parse financial data from CSV files",
                List.of(emptyTool), emptyRegistry));
        }

        @Test
        @DisplayName("100 tools don't cause performance issues")
        void manyTools() {
            List<BaseTool> tools = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                tools.add(createMockTool("tool_" + i, "Description for tool number " + i));
            }

            long start = System.nanoTime();
            GapAnalysis result = analyzer.analyze(
                "Parse XBRL financial documents and extract metrics",
                tools, emptyRegistry);
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            assertNotNull(result);
            assertTrue(durationMs < 1000,
                "Analysis with 100 tools should complete in <1s. Took: " + durationMs + "ms");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private BaseTool createMockTool(String name, String description) {
        BaseTool tool = mock(BaseTool.class);
        when(tool.getFunctionName()).thenReturn(name);
        when(tool.getDescription()).thenReturn(description);
        when(tool.getTags()).thenReturn(List.of());
        return tool;
    }
}
