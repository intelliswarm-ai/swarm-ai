/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 * 
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 * 
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.research.config;

import ai.intelliswarm.swarmai.examples.research.tools.DataAnalysisTool;
import ai.intelliswarm.swarmai.examples.research.tools.ReportGeneratorTool;
import ai.intelliswarm.swarmai.examples.research.tools.WebSearchTool;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;
import java.util.Map;

/**
 * Spring Configuration for Research Tools
 * 
 * This configuration class registers all research-specific tools as Spring beans
 * that can be used by the Spring AI function calling mechanism.
 * 
 * Each tool is registered both as a regular bean and as a Function bean
 * that Spring AI can discover and use for function calling.
 */
@Configuration
@Profile({"local", "docker"})
public class ResearchToolsConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ResearchToolsConfiguration.class);

    /**
     * Web Search Tool Bean
     */
    @Bean
    public WebSearchTool webSearchTool() {
        logger.info("🔧 Registering WebSearchTool as Spring bean");
        return new WebSearchTool();
    }

    /**
     * Web Search Function for Spring AI
     */
    @Bean
    @Description("Search the web for information about companies, market data, news, and competitive intelligence")
    public Function<WebSearchRequest, WebSearchResponse> web_search() {
        logger.info("🔧 Registering web_search function for Spring AI");
        WebSearchTool tool = webSearchTool();
        
        return request -> {
            try {
                Map<String, Object> parameters = Map.of(
                    "query", request.query(),
                    "search_type", request.searchType() != null ? request.searchType() : "general",
                    "max_results", request.maxResults() != null ? request.maxResults() : 5
                );
                
                Object result = tool.execute(parameters);
                
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    return new WebSearchResponse(
                        (String) resultMap.get("query"),
                        (String) resultMap.get("type"),
                        (String) resultMap.get("results"),
                        (Integer) resultMap.get("total_results")
                    );
                }
                
                return new WebSearchResponse(request.query(), "general", result.toString(), 1);
            } catch (Exception e) {
                logger.error("Error executing web search: {}", e.getMessage(), e);
                return new WebSearchResponse(request.query(), "error", "Search failed: " + e.getMessage(), 0);
            }
        };
    }

    /**
     * Data Analysis Tool Bean
     */
    @Bean
    public DataAnalysisTool dataAnalysisTool() {
        logger.info("🔧 Registering DataAnalysisTool as Spring bean");
        return new DataAnalysisTool();
    }

    /**
     * Data Analysis Function for Spring AI
     */
    @Bean
    @Description("Analyze market data and competitor metrics to identify patterns, trends, and insights")
    public Function<DataAnalysisRequest, DataAnalysisResponse> data_analysis() {
        logger.info("🔧 Registering data_analysis function for Spring AI");
        DataAnalysisTool tool = dataAnalysisTool();
        
        return request -> {
            try {
                Map<String, Object> parameters = Map.of(
                    "data", request.data(),
                    "analysis_type", request.analysisType() != null ? request.analysisType() : "general",
                    "include_charts", request.includeCharts() != null ? request.includeCharts() : false
                );
                
                Object result = tool.execute(parameters);
                
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    return new DataAnalysisResponse(
                        (String) resultMap.get("analysis_type"),
                        (String) resultMap.get("results"),
                        (String) resultMap.get("summary")
                    );
                }
                
                return new DataAnalysisResponse("general", result.toString(), "Analysis completed");
            } catch (Exception e) {
                logger.error("Error executing data analysis: {}", e.getMessage(), e);
                return new DataAnalysisResponse("error", "Analysis failed: " + e.getMessage(), "Error occurred");
            }
        };
    }

    /**
     * Report Generator Tool Bean
     */
    @Bean
    public ReportGeneratorTool reportGeneratorTool() {
        logger.info("🔧 Registering ReportGeneratorTool as Spring bean");
        return new ReportGeneratorTool();
    }

    /**
     * Report Generator Function for Spring AI
     */
    @Bean
    @Description("Generate professional reports from research data and analysis results")
    public Function<ReportGeneratorRequest, ReportGeneratorResponse> report_generator() {
        logger.info("🔧 Registering report_generator function for Spring AI");
        ReportGeneratorTool tool = reportGeneratorTool();
        
        return request -> {
            try {
                Map<String, Object> parameters = Map.of(
                    "content", request.content(),
                    "report_type", request.reportType() != null ? request.reportType() : "executive",
                    "format", request.format() != null ? request.format() : "markdown"
                );
                
                Object result = tool.execute(parameters);
                
                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    return new ReportGeneratorResponse(
                        (String) resultMap.get("report_type"),
                        (String) resultMap.get("format"),
                        (String) resultMap.get("content"),
                        (String) resultMap.get("file_path")
                    );
                }
                
                return new ReportGeneratorResponse("executive", "markdown", result.toString(), null);
            } catch (Exception e) {
                logger.error("Error executing report generation: {}", e.getMessage(), e);
                return new ReportGeneratorResponse("error", "text", "Report generation failed: " + e.getMessage(), null);
            }
        };
    }

    // Request/Response record classes for Spring AI function calling

    public record WebSearchRequest(
        String query,
        String searchType,
        Integer maxResults
    ) {}

    public record WebSearchResponse(
        String query,
        String type,
        String results,
        Integer totalResults
    ) {}

    public record DataAnalysisRequest(
        String data,
        String analysisType,
        Boolean includeCharts
    ) {}

    public record DataAnalysisResponse(
        String analysisType,
        String results,
        String summary
    ) {}

    public record ReportGeneratorRequest(
        String content,
        String reportType,
        String format
    ) {}

    public record ReportGeneratorResponse(
        String reportType,
        String format,
        String content,
        String filePath
    ) {}
}