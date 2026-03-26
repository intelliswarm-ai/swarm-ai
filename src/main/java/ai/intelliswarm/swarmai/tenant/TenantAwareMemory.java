package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.memory.Memory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Tenant-aware decorator for the {@link Memory} interface.
 * Provides data isolation between tenants by prefixing agent IDs
 * with the current tenant ID from {@link TenantContext}.
 *
 * <p>When no tenant context is set, all operations delegate directly
 * to the underlying Memory implementation, ensuring full backward compatibility.
 */
public class TenantAwareMemory implements Memory {

    private static final Logger logger = LoggerFactory.getLogger(TenantAwareMemory.class);
    private static final String TENANT_PREFIX_SEPARATOR = "::";

    private final Memory delegate;

    /**
     * Creates a new tenant-aware memory decorator.
     *
     * @param delegate the underlying Memory implementation to wrap
     */
    public TenantAwareMemory(Memory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        delegate.save(tenantScopedAgentId(agentId), content, metadata);
    }

    @Override
    public List<String> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    @Override
    public List<String> getRecentMemories(String agentId, int limit) {
        return delegate.getRecentMemories(tenantScopedAgentId(agentId), limit);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void clearForAgent(String agentId) {
        delegate.clearForAgent(tenantScopedAgentId(agentId));
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    /**
     * Prefixes an agent ID with the current tenant ID for data isolation.
     * If no tenant context is set, returns the agent ID unchanged.
     *
     * @param agentId the original agent ID
     * @return the tenant-scoped agent ID, or the original if no tenant is set
     */
    private String tenantScopedAgentId(String agentId) {
        String tenantId = TenantContext.currentTenantId();
        if (tenantId == null || agentId == null) {
            return agentId;
        }
        String scoped = tenantId + TENANT_PREFIX_SEPARATOR + agentId;
        logger.trace("Scoped agentId '{}' to tenant '{}': '{}'", agentId, tenantId, scoped);
        return scoped;
    }
}
