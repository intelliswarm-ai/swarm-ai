package ai.intelliswarm.swarmai.enterprise.config;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import ai.intelliswarm.swarmai.enterprise.license.LicenseValidationException;
import ai.intelliswarm.swarmai.spi.LicenseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Master auto-configuration for SwarmAI Enterprise.
 * Activates only when a license key is provided.
 * All enterprise beans are gated behind this configuration.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "swarmai.enterprise", name = "license-key")
@EnableConfigurationProperties(EnterpriseProperties.class)
public class EnterpriseAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(EnterpriseAutoConfiguration.class);

    @Bean
    public LicenseManager licenseManager(EnterpriseProperties properties) {
        try {
            LicenseManager manager = new LicenseManager(properties.getLicenseKey());
            logger.info("SwarmAI Enterprise activated: edition={}", manager.getEdition());
            return manager;
        } catch (LicenseValidationException e) {
            logger.error("Invalid enterprise license key: {}. Enterprise features will not be available.", e.getMessage());
            throw e;
        }
    }

    @Bean
    public LicenseProvider licenseProvider(LicenseManager licenseManager) {
        return licenseManager;
    }
}
