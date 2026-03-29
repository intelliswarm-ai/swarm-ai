package ai.intelliswarm.swarmai.knowledge;

import java.util.List;
import java.util.Map;

public interface Knowledge {
    
    String query(String query);
    
    List<String> search(String query, int limit);
    
    void addSource(String sourceId, String content, Map<String, Object> metadata);
    
    void removeSource(String sourceId);
    
    List<String> getSources();
    
    boolean hasSource(String sourceId);
}