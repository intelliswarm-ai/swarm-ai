package ai.intelliswarm.swarmai.selfimproving.config;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.evolution.EvolutionEngine;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.ledger.DailyTelemetryScheduler;
import ai.intelliswarm.swarmai.selfimproving.ledger.JdbcLedgerStore;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.ledger.NoOpLedgerStore;
import ai.intelliswarm.swarmai.selfimproving.listener.SelfImprovementEventListener;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.health.ImprovementNudgeScheduler;
import ai.intelliswarm.swarmai.selfimproving.health.SelfImprovementHealthIndicator;
import ai.intelliswarm.swarmai.selfimproving.reporter.GitHubImprovementReporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementExporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementReportingService;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configuration for the SwarmAI Self-Improvement engine.
 *
 * Activates when swarmai.self-improving.enabled=true.
 * Reserves 10% of every workflow's token budget for framework-level improvement.
 *
 * The improvements produced are GENERIC — they benefit all users on upgrade,
 * not just the specific workflow that generated them.
 */
@Configuration
@EnableConfigurationProperties(SelfImprovementConfig.class)
@EnableScheduling
@EnableAsync
@AutoConfigureAfter(JdbcTemplateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "swarmai.self-improving", name = "enabled", havingValue = "true")
public class SelfImprovementAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementAutoConfiguration.class);

    // Bean names prefixed with "selfImprovement" to avoid collision with downstream
    // apps that define their own @Components with the same simple class name
    // (e.g. examples' judge/ImprovementAggregator).

    @Bean
    @ConditionalOnMissingBean
    public ImprovementCollector selfImprovementCollector() {
        log.info("SwarmAI Self-Improvement: ImprovementCollector initialized");
        return new ImprovementCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatternExtractor selfImprovementPatternExtractor(SelfImprovementConfig config,
                                                             LedgerStore ledgerStore) {
        log.info("SwarmAI Self-Improvement: PatternExtractor initialized (persistent observation store: {})",
                ledgerStore.getClass().getSimpleName());
        return new PatternExtractor(config, ledgerStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementClassifier selfImprovementClassifier(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: ImprovementClassifier initialized");
        return new ImprovementClassifier(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementAggregator selfImprovementAggregator(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: ImprovementAggregator initialized — " +
                "community investment tracking enabled");
        return new ImprovementAggregator(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementPhase selfImprovementPhase(SelfImprovementConfig config,
                                              ImprovementCollector collector,
                                              PatternExtractor extractor,
                                              ImprovementClassifier classifier,
                                              ImprovementAggregator aggregator) {
        log.info("SwarmAI Self-Improvement: ImprovementPhase initialized — " +
                "{}% of token budget reserved for framework improvement",
                config.getReservePercent() * 100);
        return new ImprovementPhase(config, collector, extractor, classifier, aggregator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public LedgerStore jdbcLedgerStore(JdbcTemplate jdbcTemplate) {
        log.info("SwarmAI Self-Improvement: JdbcLedgerStore initialized — " +
                "community investment counters persist across restarts");
        return new JdbcLedgerStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(LedgerStore.class)
    public LedgerStore inMemoryLedgerStore() {
        log.info("SwarmAI Self-Improvement: No JdbcTemplate on classpath — using NoOpLedgerStore. " +
                "Ledger counters will NOT survive restart. Add a DataSource to enable persistence.");
        return new NoOpLedgerStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public EvolutionEngine selfImprovementEvolutionEngine(LedgerStore ledgerStore) {
        log.info("SwarmAI Self-Improvement: EvolutionEngine initialized — " +
                "internal observations will drive runtime self-evolution");

        // Register a global evolution advisor on Swarm.kickoff() so process type
        // optimizations are applied transparently without example code needing to
        // read H2 manually. The advisor checks if a PROCESS_TYPE_CHANGE evolution
        // exists for workflows with independent tasks (depth 0).
        ai.intelliswarm.swarmai.swarm.Swarm.setEvolutionAdvisor((configured, taskCount, maxDepth) -> {
            if (maxDepth > 0) return configured; // tasks have dependencies, can't parallelize
            if (configured == ai.intelliswarm.swarmai.process.ProcessType.PARALLEL) return configured; // already parallel
            if (taskCount <= 1) return configured; // single task, nothing to parallelize

            // Check if a prior run learned that this shape should be parallel
            List<LedgerStore.StoredEvolution> evolutions = ledgerStore.getRecentEvolutions(20);
            boolean hasProcessChange = evolutions.stream()
                    .anyMatch(e -> "PROCESS_TYPE_CHANGE".equals(e.evolutionType()));
            if (hasProcessChange) {
                return ai.intelliswarm.swarmai.process.ProcessType.PARALLEL;
            }
            return configured;
        });
        log.info("SwarmAI Self-Improvement: Evolution advisor registered on Swarm.kickoff()");

        return new EvolutionEngine(ledgerStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SelfImprovementEventListener selfImprovementEventListener(
            ImprovementPhase phase,
            LedgerStore ledgerStore,
            SelfImprovementConfig config,
            EvolutionEngine evolutionEngine,
            org.springframework.beans.factory.ObjectProvider<DailyTelemetryScheduler> scheduler) {
        log.info("SwarmAI Self-Improvement: Auto-trigger listener registered — " +
                "improvement phase will run after every successful workflow (telemetry mode: {})",
                config.getTelemetryMode());
        return new SelfImprovementEventListener(phase, ledgerStore, config,
                evolutionEngine, scheduler.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.self-improving", name = "github-token")
    public GitHubImprovementReporter selfImprovementGithubReporter(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: GitHub reporter initialized — " +
                "PRs will be created on {}/{}", config.getGithubOwner(), config.getGithubRepo());
        return new GitHubImprovementReporter(
                config.getGithubOwner(),
                config.getGithubRepo(),
                config.getGithubToken(),
                config.getGithubBaseBranch()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemetryReporter selfImprovementTelemetryReporter(SelfImprovementConfig config) {
        if (config.isTelemetryEnabled()) {
            log.info("SwarmAI Self-Improvement: Telemetry reporter initialized — " +
                    "anonymized improvement data will be sent to {}", config.getTelemetryEndpoint());
        }
        return new TelemetryReporter(config.getTelemetryEndpoint(), config.isTelemetryEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "swarmai.self-improving", name = "telemetry-enabled",
                           havingValue = "true")
    public DailyTelemetryScheduler dailyTelemetryScheduler(SelfImprovementConfig config,
                                                            LedgerStore ledgerStore) {
        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        log.info("SwarmAI Self-Improvement: Daily telemetry scheduler registered — " +
                "rollup reported to {} on cron '{}' (zone {})",
                config.getTelemetryEndpoint() + "/api/v1/self-improving/telemetry",
                config.getTelemetryReportCron(),
                config.getTelemetryReportZone());
        return new DailyTelemetryScheduler(config, ledgerStore, version);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementReportingService selfImprovementReportingService(
            ImprovementAggregator aggregator,
            org.springframework.beans.factory.ObjectProvider<GitHubImprovementReporter> githubReporter,
            TelemetryReporter telemetryReporter) {
        log.info("SwarmAI Self-Improvement: Reporting service initialized — " +
                "improvements will flow back to the framework repository");
        return new ImprovementReportingService(
                aggregator,
                githubReporter.getIfAvailable(),
                telemetryReporter
        );
    }

    // --- Offline / Firewalled Environment Support ---

    @Bean
    @ConditionalOnMissingBean
    public ImprovementExporter selfImprovementExporter(ImprovementAggregator aggregator) {
        log.info("SwarmAI Self-Improvement: ImprovementExporter initialized — " +
                "use POST /actuator/self-improving/export for air-gapped environments");
        return new ImprovementExporter(aggregator);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public SelfImprovementHealthIndicator selfImprovementHealthIndicator(
            ImprovementExporter exporter, SelfImprovementConfig config) {
        boolean autoReporting = config.getGithubToken() != null || config.isTelemetryEnabled();
        return new SelfImprovementHealthIndicator(exporter, autoReporting);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementNudgeScheduler selfImprovementNudgeScheduler(
            ImprovementExporter exporter, SelfImprovementConfig config) {
        boolean autoReporting = config.getGithubToken() != null && !config.getGithubToken().isBlank();
        ImprovementNudgeScheduler scheduler = new ImprovementNudgeScheduler(exporter, autoReporting);
        // Nudge on startup if improvements pending and no auto-reporting
        if (!autoReporting) {
            scheduler.nudgeOnStartup();
        }
        return scheduler;
    }
}
