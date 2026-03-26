package ai.intelliswarm.swarmai.examples.codebase;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.tool.common.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Codebase Analysis Workflow
 *
 * Demonstrates PARALLEL process with the new tool library.
 * Three agents analyze a codebase simultaneously (architecture, code metrics, dependencies),
 * then a fourth agent synthesizes findings into a comprehensive technical report.
 *
 * Tools showcased: DirectoryReadTool, FileReadTool, ShellCommandTool,
 *                  CodeExecutionTool, XMLParseTool, JSONTransformTool, FileWriteTool
 *
 * Usage: docker compose -f docker-compose.run.yml run --rm codebase-analysis .
 */
@Component
public class CodebaseAnalysisWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(CodebaseAnalysisWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final DirectoryReadTool directoryReadTool;
    private final FileReadTool fileReadTool;
    private final FileWriteTool fileWriteTool;
    private final ShellCommandTool shellCommandTool;
    private final CodeExecutionTool codeExecutionTool;
    private final XMLParseTool xmlParseTool;
    private final JSONTransformTool jsonTransformTool;

    public CodebaseAnalysisWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            DirectoryReadTool directoryReadTool,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            ShellCommandTool shellCommandTool,
            CodeExecutionTool codeExecutionTool,
            XMLParseTool xmlParseTool,
            JSONTransformTool jsonTransformTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.directoryReadTool = directoryReadTool;
        this.fileReadTool = fileReadTool;
        this.fileWriteTool = fileWriteTool;
        this.shellCommandTool = shellCommandTool;
        this.codeExecutionTool = codeExecutionTool;
        this.xmlParseTool = xmlParseTool;
        this.jsonTransformTool = jsonTransformTool;
    }

    public void run(String... args) throws Exception {
        logger.info("Starting Codebase Analysis Workflow");

        String basePath = args.length > 0 ? args[0] : ".";
        logger.info("Analyzing codebase at: {}", basePath);

        runCodebaseAnalysis(basePath);
    }

    private void runCodebaseAnalysis(String basePath) {
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS
        // =====================================================================

        Agent architect = Agent.builder()
            .role("Senior Software Architect")
            .goal("Analyze the codebase architecture: directory structure, module organization, " +
                  "configuration files, and key design patterns. Use directory_read to scan the file tree " +
                  "and file_read to examine configuration files (pom.xml, application.yml, Dockerfile).")
            .backstory("You are a software architect with 12 years of experience designing Java/Spring Boot " +
                      "applications. You identify architectural patterns, layering, and module boundaries. " +
                      "You always cite specific file paths and line counts in your analysis.")
            .chatClient(chatClient)
            .tool(directoryReadTool)
            .tool(fileReadTool)
            .tool(xmlParseTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        Agent qualityEngineer = Agent.builder()
            .role("Senior Code Quality Engineer")
            .goal("Gather code quality metrics: file counts by type, line counts, git history summary, " +
                  "test file counts. Use shell_command for git and find commands, file_read for examining " +
                  "test files and source files.")
            .backstory("You are a code quality engineer with 8 years of experience. You focus on measurable " +
                      "metrics: lines of code, test coverage indicators, commit frequency, code complexity. " +
                      "You produce tables of data, not opinions.")
            .chatClient(chatClient)
            .tool(shellCommandTool)
            .tool(fileReadTool)
            .tool(directoryReadTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent dependencyAnalyst = Agent.builder()
            .role("Senior Dependency Analyst")
            .goal("Analyze project dependencies from pom.xml. Use file_read to read the full pom.xml " +
                  "(set limit=500 to get all content). Extract every <dependency> block and list " +
                  "groupId, artifactId, version. Identify key frameworks and potential risks.")
            .backstory("You are a dependency and supply chain security analyst with 6 years of experience. " +
                      "You know the Java/Spring ecosystem deeply. You flag known risky dependencies and " +
                      "identify version conflicts. You present findings in structured tables.")
            .chatClient(chatClient)
            .tool(fileReadTool)
            .tool(xmlParseTool)
            .tool(jsonTransformTool)
            .verbose(true)
            .maxRpm(12)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent technicalWriter = Agent.builder()
            .role("Senior Technical Writer")
            .goal("Synthesize all prior analysis into a comprehensive Codebase Analysis Report. " +
                  "Your ENTIRE response must BE the report content in markdown. Do NOT summarize — " +
                  "write the full report with all data, tables, and findings from prior tasks.")
            .backstory("You are a technical writer who creates clear, actionable engineering documents. " +
                      "Every claim references specific data from the analysis. You write in markdown with " +
                      "tables, headers, and bullet points. You never invent data. " +
                      "You ALWAYS write the complete report as your response — never a summary or confirmation.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        // =====================================================================
        // TASKS — 3 parallel analysis streams + 1 synthesis
        // =====================================================================

        Task architectureTask = Task.builder()
            .description(String.format(
                "Analyze the architecture of the codebase at '%s'.\n\n" +
                "USE YOUR TOOLS to gather real data:\n" +
                "1. Use directory_read with path='%s' to see the top-level structure\n" +
                "2. Use directory_read with path='%s/src/main/java' and recursive=true to map source packages\n" +
                "3. Use file_read to examine key config files (pom.xml, application.yml, Dockerfile)\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Project Structure** — Top-level directory layout with descriptions\n" +
                "2. **Package Architecture** — Main packages and their responsibilities\n" +
                "3. **Configuration** — Key settings from application.yml\n" +
                "4. **Build System** — Maven/Gradle details from build files\n" +
                "5. **Design Patterns** — Observed patterns (Builder, Strategy, Observer, etc.)\n\n" +
                "RULES: Use ONLY data from your tools. Cite file paths for every finding.",
                basePath, basePath, basePath))
            .expectedOutput("Markdown architecture analysis with file paths and structure tables")
            .agent(architect)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task metricsTask = Task.builder()
            .description(String.format(
                "Gather code quality metrics for the codebase at '%s'.\n\n" +
                "USE YOUR TOOLS:\n" +
                "1. Use shell_command: 'find %s/src -name \"*.java\" | wc -l' (count Java files)\n" +
                "2. Use shell_command: 'find %s/src/test -name \"*Test.java\" | wc -l' (count test files)\n" +
                "3. Use shell_command: 'find %s/src -name \"*.java\" -exec cat {} + | wc -l' (total lines of code)\n" +
                "4. Use directory_read with pattern='**/*Test.java' recursive=true to list test files\n" +
                "5. Try shell_command: 'git log --oneline -20' (may not be available in Docker)\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **File Counts** — Java files, test files, config files, resource files\n" +
                "2. **Code Volume** — Approximate lines of code (use wc -l)\n" +
                "3. **Test Coverage Indicators** — Test file count vs source file count, test naming patterns\n" +
                "4. **Git History** — Last 20 commits, number of contributors\n" +
                "5. **Metrics Summary Table** — All metrics in a single table\n\n" +
                "RULES: Report exact numbers from tools. Do NOT estimate.",
                basePath, basePath, basePath, basePath))
            .expectedOutput("Markdown metrics report with tables of exact counts")
            .agent(qualityEngineer)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task dependencyTask = Task.builder()
            .description(String.format(
                "Analyze dependencies of the codebase at '%s'.\n\n" +
                "USE YOUR TOOLS:\n" +
                "1. Use file_read with path='%s/pom.xml' to read the full Maven build file\n" +
                "2. Read through the file content and extract ALL <dependency> blocks manually\n" +
                "3. For each dependency, extract: groupId, artifactId, version, scope\n" +
                "   NOTE: The POM uses XML namespaces — read it as text, do NOT use XPath\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Dependency Inventory** — Table: groupId, artifactId, version, scope\n" +
                "2. **Framework Analysis** — Key frameworks (Spring Boot, Spring AI, etc.) with versions\n" +
                "3. **Risk Assessment** — Any dependencies without explicit versions, snapshot deps\n" +
                "4. **Recommendations** — Suggested updates or removals\n\n" +
                "RULES: List ONLY dependencies actually found in the build file.",
                basePath, basePath))
            .expectedOutput("Markdown dependency analysis with inventory table")
            .agent(dependencyAnalyst)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task synthesisTask = Task.builder()
            .description(
                "Write the FULL Codebase Analysis Report by combining ALL prior task outputs.\n\n" +
                "Your ENTIRE response must be the report in markdown. Include ALL of these sections " +
                "with real data from the prior tasks:\n\n" +
                "# Codebase Analysis Report\n\n" +
                "## Executive Summary\n" +
                "- 5 bullet points of key findings WITH specific numbers\n\n" +
                "## Architecture Overview\n" +
                "- Project structure table (from architecture task)\n" +
                "- Key packages and their responsibilities\n" +
                "- Design patterns observed\n\n" +
                "## Code Quality Metrics\n" +
                "- File counts, line counts, test counts (from metrics task)\n" +
                "- Include the metrics table\n" +
                "- Git history summary\n\n" +
                "## Dependency Analysis\n" +
                "- Dependency table with groupId, artifactId, version (from dependency task)\n" +
                "- Key frameworks identified\n\n" +
                "## Recommendations\n" +
                "- Top 3 prioritized improvements\n\n" +
                "CRITICAL: Your response IS the report. Write it completely with all tables and data.")
            .expectedOutput("Concise markdown report saved to file")
            .agent(technicalWriter)
            .dependsOn(architectureTask)
            .dependsOn(metricsTask)
            .dependsOn(dependencyTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/codebase_analysis_report.md")
            .maxExecutionTime(360000)
            .build();

        // =====================================================================
        // SWARM — PARALLEL process
        // =====================================================================

        Swarm swarm = Swarm.builder()
            .id("codebase-analysis")
            .agent(architect)
            .agent(qualityEngineer)
            .agent(dependencyAnalyst)
            .agent(technicalWriter)
            .task(architectureTask)
            .task(metricsTask)
            .task(dependencyTask)
            .task(synthesisTask)
            .process(ProcessType.PARALLEL)
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
            .config("basePath", basePath)
            .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("basePath", basePath);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("CODEBASE ANALYSIS COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Path Analyzed: {}", basePath);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }
}
