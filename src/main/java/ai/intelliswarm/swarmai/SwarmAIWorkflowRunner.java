/*
 * SwarmAI Framework
 * Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 *
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai;

import ai.intelliswarm.swarmai.examples.duediligence.DueDiligenceWorkflow;
import ai.intelliswarm.swarmai.examples.iterative.IterativeInvestmentMemoWorkflow;
import ai.intelliswarm.swarmai.examples.mcpresearch.McpResearchWorkflow;
import ai.intelliswarm.swarmai.examples.research.CompetitiveAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.stock.StockAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.codebase.CodebaseAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.webresearch.WebResearchWorkflow;
import ai.intelliswarm.swarmai.examples.datapipeline.DataPipelineWorkflow;
import ai.intelliswarm.swarmai.examples.selfimproving.SelfImprovingWorkflow;
import ai.intelliswarm.swarmai.examples.enterprise.EnterpriseSelfImprovingWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SwarmAIWorkflowRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(SwarmAIWorkflowRunner.class);

    @Value("${swarmai.studio.enabled:false}")
    private boolean studioEnabled;

    private final CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow;
    private final StockAnalysisWorkflow stockAnalysisWorkflow;
    private final DueDiligenceWorkflow dueDiligenceWorkflow;
    private final McpResearchWorkflow mcpResearchWorkflow;
    private final IterativeInvestmentMemoWorkflow iterativeInvestmentMemoWorkflow;
    private final CodebaseAnalysisWorkflow codebaseAnalysisWorkflow;
    private final WebResearchWorkflow webResearchWorkflow;
    private final DataPipelineWorkflow dataPipelineWorkflow;
    private final SelfImprovingWorkflow selfImprovingWorkflow;
    private final EnterpriseSelfImprovingWorkflow enterpriseSelfImprovingWorkflow;

    public SwarmAIWorkflowRunner(
            CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow,
            StockAnalysisWorkflow stockAnalysisWorkflow,
            DueDiligenceWorkflow dueDiligenceWorkflow,
            McpResearchWorkflow mcpResearchWorkflow,
            IterativeInvestmentMemoWorkflow iterativeInvestmentMemoWorkflow,
            CodebaseAnalysisWorkflow codebaseAnalysisWorkflow,
            WebResearchWorkflow webResearchWorkflow,
            DataPipelineWorkflow dataPipelineWorkflow,
            SelfImprovingWorkflow selfImprovingWorkflow,
            EnterpriseSelfImprovingWorkflow enterpriseSelfImprovingWorkflow) {
        this.competitiveAnalysisWorkflow = competitiveAnalysisWorkflow;
        this.stockAnalysisWorkflow = stockAnalysisWorkflow;
        this.dueDiligenceWorkflow = dueDiligenceWorkflow;
        this.mcpResearchWorkflow = mcpResearchWorkflow;
        this.iterativeInvestmentMemoWorkflow = iterativeInvestmentMemoWorkflow;
        this.codebaseAnalysisWorkflow = codebaseAnalysisWorkflow;
        this.webResearchWorkflow = webResearchWorkflow;
        this.dataPipelineWorkflow = dataPipelineWorkflow;
        this.selfImprovingWorkflow = selfImprovingWorkflow;
        this.enterpriseSelfImprovingWorkflow = enterpriseSelfImprovingWorkflow;
    }

    @Override
    public void run(String... args) throws Exception {
        // Filter out Spring Boot args (--spring.*, --logging.*, etc.)
        java.util.List<String> filteredArgs = new java.util.ArrayList<>();
        for (String arg : args) {
            if (!arg.startsWith("--spring.") && !arg.startsWith("--logging.")) {
                filteredArgs.add(arg);
            }
        }

        if (filteredArgs.isEmpty()) {
            showUsage();
            return;
        }

        String workflowType = filteredArgs.get(0).toLowerCase();
        String[] workflowArgs = filteredArgs.subList(1, filteredArgs.size()).toArray(new String[0]);

        switch (workflowType) {
            case "competitive-analysis":
                competitiveAnalysisWorkflow.run(workflowArgs);
                break;
            case "stock-analysis":
                stockAnalysisWorkflow.run(workflowArgs);
                break;
            case "due-diligence":
                dueDiligenceWorkflow.run(workflowArgs);
                break;
            case "mcp-research":
                mcpResearchWorkflow.run(workflowArgs);
                break;
            case "iterative-memo":
                iterativeInvestmentMemoWorkflow.run(workflowArgs);
                break;
            case "codebase-analysis":
                codebaseAnalysisWorkflow.run(workflowArgs);
                break;
            case "web-research":
                webResearchWorkflow.run(workflowArgs);
                break;
            case "data-pipeline":
                dataPipelineWorkflow.run(workflowArgs);
                break;
            case "self-improving":
                selfImprovingWorkflow.run(workflowArgs);
                break;
            case "enterprise-governed":
                enterpriseSelfImprovingWorkflow.run(workflowArgs);
                break;
            default:
                System.err.println("Unknown workflow type: " + workflowType);
                showUsage();
                System.exit(1);
        }

        // When Studio is enabled, keep the server alive so users can inspect results
        if (studioEnabled) {
            logger.info("");
            logger.info("==========================================================");
            logger.info("  SwarmAI Studio is running at: http://localhost:8080/studio");
            logger.info("  Workflow complete. Inspect results in the Studio UI.");
            logger.info("  Press Ctrl+C to stop the server.");
            logger.info("==========================================================");
            logger.info("");
            // Block the CommandLineRunner thread — the web server stays alive on its own threads
            Thread.currentThread().join();
        }
    }

    private void showUsage() {
        System.out.println("SwarmAI Framework - Multi-Agent Workflow System");
        System.out.println("===============================================");
        System.out.println();
        System.out.println("Usage: java -jar swarmai-framework.jar <workflow-type> [options]");
        System.out.println();
        System.out.println("Available workflows:");
        System.out.println("  stock-analysis <TICKER>     - Financial stock analysis (default: AAPL)");
        System.out.println("  competitive-analysis <QUERY> - Multi-agent research on any topic");
        System.out.println("  due-diligence <TICKER>      - Comprehensive company due diligence");
        System.out.println("  mcp-research <QUERY>        - Research using MCP tools (web fetch/search)");
        System.out.println("  iterative-memo <TICKER> [N] - Iterative investment memo with review loop (default: NVDA, 3 iterations)");
        System.out.println("  codebase-analysis [PATH]    - Analyze codebase architecture, metrics, and dependencies (default: .)");
        System.out.println("  web-research <QUERY>        - Deep web research with scraping, fact-checking, and report");
        System.out.println("  data-pipeline [FILE]        - AI-powered data profiling, analysis, and insights report");
        System.out.println("  self-improving <QUERY>       - Self-improving workflow: generates new tools at runtime");
        System.out.println("  enterprise-governed <QUERY>  - Enterprise: self-improving + tenancy + budget + governance");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar swarmai-framework.jar stock-analysis TSLA");
        System.out.println("  java -jar swarmai-framework.jar competitive-analysis \"AI trends 2026\"");
        System.out.println("  java -jar swarmai-framework.jar due-diligence MSFT");
        System.out.println("  java -jar swarmai-framework.jar mcp-research \"AI agents in enterprise 2026\"");
        System.out.println("  java -jar swarmai-framework.jar iterative-memo NVDA 3");
        System.out.println("  java -jar swarmai-framework.jar codebase-analysis .");
        System.out.println("  java -jar swarmai-framework.jar web-research \"AI agent frameworks 2026\"");
        System.out.println("  java -jar swarmai-framework.jar data-pipeline data/sample.csv");
        System.out.println("  java -jar swarmai-framework.jar self-improving \"Analyze AAPL with YoY growth\"");
        System.out.println("  java -jar swarmai-framework.jar enterprise-governed \"Compare top 5 AI coding assistants\"");
        System.out.println();
    }
}
