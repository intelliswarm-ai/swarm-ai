package ai.intelliswarm.swarmai.memory;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;

@PublicApi(since = "1.0")
public interface Memory {

    void save(String agentId, String content, Map<String, Object> metadata);

    List<String> search(String query, int limit);

    /**
     * Searches memories scoped to agents whose IDs start with the given prefix.
     * Used by TenantAwareMemory to restrict search results to a single tenant's data.
     *
     * <p>The default implementation FAILS CLOSED: it returns an empty list and logs
     * a warning. It must not fall back to unscoped {@link #search}, because a custom
     * Memory implementation that forgets to override this method would then silently
     * leak other tenants' memories through TenantAwareMemory. Implementations that
     * support scoping must override this method.
     *
     * @param query        keyword to search for in memory content
     * @param agentPrefix  only return results from agents whose ID starts with this prefix
     * @param limit        maximum number of results
     * @return matching memory content strings, empty list if none found
     */
    default List<String> searchByAgentPrefix(String query, String agentPrefix, int limit) {
        org.slf4j.LoggerFactory.getLogger(Memory.class).warn(
                "{} does not override searchByAgentPrefix; returning no results rather than "
                        + "leaking unscoped search across tenants. Override searchByAgentPrefix "
                        + "to enable tenant-scoped memory search.",
                getClass().getName());
        return List.of();
    }

    List<String> getRecentMemories(String agentId, int limit);

    void clear();

    void clearForAgent(String agentId);

    int size();

    boolean isEmpty();
}