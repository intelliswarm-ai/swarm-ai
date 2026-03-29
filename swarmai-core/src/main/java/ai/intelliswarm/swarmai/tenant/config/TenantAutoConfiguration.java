package ai.intelliswarm.swarmai.tenant.config;

import ai.intelliswarm.swarmai.knowledge.Knowledge;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.tenant.InMemoryTenantQuotaEnforcer;
import ai.intelliswarm.swarmai.tenant.TenantAwareKnowledge;
import ai.intelliswarm.swarmai.tenant.TenantAwareMemory;
import ai.intelliswarm.swarmai.tenant.TenantQuotaEnforcer;
import ai.intelliswarm.swarmai.tenant.TenantResourceQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-configuration for SwarmAI multi-tenant features.
 *
 * <p>When enabled via {@code swarmai.tenant.enabled=true}, this configuration provides:
 * <ul>
 *   <li>{@link TenantQuotaEnforcer} - in-memory quota enforcement</li>
 *   <li>{@link TenantAwareMemory} - tenant-isolated memory decorator (if a Memory bean exists)</li>
 *   <li>{@link TenantAwareKnowledge} - tenant-isolated knowledge decorator (if a Knowledge bean exists)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "swarmai.tenant", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenantProperties.class)
public class TenantAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TenantAutoConfiguration.class);

    /**
     * Creates the in-memory quota enforcer bean from configuration properties.
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantQuotaEnforcer tenantQuotaEnforcer(TenantProperties properties) {
        logger.info("Initializing InMemoryTenantQuotaEnforcer for multi-tenant quota management");

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

    /**
     * Creates a tenant-aware memory decorator wrapping the existing Memory bean.
     */
    @Bean
    @Primary
    @ConditionalOnBean(Memory.class)
    public TenantAwareMemory tenantAwareMemory(Memory memory) {
        logger.info("Wrapping Memory bean with TenantAwareMemory for tenant isolation");
        return new TenantAwareMemory(memory);
    }

    /**
     * Creates a tenant-aware knowledge decorator wrapping the existing Knowledge bean.
     */
    @Bean
    @Primary
    @ConditionalOnBean(Knowledge.class)
    public TenantAwareKnowledge tenantAwareKnowledge(Knowledge knowledge) {
        logger.info("Wrapping Knowledge bean with TenantAwareKnowledge for tenant isolation");
        return new TenantAwareKnowledge(knowledge);
    }
}
