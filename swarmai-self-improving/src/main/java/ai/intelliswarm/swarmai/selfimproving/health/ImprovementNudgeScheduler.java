package ai.intelliswarm.swarmai.selfimproving.health;

import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter.PendingSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically logs a reminder when improvements are pending export.
 *
 * For firewalled environments where automatic reporting isn't possible,
 * this scheduler ensures the ops team sees the improvements exist.
 *
 * The nudge is:
 * - Respectful: logs at INFO level, not WARN
 * - Escalating: frequency increases with pending count (not annoyingly)
 * - Actionable: includes the exact command to export
 * - Grateful: frames it as "your workflows helped" not "you need to do something"
 *
 * Nudge frequency:
 * - 1-10 pending: once per day
 * - 11-50 pending: twice per day
 * - 50+ pending: every 4 hours
 *
 * The nudge stops when:
 * - Auto-reporting is enabled (GitHub or telemetry)
 * - No improvements are pending
 * - The user exports or disables nudging
 */
public class ImprovementNudgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ImprovementNudgeScheduler.class);

    private final ImprovementExporter exporter;
    private final boolean autoReportingEnabled;
    private final AtomicInteger nudgeCount = new AtomicInteger(0);

    public ImprovementNudgeScheduler(ImprovementExporter exporter, boolean autoReportingEnabled) {
        this.exporter = exporter;
        this.autoReportingEnabled = autoReportingEnabled;
    }

    /**
     * Check every 4 hours. Actual nudge frequency is controlled by the logic below.
     */
    @Scheduled(fixedRate = 4 * 60 * 60 * 1000, initialDelay = 60 * 60 * 1000) // first after 1 hour
    public void checkAndNudge() {
        if (autoReportingEnabled) return;

        PendingSummary summary = exporter.getPendingSummary();
        if (summary.totalPending() == 0) return;

        int count = nudgeCount.incrementAndGet();

        // Don't nudge more than once per appropriate interval
        if (summary.totalPending() <= 10 && count % 6 != 1) return;  // once/day
        if (summary.totalPending() <= 50 && count % 3 != 1) return;  // twice/day

        logNudge(summary, count);
    }

    /**
     * Also nudge at startup if there are pending improvements.
     */
    public void nudgeOnStartup() {
        if (autoReportingEnabled) return;

        PendingSummary summary = exporter.getPendingSummary();
        if (summary.totalPending() == 0) return;

        log.info("""

                ┌──────────────────────────────────────────────────────────────┐
                │  SwarmAI Self-Improvement: {} improvements ready             │
                │                                                              │
                │  Your workflows have discovered {} framework improvements    │
                │  that would benefit all SwarmAI users worldwide.             │
                │                                                              │
                │  Automatic reporting is not configured (firewalled?).         │
                │  To contribute these improvements:                           │
                │                                                              │
                │    POST /actuator/self-improving/export                       │
                │    or: improvementExporter.exportToDefault()                 │
                │                                                              │
                │  The export contains only anonymized patterns — no           │
                │  workflow content or user data. Safe for security review.     │
                └──────────────────────────────────────────────────────────────┘
                """, summary.totalPending(), summary.totalPending());
    }

    private void logNudge(PendingSummary summary, int nudgeNumber) {
        if (nudgeNumber <= 3) {
            // Friendly, grateful tone for first few nudges
            log.info("SwarmAI: Your workflows discovered {} improvements for the framework. " +
                            "Export with POST /actuator/self-improving/export to contribute back. " +
                            "Estimated savings for all users: {:,} tokens/run.",
                    summary.totalPending(), summary.estimatedTokenSavings());
        } else if (nudgeNumber <= 10) {
            // More concise for repeated nudges
            log.info("SwarmAI: {} pending improvements ({} auto, {} review). " +
                            "Export: POST /actuator/self-improving/export",
                    summary.totalPending(), summary.tier1Count(), summary.tier2Count());
        } else {
            // Minimal for long-running nudges
            log.info("SwarmAI: {} improvements pending export → /actuator/self-improving/export",
                    summary.totalPending());
        }
    }
}
