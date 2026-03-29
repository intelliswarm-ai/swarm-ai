package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.knowledge.Knowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tenant-aware decorator for the {@link Knowledge} interface.
 * Provides data isolation between tenants by prefixing source IDs
 * with the current tenant ID from {@link TenantContext}.
 *
 * <p>When no tenant context is set, all operations delegate directly
 * to the underlying Knowledge implementation, ensuring full backward compatibility.
 */
public class TenantAwareKnowledge implements Knowledge {

    private static final Logger logger = LoggerFactory.getLogger(TenantAwareKnowledge.class);
    private static final String TENANT_PREFIX_SEPARATOR = "::";

    private final Knowledge delegate;

    /**
     * Creates a new tenant-aware knowledge decorator.
     *
     * @param delegate the underlying Knowledge implementation to wrap
     */
    public TenantAwareKnowledge(Knowledge delegate) {
        this.delegate = delegate;
    }

    @Override
    public String query(String query) {
        return delegate.query(query);
    }

    @Override
    public List<String> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    @Override
    public void addSource(String sourceId, String content, Map<String, Object> metadata) {
        delegate.addSource(tenantScopedSourceId(sourceId), content, metadata);
    }

    @Override
    public void removeSource(String sourceId) {
        delegate.removeSource(tenantScopedSourceId(sourceId));
    }

    @Override
    public List<String> getSources() {
        String tenantId = TenantContext.currentTenantId();
        if (tenantId == null) {
            return delegate.getSources();
        }
        String prefix = tenantId + TENANT_PREFIX_SEPARATOR;
        return delegate.getSources().stream()
                .filter(sourceId -> sourceId.startsWith(prefix))
                .map(sourceId -> sourceId.substring(prefix.length()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean hasSource(String sourceId) {
        return delegate.hasSource(tenantScopedSourceId(sourceId));
    }

    /**
     * Prefixes a source ID with the current tenant ID for data isolation.
     * If no tenant context is set, returns the source ID unchanged.
     *
     * @param sourceId the original source ID
     * @return the tenant-scoped source ID, or the original if no tenant is set
     */
    private String tenantScopedSourceId(String sourceId) {
        String tenantId = TenantContext.currentTenantId();
        if (tenantId == null || sourceId == null) {
            return sourceId;
        }
        String scoped = tenantId + TENANT_PREFIX_SEPARATOR + sourceId;
        logger.trace("Scoped sourceId '{}' to tenant '{}': '{}'", sourceId, tenantId, scoped);
        return scoped;
    }
}
