package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementReportingService;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementReportingService.ReportingOutcome;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ImprovementReportingServiceTest {

    private ImprovementReportingService service;

    @BeforeEach
    void setUp() {
        ImprovementAggregator aggregator = new ImprovementAggregator(new SelfImprovementConfig());
        TelemetryReporter telemetry = new TelemetryReporter("https://api.intelliswarm.ai", false);
        // No GitHub reporter — tests work without API token
        service = new ImprovementReportingService(aggregator, null, telemetry);
    }

    @Test
    void shouldReportWithoutGitHubReporter() {
        ImprovementResult result = buildResult();
        ReportingOutcome outcome = service.reportImprovements(result);

        assertNotNull(outcome);
        assertEquals("test-swarm", outcome.swarmId());
        assertTrue(outcome.prUrls().isEmpty()); // no GitHub reporter
    }

    @Test
    void shouldSendTelemetryWhenConfigured() {
        ImprovementAggregator aggregator = new ImprovementAggregator(new SelfImprovementConfig());
        TelemetryReporter telemetry = new TelemetryReporter("https://api.intelliswarm.ai", true);
        ImprovementReportingService withTelemetry = new ImprovementReportingService(aggregator, null, telemetry);

        ImprovementResult result = buildResult();
        ReportingOutcome outcome = withTelemetry.reportImprovements(result);

        assertTrue(outcome.telemetrySent());
    }

    @Test
    void shouldBufferProposalsForBatching() {
        assertEquals(0, service.getPendingProposalCount());
        // Without GitHub reporter, nothing gets buffered
        service.reportImprovements(buildResult());
        assertEquals(0, service.getPendingProposalCount());
    }

    @Test
    void shouldReturnCommunityMetrics() {
        var metrics = service.getCommunityMetrics();
        assertNotNull(metrics);
        assertEquals(0, metrics.totalWorkflowRuns());
    }

    private ImprovementResult buildResult() {
        WorkflowShape shape = new WorkflowShape(3, 2, false, false, false,
                Set.of("WEB"), "SEQUENTIAL", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-1",
                SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Test", Map.of(), 0.7, Instant.now());

        GenericRule rule = new GenericRule("rule-1", RuleCategory.CONVERGENCE_DEFAULT,
                Map.of("max_depth", "<=3"), "Test", 3, 0.85,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of()),
                Instant.now(), Instant.now());

        ImprovementProposal proposal = ImprovementProposal.builder()
                .tier(ImprovementTier.TIER_1_AUTOMATIC)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/convergence-defaults.json", "key", 5, 3, "test", Map.of()))
                .origin(new ImprovementProposal.Origin("SEQ", "test", 1000, 0.0, 3, List.of()))
                .status(ImprovementProposal.ProposalStatus.VALIDATED)
                .build();

        return new ImprovementResult("test-swarm", 5, 1, 1, 0, 0, 2000, List.of(proposal), List.of());
    }
}
