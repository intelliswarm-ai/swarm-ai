package ai.intelliswarm.swarmai.selfimproving.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the self-improvement engine.
 *
 * 10% of every workflow's token budget is reserved for framework-level improvement.
 * This is not per-workflow optimization — it produces artifacts that ship in the
 * next release and benefit all users on upgrade.
 */
@ConfigurationProperties(prefix = "swarmai.self-improving")
public class SelfImprovementConfig {

    private boolean enabled = false;

    /** Percentage of total token budget reserved for self-improvement (default: 10%) */
    private double reservePercent = 0.10;

    /** Minimum ROI threshold — skip improvement if expected return < this */
    private double minimumRoiThreshold = 0.05;

    /** Maximum percentage of total budget for speculative/exploratory improvement */
    private double maxSpeculativeBudget = 0.01;

    /** Require execution evidence before generating/promoting skills */
    private boolean requireEvidenceForSkills = true;

    /** Number of times a skill must be reused before permanent promotion */
    private int promotionThreshold = 2;

    /** Remove skill after this many consecutive failed uses */
    private int demotionAfterFailures = 3;

    /** Minimum confidence for Tier 1 (automatic) improvements */
    private double tier1MinConfidence = 0.85;

    /** Minimum confidence for Tier 2 (PR review) improvements */
    private double tier2MinConfidence = 0.70;

    /** Minimum supporting observations from different workflow types */
    private int minCrossWorkflowEvidence = 2;

    /** Minimum total observations to consider a rule ready */
    private int minObservations = 3;

    /** Days after which an unvalidated proposal is marked stale */
    private int staleProposalDays = 30;

    /** Path to intelligence resource directory */
    private String intelligencePath = "classpath:intelligence/";

    /** Path to store improvement proposals */
    private String proposalPath = "proposals/";

    // --- GitHub reporting ---

    /** GitHub repository owner (e.g., "intelliswarm-ai") */
    private String githubOwner = "intelliswarm-ai";

    /** GitHub repository name (e.g., "swarm-ai") */
    private String githubRepo = "swarm-ai";

    /** GitHub API token for creating branches and PRs */
    private String githubToken;

    /** Base branch for PRs (default: "main") */
    private String githubBaseBranch = "main";

    // --- Telemetry reporting ---

    /**
     * Enable anonymized telemetry reporting to central endpoint.
     * Default: false (opt-in). Users must explicitly enable this — the framework
     * never phones home without consent.
     */
    private boolean telemetryEnabled = false;

    /**
     * When the telemetry POST fires. Two modes:
     * <ul>
     *   <li>{@link TelemetryMode#PER_WORKFLOW} — POST after each successful
     *       workflow completion. Right for short-lived JVMs (examples, CI jobs,
     *       batch scripts) where every run should contribute immediately.</li>
     *   <li>{@link TelemetryMode#CONTINUOUS} — POST on a cron schedule
     *       (default every 6h). Right for long-running services where
     *       per-workflow POSTs would flood the endpoint.</li>
     * </ul>
     * Default: {@code CONTINUOUS} — the safer choice for production services.
     * Framework users running examples should override to {@code PER_WORKFLOW}.
     */
    private TelemetryMode telemetryMode = TelemetryMode.CONTINUOUS;

    /** Central endpoint for telemetry aggregation */
    private String telemetryEndpoint = "https://api.intelliswarm.ai";

    /**
     * Cron expression for the CONTINUOUS-mode push. Default: every 6 hours
     * (00:00, 06:00, 12:00, 18:00 in the configured timezone). Ignored in
     * {@link TelemetryMode#PER_WORKFLOW} mode.
     */
    private String telemetryReportCron = "0 0 */6 * * *";

    /** Timezone for the telemetry cron (IANA name). Default: UTC. */
    private String telemetryReportZone = "UTC";

    /**
     * Flush any un-reported rollup on JVM shutdown. Useful in both modes as a
     * belt-and-suspenders safety net — ensures a graceful SIGTERM still
     * contributes the most recent activity before the process exits.
     */
    private boolean telemetryFlushOnShutdown = true;

    public enum TelemetryMode {
        PER_WORKFLOW,
        CONTINUOUS
    }

    // --- Priority budget allocation within the 10% ---

    /** Budget allocation for Priority 1: Fix failures */
    private double priority1FixFailuresPercent = 0.30;

    /** Budget allocation for Priority 2: Optimize expensive tasks */
    private double priority2OptimizePercent = 0.25;

    /** Budget allocation for Priority 3: Promote successful patterns */
    private double priority3PromotePercent = 0.20;

    /** Budget allocation for Priority 4: Detect structural gaps */
    private double priority4DetectGapsPercent = 0.15;

    /** Budget allocation for Priority 5: Speculative exploration */
    private double priority5ExplorePercent = 0.10;


    // Getters and setters

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getReservePercent() { return reservePercent; }
    public void setReservePercent(double reservePercent) { this.reservePercent = reservePercent; }

