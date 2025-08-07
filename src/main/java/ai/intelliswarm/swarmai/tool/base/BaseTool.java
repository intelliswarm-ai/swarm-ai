package ai.intelliswarm.swarmai.tool.base;

import java.util.Map;

public interface BaseTool {
    
    String getFunctionName();
    
    String getDescription();
    
    Object execute(Map<String, Object> parameters);
    
    Map<String, Object> getParameterSchema();
    
    boolean isAsync();
    
    default int getMaxUsageCount() {
        return Integer.MAX_VALUE;
    }
    
    default boolean isCacheable() {
        return false;
    }
}