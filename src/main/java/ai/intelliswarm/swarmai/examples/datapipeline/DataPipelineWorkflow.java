package ai.intelliswarm.swarmai.examples.datapipeline;

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
 * Data Pipeline Workflow
 *
 * Demonstrates SEQUENTIAL process with data-focused tools.
 * Three agents form a pipeline: Data Engineer (profile) → Data Scientist (analyze) →
 * Business Analyst (insights & report).
 *
 * Tools showcased: FileReadTool, CSVAnalysisTool, JSONTransformTool,
 *                  XMLParseTool, CodeExecutionTool, FileWriteTool
 *
 * Usage: docker compose -f docker-compose.run.yml run --rm data-pipeline data/sample.csv
 */
@Component
public class DataPipelineWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(DataPipelineWorkflow.class);

    private final ChatClient.Builder chatClientBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final FileReadTool fileReadTool;
    private final FileWriteTool fileWriteTool;
    private final CSVAnalysisTool csvAnalysisTool;
    private final JSONTransformTool jsonTransformTool;
    private final XMLParseTool xmlParseTool;
    private final CodeExecutionTool codeExecutionTool;

    public DataPipelineWorkflow(
            ChatClient.Builder chatClientBuilder,
            ApplicationEventPublisher eventPublisher,
            FileReadTool fileReadTool,
            FileWriteTool fileWriteTool,
            CSVAnalysisTool csvAnalysisTool,
            JSONTransformTool jsonTransformTool,
            XMLParseTool xmlParseTool,
            CodeExecutionTool codeExecutionTool) {
        this.chatClientBuilder = chatClientBuilder;
        this.eventPublisher = eventPublisher;
        this.fileReadTool = fileReadTool;
        this.fileWriteTool = fileWriteTool;
        this.csvAnalysisTool = csvAnalysisTool;
        this.jsonTransformTool = jsonTransformTool;
        this.xmlParseTool = xmlParseTool;
        this.codeExecutionTool = codeExecutionTool;
    }

    public void run(String... args) throws Exception {
        logger.info("Starting Data Pipeline Workflow");

        String dataPath = args.length > 0 ? args[0] : null;

        if (dataPath == null || dataPath.isEmpty()) {
            logger.info("No data file specified. Generating sample dataset...");
            dataPath = generateSampleData();
        }

        logger.info("Processing data file: {}", dataPath);
        runDataPipeline(dataPath);
    }

    private String generateSampleData() {
        String sampleCsv =
            "employee_id,name,department,salary,years_experience,performance_score,city\n" +
            "1,Alice Johnson,Engineering,95000,8,4.2,San Francisco\n" +
            "2,Bob Smith,Marketing,72000,5,3.8,New York\n" +
            "3,Charlie Brown,Engineering,105000,12,4.5,San Francisco\n" +
            "4,Diana Lee,Sales,68000,3,3.5,Chicago\n" +
            "5,Eve Wilson,Engineering,110000,15,4.8,Seattle\n" +
            "6,Frank Miller,Marketing,78000,7,4.0,New York\n" +
            "7,Grace Chen,Sales,82000,6,4.3,Chicago\n" +
            "8,Henry Davis,Engineering,98000,9,4.1,San Francisco\n" +
            "9,Iris Park,Marketing,65000,2,3.2,New York\n" +
            "10,Jack Thompson,Sales,75000,4,3.9,Seattle\n" +
            "11,Karen White,Engineering,115000,18,4.9,San Francisco\n" +
            "12,Leo Garcia,Marketing,71000,4,3.6,Chicago\n" +
            "13,Mia Robinson,Sales,88000,8,4.4,New York\n" +
            "14,Noah Martinez,Engineering,102000,10,4.3,Seattle\n" +
            "15,Olivia Anderson,Marketing,69000,3,3.4,Chicago\n" +
            "16,Paul Taylor,Sales,79000,5,4.0,San Francisco\n" +
            "17,Quinn Harris,Engineering,92000,7,3.9,New York\n" +
            "18,Rachel Clark,Marketing,85000,9,4.2,Seattle\n" +
            "19,Sam Lewis,Sales,73000,4,3.7,Chicago\n" +
            "20,Tina Walker,Engineering,108000,13,4.6,San Francisco";

        // Write sample data to file
        fileWriteTool.execute(Map.of(
            "path", "output/sample_data.csv",
            "content", sampleCsv,
            "mode", "overwrite"
        ));

        logger.info("Sample dataset created: output/sample_data.csv (20 employees, 7 columns)");
        return "output/sample_data.csv";
    }

    private void runDataPipeline(String dataPath) {
        ChatClient chatClient = chatClientBuilder.build();

        // =====================================================================
        // AGENTS
        // =====================================================================

        Agent dataEngineer = Agent.builder()
            .role("Senior Data Engineer")
            .goal("Profile the data file at '" + dataPath + "'. Use file_read to examine the raw file. " +
                  "Use csv_analysis with operation='describe' for an overview, then operation='stats' for " +
                  "column statistics. Determine: file format, schema, data types, row count, null values, " +
                  "unique value counts per column.")
            .backstory("You are a data engineer with 8 years of experience in data quality and profiling. " +
                      "You treat every dataset with skepticism — checking for nulls, duplicates, outliers, " +
                      "and format inconsistencies. You produce a complete data dictionary.")
            .chatClient(chatClient)
            .tool(fileReadTool)
            .tool(csvAnalysisTool)
            .tool(jsonTransformTool)
            .tool(xmlParseTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.1)
            .modelName("gpt-4o-mini")
            .build();

        Agent dataScientist = Agent.builder()
            .role("Senior Data Scientist")
            .goal("Perform statistical analysis on the dataset. Use csv_analysis with operation='stats' " +
                  "for column-level statistics. Use csv_analysis with operation='count' to find distributions " +
                  "across categorical columns. Use csv_analysis with operation='filter' to investigate " +
                  "interesting subsets. Use code_execution to compute derived metrics if needed.")
            .backstory("You are a data scientist with 6 years of experience in exploratory data analysis. " +
                      "You look for patterns, correlations, and anomalies. You segment data by categories " +
                      "and compare group statistics. Every insight must be backed by a specific number.")
            .chatClient(chatClient)
            .tool(csvAnalysisTool)
            .tool(codeExecutionTool)
            .tool(jsonTransformTool)
            .verbose(true)
            .maxRpm(15)
            .temperature(0.2)
            .modelName("gpt-4o-mini")
            .build();

        Agent businessAnalyst = Agent.builder()
            .role("Senior Business Analyst")
            .goal("Translate the data profiling and statistical analysis into actionable business insights. " +
                  "Your ENTIRE response must BE the complete executive report in markdown with all " +
                  "findings, tables, and recommendations.")
            .backstory("You are a business analyst with 10 years translating data findings into business " +
                      "decisions. You write for executives who need clear, actionable recommendations — " +
                      "not technical jargon. Every recommendation must reference a specific data finding. " +
                      "You ALWAYS write the complete report as your response — never a summary.")
            .chatClient(chatClient)
            .verbose(true)
            .maxRpm(10)
            .temperature(0.3)
            .modelName("gpt-4o-mini")
            .build();

        // =====================================================================
        // TASKS — Sequential pipeline
        // =====================================================================

        Task profilingTask = Task.builder()
            .description(String.format(
                "Profile the data file at '%s'.\n\n" +
                "USE YOUR TOOLS (call each one):\n" +
                "1. Use file_read with path='%s' and limit=5 to preview the raw data\n" +
                "2. Use csv_analysis with path='%s' and operation='describe' for overview\n" +
                "3. Use csv_analysis with path='%s' and operation='stats' for column statistics\n" +
                "4. Use csv_analysis with path='%s' and operation='head' and rows=5 for sample rows\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Data Dictionary** — Table: column name, type (numeric/text), description\n" +
                "2. **Shape** — Row count, column count\n" +
                "3. **Quality Assessment** — Nulls per column, duplicate rows, data type issues\n" +
                "4. **Sample Data** — First 5 rows as table\n" +
                "5. **Key Observations** — Initial patterns noticed\n\n" +
                "RULES: Report exact numbers from tools. Do NOT estimate or assume.",
                dataPath, dataPath, dataPath, dataPath, dataPath))
            .expectedOutput("Complete data profile with dictionary, quality metrics, and sample data")
            .agent(dataEngineer)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(120000)
            .build();

        Task analysisTask = Task.builder()
            .description(String.format(
                "Perform statistical analysis on the dataset at '%s' based on the data profile.\n\n" +
                "USE YOUR TOOLS:\n" +
                "1. Use csv_analysis with operation='stats' for numeric column statistics\n" +
                "2. Use csv_analysis with operation='count' and column='department' (or similar categorical column)\n" +
                "3. Use csv_analysis with operation='count' on other categorical columns found in the profile\n" +
                "4. Use csv_analysis with operation='filter' to investigate interesting subsets\n" +
                "5. Optionally use code_execution to compute derived metrics\n\n" +
                "REQUIRED DELIVERABLES:\n" +
                "1. **Descriptive Statistics** — Table: column, min, max, mean, median for numeric columns\n" +
                "2. **Distribution Analysis** — Group counts for each categorical column\n" +
                "3. **Segment Comparison** — Compare numeric metrics across categorical groups\n" +
                "4. **Outlier Detection** — Any values >2 std deviations from mean\n" +
                "5. **Key Patterns** — 3-5 data-driven observations with exact numbers\n\n" +
                "RULES: Every finding must cite a specific number from your tools.",
                dataPath))
            .expectedOutput("Statistical analysis with tables, distributions, and patterns")
            .agent(dataScientist)
            .dependsOn(profilingTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .maxExecutionTime(180000)
            .build();

        Task insightsTask = Task.builder()
            .description(
                "Create a Data Analysis Report from the profiling and statistical findings.\n\n" +
                "REQUIRED SECTIONS:\n" +
                "1. **Executive Summary** — 3-5 key findings in business language\n" +
                "2. **Dataset Overview** — From the data profiling task\n" +
                "3. **Key Metrics** — Most important statistics (include tables)\n" +
                "4. **Segment Analysis** — Group comparisons with insights\n" +
                "5. **Patterns & Trends** — From the statistical analysis\n" +
                "6. **Recommendations** — 3-5 actionable recommendations based on data\n" +
                "7. **Data Quality Notes** — Any limitations or concerns\n\n" +
                "CRITICAL: Your ENTIRE response must BE the full report in markdown.\n" +
                "Do NOT use any tools. Do NOT call file_write. Just write the report as your response.\n" +
                "RULES: Every recommendation must reference a specific metric. No vague advice.")
            .expectedOutput("Complete markdown report with all sections, tables, and data")
            .agent(businessAnalyst)
            .dependsOn(analysisTask)
            .outputFormat(OutputFormat.MARKDOWN)
            .outputFile("output/data_pipeline_report.md")
            .maxExecutionTime(180000)
            .build();

        // =====================================================================
        // SWARM — SEQUENTIAL process
        // =====================================================================

        Swarm swarm = Swarm.builder()
            .id("data-pipeline")
            .agent(dataEngineer)
            .agent(dataScientist)
            .agent(businessAnalyst)
            .task(profilingTask)
            .task(analysisTask)
            .task(insightsTask)
            .process(ProcessType.SEQUENTIAL)
            .verbose(true)
            .maxRpm(20)
            .language("en")
            .eventPublisher(eventPublisher)
            .config("dataPath", dataPath)
            .build();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("dataPath", dataPath);

        long startTime = System.currentTimeMillis();
        SwarmOutput result = swarm.kickoff(inputs);
        long duration = (System.currentTimeMillis() - startTime) / 1000;

        // =====================================================================
        // RESULTS
        // =====================================================================

        logger.info("\n" + "=".repeat(80));
        logger.info("DATA PIPELINE COMPLETE");
        logger.info("=".repeat(80));
        logger.info("Data File: {}", dataPath);
        logger.info("Duration: {} seconds", duration);
        logger.info("Tasks completed: {}", result.getTaskOutputs().size());
        logger.info("\n{}", result.getTokenUsageSummary("gpt-4o-mini"));
        logger.info("\nFinal Report:\n{}", result.getFinalOutput());
        logger.info("=".repeat(80));
    }
}
