package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.rl.SkillGenerationContext;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes capability gaps to determine whether a new skill should be generated.
 *
 * Follows the "context window is a public good" philosophy:
 * every skill competes for tokens with conversation history and other tools.
 * The bar for creating a skill is high — it must justify its existence by
 * providing knowledge that the LLM cannot already produce, being reusable,
 * and solving a real, recurring problem.
 *
 * This analyzer runs BEFORE skill generation to prevent unnecessary skill bloat.
 *
 * Decision framework:
 * 1. Does an existing tool/skill already handle this? (coverage check)
 * 2. Is this a one-off task or a recurring pattern? (reuse check)
 * 3. Can a simple prompt handle this without code? (complexity check)
 * 4. Does the gap describe something specific enough to implement? (clarity check)
 * 5. What is the token cost vs. value ratio? (ROI check)
 */
public class SkillGapAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(SkillGapAnalyzer.class);

    private static final double COVERAGE_THRESHOLD = 0.45;
    private static final int MIN_GAP_DESCRIPTION_LENGTH = 20;

    /**
     * Extracts sub-scores from a gap description into a {@link SkillGenerationContext}
     * for use by the {@link ai.intelliswarm.swarmai.rl.PolicyEngine}.
     */
    public SkillGenerationContext buildContext(String gapDescription,
                                               List<BaseTool> existingTools,
                                               SkillRegistry skillRegistry) {
        double clarityScore = assessClarity(gapDescription);
        CoverageResult coverage = assessCoverage(gapDescription, existingTools);
        double noveltyScore = 1.0 - coverage.coverageLevel();

        double skillNoveltyScore = 1.0;
        if (skillRegistry != null) {
            List<SkillRegistry.SimilarSkill> similar = skillRegistry.findSimilar(gapDescription, 0.3);
            if (!similar.isEmpty()) {
                skillNoveltyScore = 1.0 - similar.get(0).similarity();
            }
        }

        ComplexityAssessment complexity = assessComplexity(gapDescription);
        double reuseScore = assessReusability(gapDescription);

        return new SkillGenerationContext(
                gapDescription,
                clarityScore,
                noveltyScore,
                skillNoveltyScore,
                complexity.justifiesSkill(),
                reuseScore,
                gapDescription.length(),
                existingTools != null ? existingTools.size() : 0,
                skillRegistry != null ? skillRegistry.getActiveSkillCount() : 0,
                complexity.recommendedType()
        );
    }

    /**
     * Analyze a capability gap and recommend whether to generate a skill.
     * Returns a GapAnalysis with the recommendation and reasoning.
     */
    public GapAnalysis analyze(String gapDescription, List<BaseTool> existingTools,
                                SkillRegistry skillRegistry) {
        if (gapDescription == null || gapDescription.isBlank()) {
            return GapAnalysis.reject("Empty gap description");
        }

        List<String> reasons = new ArrayList<>();
        double score = 0.0;

        // 0. CRITICAL: Tool-error detection — reject gaps that describe tool failures
        //    The #1 false positive: reviewer sees "I/O error" and flags it as a capability gap,
        //    but the real problem is bad URLs or missing API keys, not a missing tool.
        ToolErrorCheck errorCheck = detectToolErrors(gapDescription);
        if (errorCheck.isToolError()) {
            reasons.add("REJECTED: This is a tool usage error, not a missing capability: " + errorCheck.reason());
            logger.info("Gap rejected (tool error pattern): {} — {}", truncate(gapDescription, 60), errorCheck.reason());
            return new GapAnalysis(gapDescription, Recommendation.SKIP, 0.0, reasons,
                new CoverageResult(0.0, List.of()), SkillType.CODE);
        }

        // 0b. Impossible-capability detection — reject gaps requiring external services we don't have
        String impossibleReason = detectImpossibleCapability(gapDescription);
        if (impossibleReason != null) {
            reasons.add("REJECTED: Requires external service not available: " + impossibleReason);
            logger.info("Gap rejected (impossible capability): {} — {}", truncate(gapDescription, 60), impossibleReason);
            return new GapAnalysis(gapDescription, Recommendation.SKIP, 0.0, reasons,
                new CoverageResult(0.0, List.of()), SkillType.CODE);
        }

        // 0c. Meta-skill detection — reject skills that teach the LLM what it already knows
        String metaReason = detectMetaSkill(gapDescription);
        if (metaReason != null) {
            reasons.add("REJECTED: Meta-skill detected: " + metaReason);
            logger.info("Gap rejected (meta-skill): {} — {}", truncate(gapDescription, 60), metaReason);
            return new GapAnalysis(gapDescription, Recommendation.SKIP, 0.0, reasons,
                new CoverageResult(0.0, List.of()), SkillType.CODE);
        }

        // 1. Clarity check — is the gap specific enough to implement?
        double clarityScore = assessClarity(gapDescription);
        score += clarityScore * 0.20;
        if (clarityScore < 0.3) {
            reasons.add("Gap description too vague (" + gapDescription.length() + " chars)");
        }

        // 2. Coverage check — do existing tools already handle this?
        CoverageResult coverage = assessCoverage(gapDescription, existingTools);
        double noveltyScore = 1.0 - coverage.coverageLevel();
        score += noveltyScore * 0.30;
        if (coverage.coverageLevel() > COVERAGE_THRESHOLD) {
            reasons.add("Existing tools cover " + String.format("%.0f%%", coverage.coverageLevel() * 100) +
                " of this gap: " + coverage.coveringTools());
        }

        // 3. Existing skill check — is there already a similar skill?
        double skillNoveltyScore = 1.0;
        if (skillRegistry != null) {
            List<SkillRegistry.SimilarSkill> similar = skillRegistry.findSimilar(gapDescription, 0.3);
            if (!similar.isEmpty()) {
                SkillRegistry.SimilarSkill best = similar.get(0);
                skillNoveltyScore = 1.0 - best.similarity();
                reasons.add("Similar skill exists: '" + best.skill().getName() +
                    "' (similarity: " + String.format("%.0f%%", best.similarity() * 100) + ")");
            }
        }
        score += skillNoveltyScore * 0.20;

        // 4. Complexity check — does this need code or just a prompt?
        ComplexityAssessment complexity = assessComplexity(gapDescription);
        score += complexity.justifiesSkill() ? 0.15 : 0.05;
        reasons.add("Recommended type: " + complexity.recommendedType());

        // 5. Reuse check — is this a pattern that will be used again?
        double reuseScore = assessReusability(gapDescription);
        score += reuseScore * 0.15;
        if (reuseScore < 0.3) {
            reasons.add("Low reuse potential — may be a one-off task");
        }

        // Decision
        Recommendation recommendation;
        if (score >= 0.60) {
            recommendation = Recommendation.GENERATE;
        } else if (score >= 0.40) {
            recommendation = Recommendation.GENERATE_SIMPLE;
        } else if (coverage.coverageLevel() > COVERAGE_THRESHOLD) {
            recommendation = Recommendation.USE_EXISTING;
        } else {
            recommendation = Recommendation.SKIP;
        }

        // Block PROMPT skills unless score is very high — they rarely add real capability
        if (complexity.recommendedType() == SkillType.PROMPT &&
            recommendation != Recommendation.SKIP &&
            score < 0.70) {
            recommendation = Recommendation.SKIP;
            reasons.add("PROMPT skill blocked (score " + String.format("%.2f", score) + " < 0.70). Only CODE/HYBRID/COMPOSITE skills are generated for capability gaps.");
        }

        GapAnalysis analysis = new GapAnalysis(
            gapDescription, recommendation, score, reasons,
            coverage, complexity.recommendedType()
        );

        logger.info("Gap analysis: {} (score={:.2f}, recommendation={})",
            truncate(gapDescription, 60), score, recommendation);

        return analysis;
    }

    /**
     * Detect whether a "capability gap" is actually a tool usage error.
     * This is the #1 quality problem in skill generation: the reviewer sees a tool
     * returning errors (I/O failure, 404, connection refused) and thinks a new tool
     * is needed, when the real problem is bad input (fake URLs, missing API keys).
     *
     * A new skill wrapping the same failing tool with retries will still fail.
     */
    private ToolErrorCheck detectToolErrors(String gap) {
        String lower = gap.toLowerCase();

        // Pattern 1: Network/IO errors — the tool worked but the URL was bad
        String[] networkErrorPatterns = {
            "i/o error", "io error", "connection refused", "connection timed out",
            "unknownhostexception", "unknown host", "unreachable",
            "socket timeout", "connect timeout", "network error"
        };
        for (String pattern : networkErrorPatterns) {
            if (lower.contains(pattern)) {
                return new ToolErrorCheck(true,
                    "Network error detected ('" + pattern + "'). The existing tool works — the problem is the URL or endpoint, not the tool itself.");
            }
        }

        // Pattern 2: HTTP status errors — tool reached the server but got an error
        String[] httpErrorPatterns = {
            "http 404", "http 403", "http 401", "http 500",
            "status=404", "status=403", "status=401", "status=500",
            "404 not found", "403 forbidden", "401 unauthorized"
        };
        for (String pattern : httpErrorPatterns) {
            if (lower.contains(pattern)) {
                return new ToolErrorCheck(true,
                    "HTTP error detected ('" + pattern + "'). The tool reached the server — the URL or auth is wrong, not the tool.");
            }
        }

        // Pattern 3: Retry/resilience requests — these wrap existing tools, not add new capability
        if ((lower.contains("retry") || lower.contains("resilien") || lower.contains("robust")) &&
            (lower.contains("error handling") || lower.contains("failure") || lower.contains("fallback"))) {
            // Only reject if combined with error-related language
            if (lower.contains("failed") || lower.contains("error") || lower.contains("i/o")) {
                return new ToolErrorCheck(true,
                    "Retry/resilience wrapper requested for a failing tool. The root cause is bad input (URLs/keys), not missing retry logic.");
            }
        }

        // Pattern 4: "More robust" version of an existing tool
        if (lower.contains("more robust") && (lower.contains("api") || lower.contains("request") || lower.contains("scraping"))) {
            if (lower.contains("error") || lower.contains("failed") || lower.contains("i/o")) {
                return new ToolErrorCheck(true,
                    "Request for 'more robust' version of existing tool. The issue is input quality (fake URLs), not tool robustness.");
            }
        }

        // Pattern 5: Gap mentions specific existing tool names with failure language
        String[] existingToolNames = {"http_request", "web_scrape", "web_search", "shell_command"};
        for (String toolName : existingToolNames) {
            if (lower.contains(toolName) && (lower.contains("failed") || lower.contains("error") ||
                lower.contains("not effective") || lower.contains("insufficient"))) {
                return new ToolErrorCheck(true,
                    "Gap references existing tool '" + toolName + "' with failure language. This is a quality issue — the agent should use the tool differently, not get a new one.");
            }
        }

        return new ToolErrorCheck(false, null);
    }

    private record ToolErrorCheck(boolean isToolError, String reason) {}

    /**
     * Detect gaps requesting capabilities that require external services we don't have.
     * No amount of skill generation can create sentiment analysis, NLP, or real-time feeds
     * without actual external APIs. These are "impossible" with our current tool stack.
     */
    private String detectImpossibleCapability(String gap) {
        String lower = gap.toLowerCase();

        // Sentiment analysis / NLP — requires external NLP API (not buildable from our tools)
        if ((lower.contains("sentiment") && (lower.contains("analysis") || lower.contains("analyz"))) ||
            (lower.contains("nlp") || lower.contains("natural language processing")) ||
            (lower.contains("opinion mining") || lower.contains("emotion detection"))) {
            return "Sentiment analysis requires an external NLP service (not buildable from http_request + web_scrape). " +
                "The agent should instead scrape user reviews from real sites and present them as-is.";
        }

        // Social media APIs — require OAuth tokens and platform-specific APIs
        if ((lower.contains("social media") && (lower.contains("api") || lower.contains("gather") || lower.contains("aggregate"))) ||
            (lower.contains("twitter") || lower.contains("reddit api") || lower.contains("facebook api"))) {
            return "Social media APIs require platform-specific OAuth credentials we don't have.";
        }

        // Real-time data feeds — require WebSocket/streaming connections
        if (lower.contains("real-time") && (lower.contains("feed") || lower.contains("stream") || lower.contains("live"))) {
            return "Real-time data feeds require WebSocket/streaming infrastructure we don't have.";
        }

        // User feedback aggregation from "multiple platforms" — too vague and requires multiple APIs
        if (lower.contains("user feedback") && (lower.contains("aggregate") || lower.contains("multiple") || lower.contains("forums"))) {
            if (lower.contains("automatically") || lower.contains("gather")) {
                return "Automatic user feedback aggregation from multiple platforms requires platform-specific APIs. " +
                    "The agent should instead use web_scrape on specific known review pages.";
            }
        }

        // Machine learning / model training — requires ML infrastructure
        if ((lower.contains("machine learning") || lower.contains("train") || lower.contains("model")) &&
            (lower.contains("classify") || lower.contains("predict") || lower.contains("neural"))) {
            return "ML model training/inference requires ML infrastructure we don't have.";
        }

        return null;
    }

    /**
     * Detect whether a gap describes a "meta-skill" — something that teaches the LLM
     * what it already knows (methodologies, frameworks, structured approaches).
     * These produce PROMPT skills that add no real capability.
     */
    private String detectMetaSkill(String gap) {
        String lower = gap.toLowerCase();
        // Skills about "how to think" rather than "what data to process"
        String[] metaPatterns = {
            "orchestrat", "workflow guide", "methodology", "structured approach",
            "guide the llm", "teach the agent", "step-by-step framework",
            "best practices for", "how to approach"
        };
        boolean hasMetaPattern = false;
        for (String pattern : metaPatterns) {
            if (lower.contains(pattern)) { hasMetaPattern = true; break; }
        }
        if (!hasMetaPattern) return null;

        // Exception: if it also has tool-composition language, it might be a real tool
        String[] toolPatterns = {"parse", "extract", "transform", "calculate", "compose", "pipeline", "execute"};
        for (String pattern : toolPatterns) {
            if (lower.contains(pattern)) return null; // Has tool language, allow it
        }
        return "Gap describes a methodology/workflow rather than a data-processing tool. The LLM already knows methodologies.";
    }

    private double assessClarity(String gap) {
        if (gap.length() < MIN_GAP_DESCRIPTION_LENGTH) return 0.1;

        double score = 0.0;

        // Length contributes to clarity (diminishing returns)
        score += Math.min(0.4, gap.length() / 200.0);

        // Contains actionable verbs
        String lower = gap.toLowerCase();
        String[] actionVerbs = {"analyze", "generate", "extract", "transform", "calculate",
            "search", "fetch", "parse", "format", "compare", "aggregate", "filter", "sort"};
        for (String verb : actionVerbs) {
            if (lower.contains(verb)) { score += 0.15; break; }
        }

        // Contains specific nouns (data types, formats, domains)
        String[] specificNouns = {"json", "csv", "xml", "api", "database", "stock", "financial",
            "report", "chart", "table", "url", "html", "email", "webhook"};
        for (String noun : specificNouns) {
            if (lower.contains(noun)) { score += 0.15; break; }
        }

        // Has parameters or entities
        if (lower.contains("ticker") || lower.contains("company") ||
            lower.contains("input") || lower.contains("output")) {
            score += 0.15;
        }

        // Penalize very generic descriptions
        String[] genericPatterns = {"do something", "help with", "make it work", "fix the"};
        for (String pattern : genericPatterns) {
            if (lower.contains(pattern)) { score -= 0.2; break; }
        }

        return Math.max(0, Math.min(1, score));
    }

    private CoverageResult assessCoverage(String gap, List<BaseTool> tools) {
        if (tools == null || tools.isEmpty()) return new CoverageResult(0.0, List.of());

        String gapLower = gap.toLowerCase();
        Set<String> gapWords = tokenize(gapLower);

        List<String> coveringTools = new ArrayList<>();
        double maxCoverage = 0.0;

        for (BaseTool tool : tools) {
            Set<String> toolWords = new HashSet<>();
            toolWords.addAll(tokenize(tool.getFunctionName()));
            toolWords.addAll(tokenize(tool.getDescription()));
            tool.getTags().forEach(t -> toolWords.addAll(tokenize(t)));

            // Jaccard similarity
            Set<String> intersection = new HashSet<>(gapWords);
            intersection.retainAll(toolWords);
            Set<String> union = new HashSet<>(gapWords);
            union.addAll(toolWords);
            double similarity = union.isEmpty() ? 0 : (double) intersection.size() / union.size();

            if (similarity > 0.2) {
                coveringTools.add(tool.getFunctionName() + " (" + String.format("%.0f%%", similarity * 100) + ")");
            }
            maxCoverage = Math.max(maxCoverage, similarity);
        }

        return new CoverageResult(maxCoverage, coveringTools);
    }

    private ComplexityAssessment assessComplexity(String gap) {
        String lower = gap.toLowerCase();

        // Tool composition indicators (needs CODE)
        boolean needsToolComposition = lower.contains("combine") || lower.contains("compose") ||
            lower.contains("pipeline") || lower.contains("chain") ||
            lower.contains("then") || lower.contains("first") ||
            (lower.contains("search") && lower.contains("analyze"));

        // Data transformation indicators (needs CODE)
        boolean needsDataTransform = lower.contains("transform") || lower.contains("parse") ||
            lower.contains("extract") || lower.contains("convert") ||
            lower.contains("calculate") || lower.contains("compute");

        // Domain expertise indicators (needs PROMPT)
        boolean needsExpertise = lower.contains("analyze") || lower.contains("recommend") ||
            lower.contains("evaluate") || lower.contains("assess") ||
            lower.contains("strategy") || lower.contains("framework");

        // Multi-step reasoning (needs HYBRID)
        boolean needsReasoning = lower.contains("reason") || lower.contains("consider") ||
            lower.contains("weigh") || lower.contains("compare") ||
            lower.contains("decide") || lower.contains("judge");

        // Multi-agent / routing (needs COMPOSITE)
        boolean needsRouting = lower.contains("route") || lower.contains("dispatch") ||
            lower.contains("different types") || lower.contains("multiple") ||
            lower.contains("depending on");

        SkillType recommended;
        if (needsRouting) recommended = SkillType.COMPOSITE;
        else if (needsToolComposition && needsExpertise) recommended = SkillType.HYBRID;
        else if (needsToolComposition || needsDataTransform) recommended = SkillType.CODE;
        else if (needsReasoning && needsExpertise) recommended = SkillType.HYBRID;
        else recommended = SkillType.CODE; // Default to CODE — PROMPT skills rarely add real capability

        boolean justifies = needsToolComposition || needsDataTransform || needsRouting ||
                           (needsExpertise && lower.length() > 50);

        return new ComplexityAssessment(recommended, justifies);
    }

    private double assessReusability(String gap) {
        String lower = gap.toLowerCase();

        double score = 0.3; // base

        // Parameterized tasks are reusable
        if (lower.contains("for any") || lower.contains("given a") ||
            lower.contains("for each") || lower.contains("parameterized")) {
            score += 0.3;
        }

        // Domain-general tasks are reusable
        String[] reusablePatterns = {"data", "analysis", "report", "search", "format",
            "template", "validation", "conversion"};
        for (String pattern : reusablePatterns) {
            if (lower.contains(pattern)) { score += 0.1; }
        }

        // Very specific one-off tasks are not reusable
        if (lower.contains("this specific") || lower.contains("just this one") ||
            lower.contains("only for")) {
            score -= 0.3;
        }

        return Math.max(0, Math.min(1, score));
    }

    private Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
            .filter(t -> t.length() > 2)
            .collect(Collectors.toSet());
    }

    private String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen ? text.substring(0, maxLen - 3) + "..." : text;
    }

    // ==================== Result Types ====================

    public enum Recommendation {
        GENERATE,         // Full skill generation recommended
        GENERATE_SIMPLE,  // Generate a simple PROMPT skill (low complexity)
        USE_EXISTING,     // Existing tools can handle this
        SKIP              // Not worth creating (too vague, one-off, etc.)
    }

    public record GapAnalysis(
        String gapDescription,
        Recommendation recommendation,
        double score,
        List<String> reasons,
        CoverageResult coverage,
        SkillType recommendedType
    ) {
        public boolean shouldGenerate() {
            return recommendation == Recommendation.GENERATE ||
                   recommendation == Recommendation.GENERATE_SIMPLE;
        }

        public static GapAnalysis reject(String reason) {
            return new GapAnalysis("", Recommendation.SKIP, 0.0, List.of(reason),
                new CoverageResult(0.0, List.of()), SkillType.CODE);
        }
    }

    public record CoverageResult(double coverageLevel, List<String> coveringTools) {}

    private record ComplexityAssessment(SkillType recommendedType, boolean justifiesSkill) {}
}
