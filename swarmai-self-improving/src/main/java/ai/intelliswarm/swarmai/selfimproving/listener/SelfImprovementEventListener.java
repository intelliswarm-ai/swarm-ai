package ai.intelliswarm.swarmai.selfimproving.listener;

import ai.intelliswarm.swarmai.event.SwarmCompletedEvent;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig;
import ai.intelliswarm.swarmai.selfimproving.config.SelfImprovementConfig.TelemetryMode;
import ai.intelliswarm.swarmai.selfimproving.ledger.DailyTelemetryScheduler;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.model.ExecutionTrace;
import ai.intelliswarm.swarmai.selfimproving.model.ImprovementProposal;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase.ImprovementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.util.HashMap;
import java.util.Map;

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

    private final ImprovementPhase improvementPhase;
    private final LedgerStore ledgerStore;
    private final SelfImprovementConfig config;
    private final DailyTelemetryScheduler telemetryScheduler;

    /**
     * @param telemetryScheduler nullable — only present when telemetry is enabled
     *                           (opt-in). When null, PER_WORKFLOW mode falls back
     *                           to no-op and the listener just updates the ledger.
     */
    public SelfImprovementEventListener(ImprovementPhase improvementPhase,
                                         LedgerStore ledgerStore,
                                         SelfImprovementConfig config,
                                         DailyTelemetryScheduler telemetryScheduler) {
        this.improvementPhase = improvementPhase;
        this.ledgerStore = ledgerStore;
        this.config = config;
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
                recordToLedger(result);
                logSummary(result);
                maybeReportTelemetry();
            }
        } catch (Exception e) {
            // Never let a broken improvement pass mask a successful workflow.
            log.warn("Self-improvement pass failed for swarm {} (non-fatal): {}",
                    event.getSwarmId(), e.getMessage());
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
