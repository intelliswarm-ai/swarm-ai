package ai.intelliswarm.swarmai.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory implementation of {@link TenantQuotaEnforcer}.
 * Uses ConcurrentHashMap with AtomicInteger counters for thread-safe
 * tracking of active workflow counts per tenant.
 *
 * <p>Suitable for single-instance deployments. For distributed deployments,
 * an implementation backed by Redis or a similar distributed store is recommended.
 */
public class InMemoryTenantQuotaEnforcer implements TenantQuotaEnforcer {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryTenantQuotaEnforcer.class);

    private final Map<String, TenantResourceQuota> quotas;
    private final TenantResourceQuota defaultQuota;
    private final ConcurrentHashMap<String, AtomicInteger> activeWorkflowCounts = new ConcurrentHashMap<>();

    /**
     * Creates a new enforcer with tenant-specific quotas and a fallback default.
     *
     * @param quotas       per-tenant quota definitions (keyed by tenant ID)
     * @param defaultQuota the default quota applied when no tenant-specific quota exists
     */
    public InMemoryTenantQuotaEnforcer(Map<String, TenantResourceQuota> quotas, TenantResourceQuota defaultQuota) {
        this.quotas = new ConcurrentHashMap<>(quotas);
        this.defaultQuota = defaultQuota;
    }

    @Override
    public void checkWorkflowQuota(String tenantId) throws TenantQuotaExceededException {
        TenantResourceQuota quota = getQuota(tenantId);
        int active = getActiveWorkflowCount(tenantId);

        if (active >= quota.maxConcurrentWorkflows()) {
            logger.warn("Tenant '{}' exceeded workflow quota: active={}, max={}",
                    tenantId, active, quota.maxConcurrentWorkflows());
            throw new TenantQuotaExceededException(
                    tenantId,
                    "workflow",
                    String.format("Tenant '%s' has reached the maximum concurrent workflows (%d/%d)",
                            tenantId, active, quota.maxConcurrentWorkflows())
            );
        }
    }

    @Override
    public void checkSkillQuota(String tenantId, int currentCount) throws TenantQuotaExceededException {
        TenantResourceQuota quota = getQuota(tenantId);

        if (currentCount >= quota.maxSkills()) {
            logger.warn("Tenant '{}' exceeded skill quota: current={}, max={}",
                    tenantId, currentCount, quota.maxSkills());
            throw new TenantQuotaExceededException(
                    tenantId,
                    "skill",
                    String.format("Tenant '%s' has reached the maximum number of skills (%d/%d)",
                            tenantId, currentCount, quota.maxSkills())
            );
        }
    }

    @Override
    public void recordWorkflowStart(String tenantId) {
        activeWorkflowCounts.computeIfAbsent(tenantId, k -> new AtomicInteger(0)).incrementAndGet();
        logger.debug("Tenant '{}' workflow started, active count: {}", tenantId, getActiveWorkflowCount(tenantId));
    }

    @Override
    public void recordWorkflowEnd(String tenantId) {
        AtomicInteger count = activeWorkflowCounts.get(tenantId);
        if (count != null) {
            int newValue = count.decrementAndGet();
            if (newValue < 0) {
                count.set(0);
                logger.warn("Tenant '{}' workflow count went negative, reset to 0", tenantId);
            }
        }
        logger.debug("Tenant '{}' workflow ended, active count: {}", tenantId, getActiveWorkflowCount(tenantId));
    }

    @Override
    public int getActiveWorkflowCount(String tenantId) {
        AtomicInteger count = activeWorkflowCounts.get(tenantId);
        return count != null ? count.get() : 0;
    }

    /**
     * Returns the effective quota for a tenant, falling back to the default.
     */
    private TenantResourceQuota getQuota(String tenantId) {
        return quotas.getOrDefault(tenantId, defaultQuota);
    }
}
