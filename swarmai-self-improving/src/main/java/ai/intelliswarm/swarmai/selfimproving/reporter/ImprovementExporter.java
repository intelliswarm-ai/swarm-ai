package ai.intelliswarm.swarmai.selfimproving.reporter;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports accumulated improvements as a portable contribution file
 * for air-gapped / firewalled enterprise environments.
 *
 * When automatic reporting (GitHub PRs, telemetry) isn't possible,
 * the framework produces a self-contained export file that ops teams
 * can manually submit to the SwarmAI project.
 *
 * The export file:
 * - Contains ONLY generic, anonymized improvement data (no workflow content)
 * - Is a single JSON file that can be emailed, uploaded, or attached to a GitHub issue
 * - Includes a contribution URL and step-by-step instructions
 * - Is human-readable so security teams can audit before sending
 *
 * Submission channels:
 * 1. GitHub issue: paste the file content at github.com/intelliswarm-ai/swarm-ai/issues/new
 * 2. Email: send to contributions@intelliswarm.ai
 * 3. Web form: upload at intelliswarm.ai/contribute
 * 4. CLI: `curl -X POST https://api.intelliswarm.ai/api/v1/contribute -d @export.json`
 */
public class ImprovementExporter {

    private static final Logger log = LoggerFactory.getLogger(ImprovementExporter.class);

    private static final String CONTRIBUTION_URL = "https://github.com/intelliswarm-ai/swarm-ai/issues/new?template=self-improvement-contribution.md";
    private static final String EMAIL = "contributions@intelliswarm.ai";
    private static final String WEB_FORM = "https://intelliswarm.ai/contribute";

    private final ObjectMapper objectMapper;
    private final ImprovementAggregator aggregator;

