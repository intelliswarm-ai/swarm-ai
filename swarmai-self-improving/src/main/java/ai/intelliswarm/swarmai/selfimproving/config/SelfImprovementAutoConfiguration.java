package ai.intelliswarm.swarmai.selfimproving.config;

import ai.intelliswarm.swarmai.selfimproving.aggregator.ImprovementAggregator;
import ai.intelliswarm.swarmai.selfimproving.classifier.ImprovementClassifier;
import ai.intelliswarm.swarmai.selfimproving.collector.ImprovementCollector;
import ai.intelliswarm.swarmai.selfimproving.extractor.PatternExtractor;
import ai.intelliswarm.swarmai.selfimproving.phase.ImprovementPhase;
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
}
