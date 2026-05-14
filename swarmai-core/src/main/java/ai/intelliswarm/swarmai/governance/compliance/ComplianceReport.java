package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated findings across one or more {@link ComplianceGate} evaluations.
 * {@link #pass()} is true iff every finding is below {@link ComplianceFinding.Severity#BLOCKER}.
 *
 * <p>Audit sinks should persist the full report (not just the boolean), so
 * regulators can later inspect <em>why</em> a particular output was held back
 * or shipped.
 *
 * @param pass     overall verdict
 * @param findings ordered findings (gate-emission order preserved)
 */
@PublicApi(since = "1.0.24")
public record ComplianceReport(boolean pass, List<ComplianceFinding> findings) {

    public ComplianceReport {
        Objects.requireNonNull(findings, "findings must not be null");
        findings = List.copyOf(findings);
    }

    public static ComplianceReport ofFindings(List<ComplianceFinding> findings) {
        boolean clean = findings.stream()
                .noneMatch(f -> f.severity() == ComplianceFinding.Severity.BLOCKER);
        return new ComplianceReport(clean, findings);
    }

    public static ComplianceReport clean() {
        return new ComplianceReport(true, List.of());
    }

    public long blockerCount() {
        return findings.stream()
                .filter(f -> f.severity() == ComplianceFinding.Severity.BLOCKER)
                .count();
    }

    public long warningCount() {
        return findings.stream()
                .filter(f -> f.severity() == ComplianceFinding.Severity.WARNING)
                .count();
    }
}
