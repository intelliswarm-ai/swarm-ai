package ai.intelliswarm.swarmai.governance.compliance;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Map;
import java.util.Objects;

/**
 * One issue surfaced by a {@link ComplianceGate}. The {@code code} is a stable
 * machine identifier; downstream consumers route on it (e.g. an audit sink
 * indexes findings by code; a UI groups failures by severity).
 *
 * @param code        stable identifier (e.g. {@code "MISSING_CITATION"})
 * @param severity    blocker vs warning vs info
 * @param message     human-readable explanation
 * @param attributes  structured context (sentence, position, document id, etc.)
 */
@PublicApi(since = "1.0.24")
public record ComplianceFinding(
        String code,
        Severity severity,
        String message,
        Map<String, Object> attributes) {

    public ComplianceFinding {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(message, "message must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static ComplianceFinding blocker(String code, String message) {
        return new ComplianceFinding(code, Severity.BLOCKER, message, Map.of());
    }

    public static ComplianceFinding blocker(String code, String message, Map<String, Object> attrs) {
        return new ComplianceFinding(code, Severity.BLOCKER, message, attrs);
    }

    public static ComplianceFinding warn(String code, String message) {
        return new ComplianceFinding(code, Severity.WARNING, message, Map.of());
    }

    public static ComplianceFinding info(String code, String message) {
        return new ComplianceFinding(code, Severity.INFO, message, Map.of());
    }

    @PublicApi(since = "1.0.24")
    public enum Severity {
        /** Output is unfit to ship — must be remediated. */
        BLOCKER,
        /** Worth flagging to the user; doesn't block delivery. */
        WARNING,
        /** Informational; safe to ship as-is. */
        INFO
    }
}
