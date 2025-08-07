/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 * 
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 * 
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai;

import ai.intelliswarm.swarmai.examples.research.CompetitiveAnalysisWorkflow;
import ai.intelliswarm.swarmai.examples.stock.StockAnalysisWorkflow;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class SwarmAIWorkflowRunner implements CommandLineRunner {
    
    private final CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow;
    private final StockAnalysisWorkflow stockAnalysisWorkflow;
    
    public SwarmAIWorkflowRunner(
            CompetitiveAnalysisWorkflow competitiveAnalysisWorkflow,
            StockAnalysisWorkflow stockAnalysisWorkflow) {
        this.competitiveAnalysisWorkflow = competitiveAnalysisWorkflow;
        this.stockAnalysisWorkflow = stockAnalysisWorkflow;
    }
    
    @Override
    public void run(String... args) throws Exception {
        if (args.length == 0) {
            showUsage();
            return;
        }
        
        String workflowType = args[0].toLowerCase();
        String[] workflowArgs = new String[Math.max(0, args.length - 1)];
        System.arraycopy(args, 1, workflowArgs, 0, workflowArgs.length);
        
        switch (workflowType) {
            case "competitive-analysis":
                System.out.println("🚀 Starting Competitive Analysis Workflow...");
                competitiveAnalysisWorkflow.run(workflowArgs);
                break;
            case "stock-analysis":
                System.out.println("📊 Starting Stock Analysis Workflow...");
                stockAnalysisWorkflow.run(workflowArgs);
                break;
            default:
                System.err.println("❌ Unknown workflow type: " + workflowType);
                showUsage();
                System.exit(1);
        }
    }
    
    private void showUsage() {
        System.out.println("SwarmAI Framework - Multi-Agent Workflow System");
        System.out.println("===============================================");
        System.out.println();
        System.out.println("Usage: java -jar swarmai-framework.jar <workflow-type> [options]");
        System.out.println();
        System.out.println("Available workflows:");
        System.out.println("  competitive-analysis  - Multi-agent competitive analysis research");
        System.out.println("  stock-analysis <TICKER> - Financial stock analysis (default: AAPL)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar swarmai-framework.jar competitive-analysis");
        System.out.println("  java -jar swarmai-framework.jar stock-analysis TSLA");
        System.out.println("  java -jar swarmai-framework.jar stock-analysis GOOGL");
        System.out.println();
    }
}