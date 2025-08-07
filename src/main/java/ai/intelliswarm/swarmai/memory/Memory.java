package ai.intelliswarm.swarmai.memory;

import java.util.List;
import java.util.Map;

public interface Memory {
    
    void save(String agentId, String content, Map<String, Object> metadata);
    
    List<String> search(String query, int limit);
    
    List<String> getRecentMemories(String agentId, int limit);
    
    void clear();
    
    void clearForAgent(String agentId);
    
    int size();
    
    boolean isEmpty();
}