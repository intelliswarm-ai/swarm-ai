package ai.intelliswarm.swarmai.knowledge;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.List;
import java.util.Map;

@PublicApi(since = "1.0")
public interface Knowledge {
    
    String query(String query);
    
    List<String> search(String query, int limit);
    
    void addSource(String sourceId, String content, Map<String, Object> metadata);
    
    void removeSource(String sourceId);
    
    List<String> getSources();
    
    boolean hasSource(String sourceId);
}