package ai.intelliswarm.swarmai.selfimproving.reporter;

import ai.intelliswarm.swarmai.selfimproving.model.*;
import ai.intelliswarm.swarmai.selfimproving.model.GenericRule.RuleCategory;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reports anonymized improvement telemetry from distributed enterprise deployments
 * back to the central IntelliSwarm.ai endpoint.
 *
 * This is the mechanism for the "many independent flows of enterprise use"
 * to report back to the base (GitHub) how the framework can be further improved.
 *
 * PRIVACY GUARANTEES:
 * - No workflow content, task descriptions, or agent outputs are sent
 * - No user data, tenant IDs, or API keys are included
 * - Only structural patterns (WorkflowShape) and improvement proposals are reported
 * - Each report includes a random installation ID (not traceable to a user)
 * - Telemetry can be disabled entirely via configuration
 *
 * The central endpoint aggregates telemetry from all deployments and periodically
 * creates GitHub PRs with the collective intelligence.
 */
public class TelemetryReporter {

    private static final Logger log = LoggerFactory.getLogger(TelemetryReporter.class);

    private final String telemetryEndpoint;
    private final String installationId;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public TelemetryReporter(String telemetryEndpoint, boolean enabled) {
        this.telemetryEndpoint = telemetryEndpoint;
        this.enabled = enabled;
        this.installationId = UUID.randomUUID().toString(); // random per JVM, not traceable
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Report an improvement result back to the central endpoint.
     * The report is fully anonymized — only structural patterns and proposals.
     */
    public void report(ImprovementResult result) {
        if (!enabled) {
            log.debug("Telemetry disabled, skipping report");
            return;
        }

        try {
            TelemetryReport report = buildReport(result);
            String json = objectMapper.writeValueAsString(report);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(telemetryEndpoint + "/api/v1/self-improving/telemetry"))
                    .header("Content-Type", "application/json")
                    .header("X-SwarmAI-Installation", installationId)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200 || response.statusCode() == 202) {
                            log.debug("Telemetry report sent: {} observations, {} proposals",
                                    result.totalObservations(), result.totalProposals());
                        } else {
                            log.debug("Telemetry endpoint returned {}", response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        log.debug("Failed to send telemetry (non-blocking): {}", e.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            // Telemetry failures are never fatal
            log.debug("Failed to build telemetry report: {}", e.getMessage());
        }
    }

    /**
     * Build an anonymized telemetry report from an improvement result.
     */
    TelemetryReport buildReport(ImprovementResult result) {
        List<AnonymizedProposal> proposals = result.proposals().stream()
                .map(this::anonymize)
                .toList();

        Map<String, Integer> observationCounts = new LinkedHashMap<>();
        observationCounts.put("total", result.totalObservations());
        observationCounts.put("tier1_shipped", result.tier1Shipped());
        observationCounts.put("tier2_pending", result.tier2Pending());
        observationCounts.put("tier3_proposals", result.tier3Proposals());

        return new TelemetryReport(
                installationId,
                "1.0.0-SNAPSHOT", // framework version
                result.swarmId() != null ? hashId(result.swarmId()) : "anonymous",
                result.tokensUsed(),
                observationCounts,
                proposals,
                Instant.now()
        );
    }

    /**
     * Anonymize a proposal — strip all content, keep only structural patterns.
     */
    private AnonymizedProposal anonymize(ImprovementProposal proposal) {
        return new AnonymizedProposal(
                proposal.rule().category().name(),
                proposal.tier().name(),
                proposal.rule().condition(), // structural only, no domain terms
                proposal.rule().confidence(),
                proposal.rule().recommendation(),
                proposal.rule().crossValidation() != null && proposal.rule().crossValidation().passed(),
                proposal.rule().supportingObservations().size(),
                proposal.origin() != null ? proposal.origin().tokenSavings() : 0
        );
    }

    /**
     * One-way hash to anonymize IDs without losing deduplication ability.
     */
    private String hashId(String id) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 12);
        } catch (Exception e) {
            return "anon";
        }
    }

    /**
     * Anonymized telemetry report — safe to send to central endpoint.
     */
    public record TelemetryReport(
            String installationId,
            String frameworkVersion,
            String anonymizedSwarmId,
            long tokensUsedForImprovement,
            Map<String, Integer> observationCounts,
            List<AnonymizedProposal> proposals,
            Instant timestamp
    ) {}

    /**
     * Anonymized proposal — no workflow content, only structural pattern.
     */
    public record AnonymizedProposal(
            String category,
            String tier,
            Map<String, Object> condition,
            double confidence,
            String recommendation,
            boolean crossValidated,
            int supportingObservations,
            long estimatedTokenSavings
    ) {}
}
