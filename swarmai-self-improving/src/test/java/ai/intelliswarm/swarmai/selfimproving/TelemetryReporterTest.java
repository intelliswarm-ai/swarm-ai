package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter.TelemetryReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryReporterTest {

    private TelemetryReporter reporter;

    @BeforeEach
    void setUp() {
        // Disabled endpoint — tests report building, not HTTP
        reporter = new TelemetryReporter("https://api.intelliswarm.ai", false);
    }

    @Test
    void shouldBuildAnonymizedReport() {
        ImprovementResult result = buildResult();
        TelemetryReport report = reporter.buildReport(result);

        assertNotNull(report);
        assertNotNull(report.installationId());
        assertEquals("1.0.0-SNAPSHOT", report.frameworkVersion());
        assertFalse(report.proposals().isEmpty());
    }

    @Test
    void shouldAnonymizeSwarmId() {
        ImprovementResult result = buildResult();
        TelemetryReport report = reporter.buildReport(result);

        // Swarm ID should be hashed, not raw
        assertNotEquals("test-swarm", report.anonymizedSwarmId());
        assertEquals(12, report.anonymizedSwarmId().length()); // truncated SHA-256
    }

    @Test
    void shouldNotIncludeWorkflowContent() {
        ImprovementResult result = buildResult();
        TelemetryReport report = reporter.buildReport(result);

        String json = report.toString();
        // Should not contain any task descriptions or agent outputs
        assertFalse(json.contains("Research the market"));
        assertFalse(json.contains("Find competitors"));
    }

    @Test
    void shouldIncludeStructuralConditions() {
        ImprovementResult result = buildResult();
        TelemetryReport report = reporter.buildReport(result);

        var proposal = report.proposals().get(0);
        assertNotNull(proposal.condition());
        assertTrue(proposal.condition().containsKey("max_depth")); // structural, not domain
    }

    @Test
    void shouldTrackObservationCounts() {
        ImprovementResult result = buildResult();
        TelemetryReport report = reporter.buildReport(result);

        assertEquals(5, report.observationCounts().get("total"));
    }

    @Test
    void shouldNotSendWhenDisabled() {
        TelemetryReporter disabled = new TelemetryReporter("https://api.intelliswarm.ai", false);
        // This should not throw or make HTTP calls
        disabled.report(buildResult());
    }

    private ImprovementResult buildResult() {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-1",
                SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Converged early", Map.of(), 0.7, Instant.now());

        GenericRule rule = new GenericRule("rule-1", RuleCategory.CONVERGENCE_DEFAULT,
                Map.of("max_depth", "<=3"),
                "Reduce maxIterations", 3, 0.85,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of()),
                Instant.now(), Instant.now());

        ImprovementProposal proposal = ImprovementProposal.builder()
                .tier(ImprovementTier.TIER_1_AUTOMATIC)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/convergence-defaults.json", "maxIterations", 5, 3,
                        "35% savings", Map.of()))
                .origin(new ImprovementProposal.Origin(
                        "SEQUENTIAL", "test", 5000, 0.0, 3, List.of()))
                .status(ImprovementProposal.ProposalStatus.VALIDATED)
                .build();

        return new ImprovementResult("test-swarm", 5, 1, 1, 0, 0, 2000, List.of(proposal), List.of());
    }
}
