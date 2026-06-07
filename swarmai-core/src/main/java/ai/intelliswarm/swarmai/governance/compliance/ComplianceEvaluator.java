package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs an ordered list of {@link ComplianceGate}s against an output and
 * aggregates the result. Stops on the first BLOCKER if {@code stopOnBlocker}
 * is {@code true}; otherwise runs every gate and returns the full picture.
 *
 * <p>This is the entry-point a workflow's "deliver output" step should call.
 * If the result's {@link ComplianceReport#pass()} is false, the output is held
 * back, surfaced to a human via the existing approval-gate machinery, and the
 * findings are persisted into the audit sink.
 */
@PublicApi(since = "1.0.24")
public final class ComplianceEvaluator {

    private final List<ComplianceGate> gates;
    private final boolean stopOnBlocker;

    public ComplianceEvaluator(List<ComplianceGate> gates) {
        this(gates, false);
    }

    public ComplianceEvaluator(List<ComplianceGate> gates, boolean stopOnBlocker) {
        Objects.requireNonNull(gates, "gates must not be null");
        this.gates = List.copyOf(gates);
        this.stopOnBlocker = stopOnBlocker;
    }

    public List<ComplianceGate> gates() {
        return gates;
    }

    public ComplianceReport evaluate(ComplianceGate.Output output) {
        Objects.requireNonNull(output, "output must not be null");
        List<ComplianceFinding> all = new ArrayList<>();
        for (ComplianceGate gate : gates) {
            List<ComplianceFinding> findings = gate.evaluate(output);
            if (findings != null && !findings.isEmpty()) {
                all.addAll(findings);
                if (stopOnBlocker && findings.stream()
                        .anyMatch(f -> f.severity() == ComplianceFinding.Severity.BLOCKER)) {
                    break;
                }
            }
        }
        return ComplianceReport.ofFindings(all);
    }
}
