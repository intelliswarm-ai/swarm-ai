package ai.intelliswarm.swarmai.distributed.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Auto-configuration for the SwarmAI distributed execution module.
 *
 * <p>Activated when {@code swarmai.distributed.enabled=true}.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swarmai.distributed", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DistributedConfig.class)
public class DistributedAutoConfiguration {
    // Cluster infrastructure beans are created per-execution by DistributedProcess.
    // This auto-configuration enables the config properties and future
    // shared beans (e.g., persistent cluster registry, discovery service).
}
