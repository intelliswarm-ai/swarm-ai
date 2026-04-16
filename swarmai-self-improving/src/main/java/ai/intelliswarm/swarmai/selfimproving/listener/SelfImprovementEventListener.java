package ai.intelliswarm.swarmai.selfimproving.listener;

import ai.intelliswarm.swarmai.event.SwarmCompletedEvent;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig.TelemetryMode;
import ai.intelliswarm.swarmai.selfimproving.evolution.EvolutionEngine;
import ai.intelliswarm.swarmai.selfimproving.ledger.DailyTelemetryScheduler;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace;
import ai.intelliswarm.swarmai.selfimproving.model.ImprovementProposal;
import ai.intelliswarm.swarmai.selfimproving.model.ImprovementTier;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation;
import ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

/**
 * Auto-trigger for the self-improvement phase.
 *
 * <p>Subscribes to {@link SwarmCompletedEvent} and runs {@link ImprovementPhase}
 * on every successful workflow — including examples, tests, and production runs.
 * Failures are logged but never propagated; a broken improvement pass must never
 * mask a successful workflow.
 *
 * <p>This listener populates the local aggregator / ledger only. It does
 * <em>not</em> fire telemetry to the central endpoint — that is the daily
 * scheduler's responsibility. Separating the two means a deployment that runs
 * 10,000 workflows a day sends one daily rollup, not 10,000 per-run reports.
 */
public class SelfImprovementEventListener {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementEventListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ImprovementPhase improvementPhase;
    private final LedgerStore ledgerStore;
    private final SelfImprovementConfig config;
    private final EvolutionEngine evolutionEngine;
    private final DailyTelemetryScheduler telemetryScheduler;

    /**
     * @param evolutionEngine    processes INTERNAL observations as runtime self-evolution
     * @param telemetryScheduler nullable — only present when telemetry is enabled
     *                           (opt-in). When null, PER_WORKFLOW mode falls back
     *                           to no-op and the listener just updates the ledger.
     */
    public SelfImprovementEventListener(ImprovementPhase improvementPhase,
                                         LedgerStore ledgerStore,
                                         SelfImprovementConfig config,
                                         EvolutionEngine evolutionEngine,
                                         DailyTelemetryScheduler telemetryScheduler) {
        this.improvementPhase = improvementPhase;
        this.ledgerStore = ledgerStore;
        this.config = config;
        this.evolutionEngine = evolutionEngine;
        this.telemetryScheduler = telemetryScheduler;
    }

    @Async
    @EventListener
    public void onSwarmCompleted(SwarmCompletedEvent event) {
        try {
            ExecutionTrace trace = ExecutionTrace.builder()
                    .fromSwarmOutput(event.getOutput())
                    .build();

            ImprovementResult result = improvementPhase.execute(
                    trace,
                    event.getBudgetTracker(),
                    event.getSwarmId()
            );

            if (result != null) {
                persistObservations(result);
                routeObservations(result, trace);
                recordToLedger(result);
                logSummary(result);
                maybeReportTelemetry();
                maybeContributeProposals(result);
            }
        } catch (Exception e) {
            // Never let a broken improvement pass mask a successful workflow.
            log.warn("Self-improvement pass failed for swarm {} (non-fatal): {}",
                    event.getSwarmId(), e.getMessage());
        }
    }

    /**
     * Persist raw observations to the ledger store so they survive JVM restarts.
     * This enables cross-JVM pattern extraction in regression-run scenarios
     * where each workflow runs in a separate JVM.
     */
    private void persistObservations(ImprovementResult result) {
        if (result.observations() == null || result.observations().isEmpty()) return;
        for (SpecificObservation obs : result.observations()) {
            try {
                String evidenceJson = MAPPER.writeValueAsString(obs.evidence());
                ledgerStore.recordObservation(
                        result.swarmId(),
                        obs.type() != null ? obs.type().name() : "UNKNOWN",
                        obs.description(),
                        evidenceJson
                );
            } catch (Exception e) {
                log.debug("Failed to persist observation (non-fatal): {}", e.getMessage());
            }
        }
    }