    public ImprovementExporter(ImprovementAggregator aggregator) {
        this.aggregator = aggregator;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Export all pending improvements as a portable contribution file.
     *
     * @param outputPath where to write the export file
     * @return the export summary
     */
    public ExportResult export(Path outputPath) throws IOException {
        ImprovementAggregator.ReleaseIntelligence intelligence = aggregator.aggregateForRelease();

        List<ImprovementProposal> allProposals = new ArrayList<>();
        allProposals.addAll(intelligence.tier1Automatic());
        allProposals.addAll(intelligence.tier2Review());
        allProposals.addAll(intelligence.tier3Proposals());

        if (allProposals.isEmpty()) {
            log.info("No improvements to export");
            return new ExportResult(0, null, "No improvements pending");
        }

        ContributionFile contribution = buildContributionFile(allProposals, intelligence);

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writeValue(outputPath.toFile(), contribution);

        log.info("Exported {} improvements to {}", allProposals.size(), outputPath);
        logSubmissionInstructions(outputPath, allProposals.size());

        return new ExportResult(allProposals.size(), outputPath, "Export successful");
    }

    /**
     * Export to default location: ./swarmai-improvements-{date}.json
     */
    public ExportResult exportToDefault() throws IOException {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path path = Path.of("swarmai-improvements-" + date + ".json");
        return export(path);
    }

    /**
     * Get a human-readable summary of pending improvements — suitable for
     * display in admin dashboards, health checks, or log output.
     */
    public PendingSummary getPendingSummary() {
        ImprovementAggregator.ReleaseIntelligence intelligence = aggregator.aggregateForRelease();

        int total = intelligence.tier1Automatic().size()
                + intelligence.tier2Review().size()
                + intelligence.tier3Proposals().size();

        long estimatedTokenSavings = intelligence.tier1Automatic().stream()
                .filter(p -> p.origin() != null)
                .mapToLong(p -> p.origin().tokenSavings())
                .sum();

        Map<String, Integer> byCategory = new LinkedHashMap<>();
        for (ImprovementProposal p : intelligence.tier1Automatic()) {
            byCategory.merge(p.rule().category().name(), 1, Integer::sum);
        }
        for (ImprovementProposal p : intelligence.tier2Review()) {
            byCategory.merge(p.rule().category().name(), 1, Integer::sum);
        }

        return new PendingSummary(
                total,
                intelligence.tier1Automatic().size(),
                intelligence.tier2Review().size(),
                intelligence.tier3Proposals().size(),
                estimatedTokenSavings,
                byCategory,
                intelligence.communityInvestment()
        );
    }

    // --- Internal ---

    private ContributionFile buildContributionFile(List<ImprovementProposal> proposals,
                                                    ImprovementAggregator.ReleaseIntelligence intelligence) {
        List<AnonymizedContribution> contributions = proposals.stream()
                .map(this::anonymize)
                .toList();

        return new ContributionFile(
                "SwarmAI Self-Improvement Contribution",
                "1.0",
                new ContributionInstructions(),
                intelligence.communityInvestment(),
                contributions,
                Instant.now()
        );
    }

    private AnonymizedContribution anonymize(ImprovementProposal proposal) {
        return new AnonymizedContribution(
                proposal.rule().category().name(),
                proposal.tier().name(),
                proposal.rule().condition(),
                proposal.rule().confidence(),
                proposal.rule().recommendation(),
                proposal.rule().recommendedValue(),
                proposal.rule().crossValidation() != null && proposal.rule().crossValidation().passed(),
                proposal.rule().supportingObservations().size(),
                proposal.origin() != null ? proposal.origin().tokenSavings() : 0,
                proposal.origin() != null ? proposal.origin().occurrenceCount() : 0
        );
    }

    private void logSubmissionInstructions(Path exportPath, int count) {
        log.info("""

                ╔══════════════════════════════════════════════════════════════╗
                ║  SwarmAI: {} improvements ready to contribute back          ║
                ╠══════════════════════════════════════════════════════════════╣
                ║                                                              ║
                ║  Your workflows discovered {} framework improvements.        ║
                ║  These will benefit ALL SwarmAI users in the next release.    ║
                ║                                                              ║
                ║  Export file: {}
                ║                                                              ║
                ║  To contribute (choose one):                                 ║
                ║                                                              ║
                ║  1. GitHub Issue (recommended):                               ║
                ║     {}
                ║     Attach or paste the export file content.                 ║
                ║                                                              ║
                ║  2. Email:                                                    ║
                ║     Send the file to {}                          ║
                ║                                                              ║
                ║  3. Web form:                                                 ║
                ║     Upload at {}                     ║
                ║                                                              ║
                ║  The file contains ONLY anonymized structural patterns.       ║
                ║  No workflow content, user data, or API keys are included.    ║
                ║  Your security team can audit it before sending.              ║
                ║                                                              ║
                ╚══════════════════════════════════════════════════════════════╝
                """, count, count, exportPath, CONTRIBUTION_URL, EMAIL, WEB_FORM);
    }

    // --- Records ---

    public record ExportResult(int improvementCount, Path exportPath, String message) {}

    public record PendingSummary(
            int totalPending,
            int tier1Count,
            int tier2Count,
            int tier3Count,
            long estimatedTokenSavings,
            Map<String, Integer> byCategory,
            ImprovementAggregator.CommunityInvestmentLedger.Snapshot communityInvestment
    ) {
        public String toOpsMessage() {
            if (totalPending == 0) return "No pending improvements.";
            return String.format(java.util.Locale.US, """
                    SwarmAI Self-Improvement: %d improvements discovered
                    ├── %d ready to auto-apply (Tier 1)
                    ├── %d ready for review (Tier 2)
                    └── %d architecture proposals (Tier 3)
                    Estimated token savings if contributed: %,d tokens/run

                    Run the export to contribute back:
                      POST /actuator/self-improving/export
                      or programmatically: improvementExporter.exportToDefault()
                    """, totalPending, tier1Count, tier2Count, tier3Count, estimatedTokenSavings);
        }
    }

    public record ContributionFile(
            String title,
            String formatVersion,
            ContributionInstructions instructions,
            ImprovementAggregator.CommunityInvestmentLedger.Snapshot communityStats,
            List<AnonymizedContribution> contributions,
            Instant exportedAt
    ) {}

    public record ContributionInstructions(
            String description,
            String githubUrl,
            String email,
            String webForm,
            String privacyNote
    ) {
        public ContributionInstructions() {
            this(
                    "This file contains anonymized framework improvements discovered by the SwarmAI " +
                            "self-improvement engine. Contributing this back helps all SwarmAI users.",
                    CONTRIBUTION_URL,
                    EMAIL,
                    WEB_FORM,
                    "This file contains ONLY structural workflow patterns and generic improvement rules. " +
                            "No workflow content, task descriptions, agent outputs, user data, tenant IDs, " +
                            "or API keys are included. Your security team can audit the file before sending."
            );
        }
    }

    public record AnonymizedContribution(
            String category,
            String tier,
            Map<String, Object> condition,
            double confidence,
            String recommendation,
            Object recommendedValue,
            boolean crossValidated,
            int supportingObservations,
            long estimatedTokenSavings,
            int occurrenceCount
    ) {}
}
