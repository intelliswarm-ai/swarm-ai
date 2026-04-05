package ai.intelliswarm.swarmai.selfimproving;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.ValidationResult;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImprovementExporterTest {

    @Test
    void shouldExportWhenOutputPathHasNoParent() throws Exception {
        ImprovementAggregator aggregator = new ImprovementAggregator(new SelfImprovementConfig());
        aggregator.submit(buildProposal());

        ImprovementExporter exporter = new ImprovementExporter(aggregator);
        String fileName = "swarmai-export-test-" + System.nanoTime() + ".json";
        Path outputPath = Path.of(fileName);

        try {
            ImprovementExporter.ExportResult result = exporter.export(outputPath);

            assertEquals(1, result.improvementCount());
            assertEquals(outputPath, result.exportPath());
            assertTrue(Files.exists(outputPath));
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    private ImprovementProposal buildProposal() {
        WorkflowShape shape = new WorkflowShape(3, 2, true, false, false,
                Set.of("WEB"), "SELF_IMPROVING", 2, 1.0, true, false);
        SpecificObservation obs = new SpecificObservation("obs-export-1",
                SpecificObservation.ObservationType.CONVERGENCE_PATTERN,
                shape, "Converged at iteration 2", Map.of("converged_at", 2), 0.7, Instant.now());

        GenericRule rule = new GenericRule("rule-export-1", RuleCategory.CONVERGENCE_DEFAULT,
                Map.of("max_depth", "<=3"),
                "Reduce maxIterations for shallow workflows", 3, 0.90,
                List.of(obs, obs, obs),
                ValidationResult.passed(5, 4, List.of("Matched 4/5")),
                Instant.now(), Instant.now());

        return ImprovementProposal.builder()
                .tier(ImprovementTier.TIER_1_AUTOMATIC)
                .rule(rule)
                .improvement(new ImprovementProposal.Improvement(
                        "intelligence/convergence-defaults.json", "maxIterations", 5, 3,
                        "35% token reduction for shallow workflows", Map.of()))
                .origin(new ImprovementProposal.Origin(
                        "SELF_IMPROVING", "converged at iteration 2 across 15 runs",
                        33000, 0.04, 15, List.of("s1", "s2")))
                .status(ImprovementProposal.ProposalStatus.VALIDATED)
                .build();
    }
}
