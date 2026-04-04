package ai.intelliswarm.swarmai.selfimproving.config;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
import ai.intelliswarm.swarmai.selfimproving.reporter.GitHubImprovementReporter;
import ai.intelliswarm.swarmai.selfimproving.reporter.ImprovementReportingService;
import ai.intelliswarm.swarmai.selfimproving.reporter.TelemetryReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
@ConditionalOnProperty(prefix = "swarmai.self-improving", name = "enabled", havingValue = "true")
public class SelfImprovementAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ImprovementCollector improvementCollector() {
        log.info("SwarmAI Self-Improvement: ImprovementCollector initialized");
        return new ImprovementCollector();
    }

    @Bean
    @ConditionalOnMissingBean
    public PatternExtractor patternExtractor(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: PatternExtractor initialized");
        return new PatternExtractor(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementClassifier improvementClassifier(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: ImprovementClassifier initialized");
        return new ImprovementClassifier(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementAggregator improvementAggregator(SelfImprovementConfig config) {
        log.info("SwarmAI Self-Improvement: ImprovementAggregator initialized — " +
                "community investment tracking enabled");
        return new ImprovementAggregator(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementPhase improvementPhase(SelfImprovementConfig config,
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
    @ConditionalOnProperty(prefix = "swarmai.self-improving", name = "github-token")
    public GitHubImprovementReporter githubImprovementReporter(SelfImprovementConfig config) {
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
    public TelemetryReporter telemetryReporter(SelfImprovementConfig config) {
        if (config.isTelemetryEnabled()) {
            log.info("SwarmAI Self-Improvement: Telemetry reporter initialized — " +
                    "anonymized improvement data will be sent to {}", config.getTelemetryEndpoint());
        }
        return new TelemetryReporter(config.getTelemetryEndpoint(), config.isTelemetryEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    public ImprovementReportingService improvementReportingService(
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
}
