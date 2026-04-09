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
     * @param query        keyword to search for in memory content
     * @param agentPrefix  only return results from agents whose ID starts with this prefix
     * @param limit        maximum number of results
     * @return matching memory content strings, empty list if none found
     */
    default List<String> searchByAgentPrefix(String query, String agentPrefix, int limit) {
        // Default: fall back to unscoped search (backward compatible)
        return search(query, limit);
    }

    List<String> getRecentMemories(String agentId, int limit);

    void clear();

    void clearForAgent(String agentId);

    int size();

    boolean isEmpty();
}