    /**
     * In {@link TelemetryMode#PER_WORKFLOW} mode, push the rollup to the central
     * endpoint immediately after each successful workflow. In CONTINUOUS mode,
     * leave it to the scheduler's cron tick.
     */
    private void maybeReportTelemetry() {
        if (telemetryScheduler == null) return;
        if (!config.isTelemetryEnabled()) return;
        if (config.getTelemetryMode() != TelemetryMode.PER_WORKFLOW) return;
        try {
            telemetryScheduler.reportNow();
        } catch (Exception e) {
            log.debug("Per-workflow telemetry push failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Route observations to the correct pipeline based on their type.
     * INTERNAL observations → EvolutionEngine (runtime self-evolution)
     * EXTERNAL observations → proposal pipeline → intelliswarm.ai/contribute
     */
    private void routeObservations(ImprovementResult result, ExecutionTrace trace) {
        if (result.observations() == null || result.observations().isEmpty()) return;

        // Split observations by routing
        List<SpecificObservation> internal = result.observations().stream()
                .filter(obs -> obs.type() != null && obs.type().isInternal())
                .toList();
        List<SpecificObservation> external = result.observations().stream()
                .filter(obs -> obs.type() != null && obs.type().isExternal())
                .toList();

        // Route internal observations to the evolution engine
        if (!internal.isEmpty() && evolutionEngine != null) {
            List<SwarmEvolution> evolutions = evolutionEngine.evolve(
                    result.swarmId(), internal, trace.workflowShape());
            if (!evolutions.isEmpty()) {
                log.info("[{}] Self-evolution: {} runtime optimizations identified from {} internal observations",
                        result.swarmId(), evolutions.size(), internal.size());
            }
        }

        if (!external.isEmpty()) {
            log.debug("[{}] {} external observations will flow to proposal pipeline → intelliswarm.ai/contribute",
                    result.swarmId(), external.size());
        }
    }

    /**
     * Auto-contribute proposals to intelliswarm.ai/contribute when proposals are generated.
     * Only contributes proposals backed by EXTERNAL observations (framework gaps).
     * Uses the same endpoint as the CLI: POST /api/v1/contribute.
     * Only fires in PER_WORKFLOW mode with telemetry enabled and non-empty proposals.
     */
    private void maybeContributeProposals(ImprovementResult result) {
        if (!config.isTelemetryEnabled()) return;
        if (result.proposals() == null || result.proposals().isEmpty()) return;

        // Only contribute proposals backed by EXTERNAL observations.
        // INTERNAL observations are handled by the EvolutionEngine (self-evolution).
        List<ImprovementProposal> externalProposals = result.proposals().stream()
                .filter(p -> p.rule() != null
                        && p.rule().supportingObservations() != null
                        && p.rule().supportingObservations().stream()
                            .anyMatch(obs -> obs.type() != null && obs.type().isExternal()))
                .toList();

        if (externalProposals.isEmpty()) {
            log.debug("[{}] {} proposals generated but all are INTERNAL (self-evolution) — not contributing externally",
                    result.swarmId(), result.totalProposals());
            return;
        }

        try {
            String endpoint = config.getTelemetryEndpoint();
            if (endpoint == null || endpoint.isBlank()) return;

            Map<String, Object> payload = buildContributionPayload(result, externalProposals);
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/api/v1/contribute"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpClient.newHttpClient()
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200 || response.statusCode() == 201 || response.statusCode() == 202) {
                            log.info("Auto-contributed {} EXTERNAL proposals to {}/contribute (filtered from {} total)",
                                    externalProposals.size(), endpoint, result.totalProposals());
                        } else {
                            log.debug("Contribute endpoint returned {} (non-fatal)", response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        log.debug("Auto-contribute failed (non-fatal): {}", e.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            log.debug("Failed to build contribution payload (non-fatal): {}", e.getMessage());
        }
    }

    private Map<String, Object> buildContributionPayload(ImprovementResult result,
                                                          List<ImprovementProposal> externalProposals) {
        List<Map<String, Object>> improvements = new ArrayList<>();
        for (ImprovementProposal p : externalProposals) {
            Map<String, Object> improvement = new LinkedHashMap<>();
            improvement.put("category", p.rule() != null && p.rule().category() != null
                    ? p.rule().category().name() : "UNKNOWN");
            improvement.put("tier", p.tier() != null ? p.tier().name() : "TIER_3_PROPOSAL");
            improvement.put("confidence", p.rule() != null ? p.rule().confidence() : 0.0);
            improvement.put("crossValidated", p.rule() != null
                    && p.rule().crossValidation() != null
                    && p.rule().crossValidation().passed());
            improvement.put("supportingObservations",
                    p.rule() != null ? p.rule().supportingObservations().size() : 0);
            improvement.put("condition", Map.of(
                    "kind", "FRAMEWORK",
                    "theme", p.rule() != null ? p.rule().recommendation() : "",
                    "reportedByWorkflows", List.of(result.swarmId())
            ));
            improvement.put("recommendation", p.rule() != null ? p.rule().recommendation() : "");
            improvements.add(improvement);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportFormat", "swarmai-improvements");
        payload.put("formatVersion", "1.0");
        payload.put("title", "Auto-contributed from self-improvement pipeline");
        payload.put("exportedAt", Instant.now().toString());
        payload.put("communityStats", Map.of(
                "totalWorkflowsAnalyzed", 1,
                "totalImprovementsDiscovered", improvements.size(),
                "runDate", java.time.LocalDate.now().toString()
        ));
        payload.put("improvements", improvements);
        return payload;
    }

    private void recordToLedger(ImprovementResult result) {
        long antiPatterns = 0;
        long skillsPromoted = 0;
        Map<String, Long> categories = new HashMap<>();

        if (result.proposals() != null) {
            for (ImprovementProposal p : result.proposals()) {
                String category = (p.rule() != null && p.rule().category() != null)
                        ? p.rule().category().name()
                        : "UNKNOWN";
                categories.merge(category, 1L, Long::sum);
                if ("ANTI_PATTERN".equals(category)) antiPatterns++;
                if ("SKILL_PROMOTION".equals(category)) skillsPromoted++;
            }
        }

        LedgerStore.LedgerDelta delta = new LedgerStore.LedgerDelta(
                1L,
                result.tokensUsed(),
                result.totalObservations(),
                result.totalProposals(),
                result.tier1Shipped(),
                result.tier2Pending(),
                result.tier3Proposals(),
                antiPatterns,
                skillsPromoted
        );

        try {
            ledgerStore.recordRun(delta, categories);
        } catch (Exception e) {
            log.warn("Failed to persist ledger delta (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Print a compact, always-visible summary of what the improvement phase produced.
     * Emitted at INFO level so users of examples see the mechanism working without
     * needing to enable DEBUG logging.
     */
    private void logSummary(ImprovementResult result) {
        Map<String, Long> categoryCounts = new HashMap<>();
        if (result.proposals() != null) {
            for (ImprovementProposal p : result.proposals()) {
                String cat = (p.rule() != null && p.rule().category() != null)
                        ? p.rule().category().name()
                        : "UNKNOWN";
                categoryCounts.merge(cat, 1L, Long::sum);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append(System.lineSeparator());
        report.append("┌──────────────────────────────────────────────────────────────┐").append(System.lineSeparator());
        report.append("│  Self-Improvement Report — swarm ").append(pad(result.swarmId(), 28)).append("│").append(System.lineSeparator());
        report.append("├──────────────────────────────────────────────────────────────┤").append(System.lineSeparator());
        report.append(String.format("│  Tokens invested in improvement phase: %,15d tokens │", result.tokensUsed())).append(System.lineSeparator());
        report.append(String.format("│  Observations collected:               %,15d        │", result.totalObservations())).append(System.lineSeparator());
        report.append(String.format("│  Proposals generated:                  %,15d        │", result.totalProposals())).append(System.lineSeparator());
        report.append(String.format("│    Tier 1 (auto-eligible):            %,15d        │", result.tier1Shipped())).append(System.lineSeparator());
        report.append(String.format("│    Tier 2 (PR + review):              %,15d        │", result.tier2Pending())).append(System.lineSeparator());
        report.append(String.format("│    Tier 3 (architecture proposal):    %,15d        │", result.tier3Proposals())).append(System.lineSeparator());
        if (!categoryCounts.isEmpty()) {
            report.append("├──────────────────────────────────────────────────────────────┤").append(System.lineSeparator());
            report.append("│  By category:                                                │").append(System.lineSeparator());
            categoryCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> report.append(String.format("│    %-40s %,15d │%n", e.getKey(), e.getValue())));
        }
        // Raw observations — visible even when no rule graduates. This is the
        // honest signal: the collector found something, but cross-workflow
        // evidence wasn't sufficient for a rule yet.
        if (result.observations() != null && !result.observations().isEmpty()) {
            report.append("├──────────────────────────────────────────────────────────────┤").append(System.lineSeparator());
            report.append("│  Raw observations collected (pre-rule extraction):           │").append(System.lineSeparator());
            for (SpecificObservation obs : result.observations()) {
                String line = String.format("    %s: %s",
                        obs.type() != null ? obs.type().name() : "UNKNOWN",
                        obs.description() != null ? obs.description() : "(no description)");
                for (String chunk : wrap(line, 58)) {
                    report.append(String.format("│  %-58s  │%n", chunk));
                }
            }
        }
        report.append("└──────────────────────────────────────────────────────────────┘");

        log.info(report.toString());
    }

    private static String pad(String s, int width) {
        if (s == null) s = "(unknown)";
        if (s.length() >= width) return s.substring(0, width);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static java.util.List<String> wrap(String s, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (s == null) return lines;
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + width, s.length());
            lines.add(s.substring(start, end));
            start = end;
        }
        return lines;
    }
}
