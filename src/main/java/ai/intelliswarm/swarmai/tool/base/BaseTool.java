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

    /**
     * Maximum response length in characters. Tool output will be truncated to this limit
     * to prevent exceeding LLM context windows. Override to customize per tool.
     * Default: 8000 chars (~2000 tokens).
     */
    default int getMaxResponseLength() {
        return 8000;
    }
}