    public double getMinimumRoiThreshold() { return minimumRoiThreshold; }
    public void setMinimumRoiThreshold(double minimumRoiThreshold) { this.minimumRoiThreshold = minimumRoiThreshold; }

    public double getMaxSpeculativeBudget() { return maxSpeculativeBudget; }
    public void setMaxSpeculativeBudget(double maxSpeculativeBudget) { this.maxSpeculativeBudget = maxSpeculativeBudget; }

    public boolean isRequireEvidenceForSkills() { return requireEvidenceForSkills; }
    public void setRequireEvidenceForSkills(boolean requireEvidenceForSkills) { this.requireEvidenceForSkills = requireEvidenceForSkills; }

    public int getPromotionThreshold() { return promotionThreshold; }
    public void setPromotionThreshold(int promotionThreshold) { this.promotionThreshold = promotionThreshold; }

    public int getDemotionAfterFailures() { return demotionAfterFailures; }
    public void setDemotionAfterFailures(int demotionAfterFailures) { this.demotionAfterFailures = demotionAfterFailures; }

    public double getTier1MinConfidence() { return tier1MinConfidence; }
    public void setTier1MinConfidence(double tier1MinConfidence) { this.tier1MinConfidence = tier1MinConfidence; }

    public double getTier2MinConfidence() { return tier2MinConfidence; }
    public void setTier2MinConfidence(double tier2MinConfidence) { this.tier2MinConfidence = tier2MinConfidence; }

    public int getMinCrossWorkflowEvidence() { return minCrossWorkflowEvidence; }
    public void setMinCrossWorkflowEvidence(int minCrossWorkflowEvidence) { this.minCrossWorkflowEvidence = minCrossWorkflowEvidence; }

    public int getMinObservations() { return minObservations; }
    public void setMinObservations(int minObservations) { this.minObservations = minObservations; }

    public int getStaleProposalDays() { return staleProposalDays; }
    public void setStaleProposalDays(int staleProposalDays) { this.staleProposalDays = staleProposalDays; }

    public String getIntelligencePath() { return intelligencePath; }
    public void setIntelligencePath(String intelligencePath) { this.intelligencePath = intelligencePath; }

    public String getProposalPath() { return proposalPath; }
    public void setProposalPath(String proposalPath) { this.proposalPath = proposalPath; }

    public double getPriority1FixFailuresPercent() { return priority1FixFailuresPercent; }
    public void setPriority1FixFailuresPercent(double v) { this.priority1FixFailuresPercent = v; }

    public double getPriority2OptimizePercent() { return priority2OptimizePercent; }
    public void setPriority2OptimizePercent(double v) { this.priority2OptimizePercent = v; }

    public double getPriority3PromotePercent() { return priority3PromotePercent; }
    public void setPriority3PromotePercent(double v) { this.priority3PromotePercent = v; }

    public double getPriority4DetectGapsPercent() { return priority4DetectGapsPercent; }
    public void setPriority4DetectGapsPercent(double v) { this.priority4DetectGapsPercent = v; }

    public double getPriority5ExplorePercent() { return priority5ExplorePercent; }
    public void setPriority5ExplorePercent(double v) { this.priority5ExplorePercent = v; }

    public String getGithubOwner() { return githubOwner; }
    public void setGithubOwner(String githubOwner) { this.githubOwner = githubOwner; }

    public String getGithubRepo() { return githubRepo; }
    public void setGithubRepo(String githubRepo) { this.githubRepo = githubRepo; }

    public String getGithubToken() { return githubToken; }
    public void setGithubToken(String githubToken) { this.githubToken = githubToken; }

    public String getGithubBaseBranch() { return githubBaseBranch; }
    public void setGithubBaseBranch(String githubBaseBranch) { this.githubBaseBranch = githubBaseBranch; }

    public boolean isTelemetryEnabled() { return telemetryEnabled; }
    public void setTelemetryEnabled(boolean telemetryEnabled) { this.telemetryEnabled = telemetryEnabled; }

    public TelemetryMode getTelemetryMode() { return telemetryMode; }
    public void setTelemetryMode(TelemetryMode telemetryMode) { this.telemetryMode = telemetryMode; }

    public String getTelemetryEndpoint() { return telemetryEndpoint; }
    public void setTelemetryEndpoint(String telemetryEndpoint) { this.telemetryEndpoint = telemetryEndpoint; }

    public String getTelemetryReportCron() { return telemetryReportCron; }
    public void setTelemetryReportCron(String telemetryReportCron) { this.telemetryReportCron = telemetryReportCron; }

    public String getTelemetryReportZone() { return telemetryReportZone; }
    public void setTelemetryReportZone(String telemetryReportZone) { this.telemetryReportZone = telemetryReportZone; }

    public boolean isTelemetryFlushOnShutdown() { return telemetryFlushOnShutdown; }
    public void setTelemetryFlushOnShutdown(boolean v) { this.telemetryFlushOnShutdown = v; }
}
