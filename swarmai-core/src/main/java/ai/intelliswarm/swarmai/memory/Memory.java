package ai.intelliswarm.swarmai.memory;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;

@PublicApi(since = "1.0")
public interface Memory {
    
    void save(String agentId, String content, Map<String, Object> metadata);
    
    List<String> search(String query, int limit);
    
    List<String> getRecentMemories(String agentId, int limit);
    
    void clear();
    
    void clearForAgent(String agentId);
    
    int size();
    
    boolean isEmpty();
}