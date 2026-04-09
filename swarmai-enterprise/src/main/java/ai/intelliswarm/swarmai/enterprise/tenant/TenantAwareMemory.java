package ai.intelliswarm.swarmai.enterprise.tenant;

import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Tenant-aware decorator for the {@link Memory} interface.
 * Provides data isolation between tenants by prefixing agent IDs
 * with the current tenant ID from {@link TenantContext}.
 */
public class TenantAwareMemory implements Memory {

    private static final Logger logger = LoggerFactory.getLogger(TenantAwareMemory.class);
    private static final String TENANT_PREFIX_SEPARATOR = "::";

    private final Memory delegate;

    public TenantAwareMemory(Memory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(String agentId, String content, Map<String, Object> metadata) {
        delegate.save(tenantScopedAgentId(agentId), content, metadata);
    }

    /**
     * Tenant-scoped search. When a tenant context is set, searches only within
     * memories saved by this tenant's agents (prefixed agentIds). Without tenant
     * context, falls back to global unscoped search for backward compatibility.
     */
    @Override
    public List<String> search(String query, int limit) {
        String tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return delegate.search(query, limit);
        }
        String tenantPrefix = tenantId + TENANT_PREFIX_SEPARATOR;
        return delegate.searchByAgentPrefix(query, tenantPrefix, limit);
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
