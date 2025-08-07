/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 * 
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 * 
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.stock.config;

import ai.intelliswarm.swarmai.examples.stock.tools.CalculatorTool;
import ai.intelliswarm.swarmai.examples.stock.tools.SECFilingsTool;
import ai.intelliswarm.swarmai.examples.stock.tools.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Configuration for Stock Analysis Tools
 * 
 * This configuration class registers all tools needed for stock analysis
 * as Spring beans and Spring AI function beans.
 */
@Configuration
public class StockToolsConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(StockToolsConfiguration.class);

    @Bean
    public CalculatorTool stockCalculatorTool() {
        logger.info("🔧 Registering Stock CalculatorTool as Spring bean");
        return new CalculatorTool();
    }

    @Bean
    public WebSearchTool stockWebSearchTool() {
        logger.info("🔧 Registering Stock WebSearchTool as Spring bean");
        return new WebSearchTool();
    }

    @Bean
    public SECFilingsTool stockSECFilingsTool() {
        logger.info("🔧 Registering Stock SECFilingsTool as Spring bean");
        return new SECFilingsTool();
    }

    // Register function beans for Spring AI
    @Bean
    @Description("Performs mathematical calculations for financial analysis like P/E ratios, growth rates, and valuation metrics")
    public Function<CalculatorTool.Request, String> calculator() {
        logger.info("🔧 Registering calculator function for Spring AI");
        CalculatorTool tool = stockCalculatorTool();
        return request -> (String) tool.execute(java.util.Map.of("expression", request.expression()));
    }
    
    @Bean
    @Description("Searches the web for stock news, market analysis, and financial information")
    public Function<WebSearchTool.Request, String> web_search() {
        logger.info("🔧 Registering web_search function for Spring AI");
        WebSearchTool tool = stockWebSearchTool();
        return request -> (String) tool.execute(java.util.Map.of("query", request.query()));
    }
    
    @Bean
    @Description("Analyzes SEC filings (10-K, 10-Q) for company financial data and regulatory information")
    public Function<SECFilingsTool.Request, String> sec_filings() {
        logger.info("🔧 Registering sec_filings function for Spring AI");
        SECFilingsTool tool = stockSECFilingsTool();
        return request -> (String) tool.execute(java.util.Map.of("input", request.input()));
    }
}