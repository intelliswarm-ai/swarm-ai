package ai.intelliswarm.swarmai.enterprise.tenant;

import ai.intelliswarm.swarmai.enterprise.license.LicenseManager;
import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;

/**
 * Enterprise auto-configuration for multi-tenant features.
 * Activates only when a valid enterprise license is present.
 */
@AutoConfiguration
@ConditionalOnBean(LicenseManager.class)
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TenantAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TenantQuotaEnforcer tenantQuotaEnforcer(TenantProperties properties, LicenseManager licenseManager) {
        if (!licenseManager.hasFeature("multi-tenancy")) {
            logger.info("Multi-tenancy feature not included in license (edition={}), skipping tenant quota enforcer",
                    licenseManager.getEdition());
            return null;
        }

        logger.info("Initializing InMemoryTenantQuotaEnforcer for enterprise multi-tenant quota management");

        TenantProperties.QuotaConfig defaultConfig = properties.getDefaultQuota();
        TenantResourceQuota defaultQuota = TenantResourceQuota.builder("__default__")
                .maxConcurrentWorkflows(defaultConfig.getMaxConcurrentWorkflows())
                .maxSkills(defaultConfig.getMaxSkills())
                .maxMemoryEntries(defaultConfig.getMaxMemoryEntries())
                .maxTokenBudget(defaultConfig.getMaxTokenBudget())
                .build();

        Map<String, TenantResourceQuota> quotaMap = new HashMap<>();
        properties.getQuotas().forEach((tenantId, config) -> {
            quotaMap.put(tenantId, TenantResourceQuota.builder(tenantId)
                    .maxConcurrentWorkflows(config.getMaxConcurrentWorkflows())
                    .maxSkills(config.getMaxSkills())
                    .maxMemoryEntries(config.getMaxMemoryEntries())
                    .maxTokenBudget(config.getMaxTokenBudget())
                    .build());
            logger.debug("Registered quota for tenant '{}': maxWorkflows={}, maxSkills={}",
                    tenantId, config.getMaxConcurrentWorkflows(), config.getMaxSkills());
        });

        return new InMemoryTenantQuotaEnforcer(quotaMap, defaultQuota);
    }

    @Bean
    @Primary
    @ConditionalOnBean(Memory.class)
    public TenantAwareMemory tenantAwareMemory(Memory memory, LicenseManager licenseManager) {
        if (!licenseManager.hasFeature("multi-tenancy")) {
            return null;
        }
        logger.info("Wrapping Memory bean with TenantAwareMemory for enterprise tenant isolation");
        return new TenantAwareMemory(memory);
    }

    @Bean
    @Primary
    @ConditionalOnBean(Knowledge.class)
    public TenantAwareKnowledge tenantAwareKnowledge(Knowledge knowledge, LicenseManager licenseManager) {
        if (!licenseManager.hasFeature("multi-tenancy")) {
            return null;
        }
        logger.info("Wrapping Knowledge bean with TenantAwareKnowledge for enterprise tenant isolation");
        return new TenantAwareKnowledge(knowledge);
    }
}
