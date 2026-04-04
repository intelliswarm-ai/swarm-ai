package ai.intelliswarm.swarmai.eval.competitor;

import java.time.Instant;
import java.util.Map;

/**
 * Objective comparison result for a single framework on a single application.
 * Collected by running the same application with the same LLM, same inputs,
 * and measuring quantitative + qualitative metrics.
 */
public record ComparisonResult(
        String framework,
        String application,
        String llmModel,

        // Quantitative
        int linesOfCode,
        long setupTimeMinutes,
        long executionTimeMs,
        long promptTokens,
        long completionTokens,
        double costUsd,
        double successRate,

        // Quality (LLM-as-judge, 1-10)
        double outputQualityScore,
        double correctnessScore,

        // Enterprise readiness (binary)
        boolean hasBudgetControl,
        boolean hasGovernanceGates,
        boolean hasMultiTenancy,
        boolean hasAuditTrail,
        boolean hasToolPermissions,
        boolean hasYamlConfig,
        boolean hasSelfImproving,

        // Meta
        Instant measuredAt,
        Map<String, Object> details
) {
    /** Compute a weighted enterprise readiness score (0-100). */
    public double enterpriseScore() {
        int count = 0;
        if (hasBudgetControl) count++;
        if (hasGovernanceGates) count++;
        if (hasMultiTenancy) count++;
        if (hasAuditTrail) count++;
        if (hasToolPermissions) count++;
        if (hasYamlConfig) count++;
        if (hasSelfImproving) count++;
        return (count / 7.0) * 100.0;
    }

    /** Compute token efficiency (lower = better). */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
