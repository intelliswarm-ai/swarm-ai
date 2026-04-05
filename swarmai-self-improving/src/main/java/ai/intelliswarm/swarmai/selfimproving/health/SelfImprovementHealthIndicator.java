package ai.intelliswarm.swarmai.selfimproving.health;

import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter.PendingSummary;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Spring Boot Actuator health indicator that surfaces pending improvements
 * in the /actuator/health endpoint.
 *
 * When improvements are pending and haven't been reported back, the health
 * status shows as "UP" with details encouraging the ops team to export.
 *
 * This is the gentle nudge for firewalled environments where automatic
 * reporting isn't possible:
 *
 * GET /actuator/health
 * {
 *   "status": "UP",
 *   "components": {
 *     "selfImprovement": {
 *       "status": "UP",
 *       "details": {
 *         "pendingImprovements": 47,
 *         "tier1Ready": 12,
 *         "tier2Ready": 28,
 *         "tier3Proposals": 7,
 *         "estimatedTokenSavings": 450000,
 *         "message": "47 improvements discovered. Export and contribute back to benefit all users.",
 *         "exportEndpoint": "POST /actuator/self-improving/export",
 *         "reportingStatus": "OFFLINE — automatic reporting not configured"
 *       }
 *     }
 *   }
 * }
 */
public class SelfImprovementHealthIndicator implements HealthIndicator {

    private final ImprovementExporter exporter;
    private final boolean autoReportingEnabled;

    public SelfImprovementHealthIndicator(ImprovementExporter exporter,
                                           boolean autoReportingEnabled) {
        this.exporter = exporter;
        this.autoReportingEnabled = autoReportingEnabled;
    }

    @Override
    public Health health() {
        PendingSummary summary = exporter.getPendingSummary();

        Health.Builder builder = Health.up();

        builder.withDetail("pendingImprovements", summary.totalPending());
        builder.withDetail("tier1Ready", summary.tier1Count());
        builder.withDetail("tier2Ready", summary.tier2Count());
        builder.withDetail("tier3Proposals", summary.tier3Count());
        builder.withDetail("estimatedTokenSavings", summary.estimatedTokenSavings());

        if (autoReportingEnabled) {
            builder.withDetail("reportingStatus", "ONLINE — improvements reported automatically");
        } else if (summary.totalPending() > 0) {
            builder.withDetail("reportingStatus",
                    "OFFLINE — automatic reporting not configured");
            builder.withDetail("message",
                    "%d improvements discovered. Export and contribute back to benefit all SwarmAI users."
                            .formatted(summary.totalPending()));
            builder.withDetail("exportEndpoint", "POST /actuator/self-improving/export");
            builder.withDetail("howToContribute",
                    "Run: curl -X POST http://localhost:8080/actuator/self-improving/export " +
                            "— then submit the file at https://github.com/intelliswarm-ai/swarm-ai/issues/new");
        } else {
            builder.withDetail("reportingStatus", "OFFLINE — no improvements pending");
        }

        // Community stats
        if (summary.communityInvestment() != null) {
            builder.withDetail("communityWorkflowRuns", summary.communityInvestment().totalWorkflowRuns());
            builder.withDetail("communityImprovementsShipped", summary.communityInvestment().totalImprovementsShipped());
            builder.withDetail("communityROI", "%.1fx".formatted(summary.communityInvestment().roi()));
        }

        if (!summary.byCategory().isEmpty()) {
            builder.withDetail("improvementsByCategory", summary.byCategory());
        }

        return builder.build();
    }
}
