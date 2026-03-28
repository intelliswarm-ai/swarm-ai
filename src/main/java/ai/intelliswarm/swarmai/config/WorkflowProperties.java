package ai.intelliswarm.swarmai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Externalized configuration for self-improving workflows.
 *
 * Replaces all hardcoded values in SelfImprovingWorkflow with YAML-driven config:
 *
 * <pre>
 * swarmai:
 *   workflow:
 *     model: gpt-4.1
 *     max-iterations: 15
 *     agents:
 *       analyst:
 *         temperature: 0.2
 *         max-rpm: 15
 *     ...
 * </pre>
 *
 * Every value has a sensible default so the workflow works out of the box.
 */
@Component
@ConfigurationProperties(prefix = "swarmai.workflow")
public class WorkflowProperties {

    // ==================== Model ====================

    /** Default LLM model for all agents (overridable per agent). */
    private String model = "gpt-4.1";

    // ==================== Process ====================

    /** Maximum self-improving iterations before stopping. */
    private int maxIterations = 15;

    /** Language for output. */
    private String language = "en";

    /** Enable verbose logging for agents. */
    private boolean verbose = true;

    /** Global max RPM for the swarm. */
    private int maxRpm = 20;

    // ==================== Agents ====================

    private AgentConfig agents = new AgentConfig();

    // ==================== Tasks ====================

    private TaskConfig tasks = new TaskConfig();

    // ==================== Tools ====================

    /** Tool names to include. Empty = all available tools. */
    private List<String> tools = List.of();

    /** Known-good API endpoints the planner can reference. */
    private List<ApiEndpoint> knownEndpoints = new ArrayList<>();

    /** Scrape-safe URLs for web_scrape. */
    private List<String> scrapeSafeUrls = List.of(
        "https://en.wikipedia.org/wiki/{topic}",
        "https://news.ycombinator.com"
    );

    // ==================== Output ====================

    private OutputConfig output = new OutputConfig();

    // ==================== Planning ====================

    private PlanningConfig planning = new PlanningConfig();

    // ==================== Nested Config Classes ====================

    public static class AgentConfig {
        private AgentDef analyst = new AgentDef(0.2, 15, true);
        private AgentDef writer = new AgentDef(0.3, 10, true);
        private AgentDef reviewer = new AgentDef(0.1, 10, true);
        private AgentDef planner = new AgentDef(0.1, 10, false);

        public AgentDef getAnalyst() { return analyst; }
        public void setAnalyst(AgentDef analyst) { this.analyst = analyst; }
        public AgentDef getWriter() { return writer; }
        public void setWriter(AgentDef writer) { this.writer = writer; }
        public AgentDef getReviewer() { return reviewer; }
        public void setReviewer(AgentDef reviewer) { this.reviewer = reviewer; }
        public AgentDef getPlanner() { return planner; }
        public void setPlanner(AgentDef planner) { this.planner = planner; }
    }

    public static class AgentDef {
        private double temperature;
        private int maxRpm;
        private boolean verbose;
        /** Override the global model for this agent. Null = use global. */
        private String model;

        public AgentDef() {
            this(0.3, 10, true);
        }

        public AgentDef(double temperature, int maxRpm, boolean verbose) {
            this.temperature = temperature;
            this.maxRpm = maxRpm;
            this.verbose = verbose;
        }

        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxRpm() { return maxRpm; }
        public void setMaxRpm(int maxRpm) { this.maxRpm = maxRpm; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean verbose) { this.verbose = verbose; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        /** Resolve model: per-agent override or fall back to global. */
        public String resolveModel(String globalModel) {
            return model != null && !model.isBlank() ? model : globalModel;
        }
    }

    public static class TaskConfig {
        private TaskDef analysis = new TaskDef(300000);
        private TaskDef report = new TaskDef(180000);
        private TaskDef planning = new TaskDef(30000);

        public TaskDef getAnalysis() { return analysis; }
        public void setAnalysis(TaskDef analysis) { this.analysis = analysis; }
        public TaskDef getReport() { return report; }
        public void setReport(TaskDef report) { this.report = report; }
        public TaskDef getPlanning() { return planning; }
        public void setPlanning(TaskDef planning) { this.planning = planning; }
    }

    public static class TaskDef {
        /** Max execution time in milliseconds. */
        private long maxExecutionTime;

        public TaskDef() { this(300000); }
        public TaskDef(long maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }

        public long getMaxExecutionTime() { return maxExecutionTime; }
        public void setMaxExecutionTime(long maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }
    }

    public static class OutputConfig {
        /** Output file for the final report. */
        private String reportFile = "output/self_improving_report.md";
        /** Output format. */
        private String format = "markdown";

        public String getReportFile() { return reportFile; }
        public void setReportFile(String reportFile) { this.reportFile = reportFile; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
    }

    public static class PlanningConfig {
        /** Max capability gaps the reviewer can flag per review. */
        private int maxCapabilityGapsPerReview = 1;
        /** Max next-commands the reviewer can suggest per review. */
        private int maxNextCommandsPerReview = 5;

        public int getMaxCapabilityGapsPerReview() { return maxCapabilityGapsPerReview; }
        public void setMaxCapabilityGapsPerReview(int v) { this.maxCapabilityGapsPerReview = v; }
        public int getMaxNextCommandsPerReview() { return maxNextCommandsPerReview; }
        public void setMaxNextCommandsPerReview(int v) { this.maxNextCommandsPerReview = v; }
    }

    /**
     * A known-good API endpoint that the planner can recommend to agents.
     * Prevents the LLM from hallucinating URLs.
     */
    public static class ApiEndpoint {
        private String name;
        private String url;
        private String description;

        public ApiEndpoint() {}
        public ApiEndpoint(String name, String url, String description) {
            this.name = name; this.url = url; this.description = description;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    // ==================== Top-Level Getters/Setters ====================

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }
    public int getMaxRpm() { return maxRpm; }
    public void setMaxRpm(int maxRpm) { this.maxRpm = maxRpm; }
    public AgentConfig getAgents() { return agents; }
    public void setAgents(AgentConfig agents) { this.agents = agents; }
    public TaskConfig getTasks() { return tasks; }
    public void setTasks(TaskConfig tasks) { this.tasks = tasks; }
    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
    public List<ApiEndpoint> getKnownEndpoints() { return knownEndpoints; }
    public void setKnownEndpoints(List<ApiEndpoint> knownEndpoints) { this.knownEndpoints = knownEndpoints; }
    public List<String> getScrapeSafeUrls() { return scrapeSafeUrls; }
    public void setScrapeSafeUrls(List<String> scrapeSafeUrls) { this.scrapeSafeUrls = scrapeSafeUrls; }
    public OutputConfig getOutput() { return output; }
    public void setOutput(OutputConfig output) { this.output = output; }
    public PlanningConfig getPlanning() { return planning; }
    public void setPlanning(PlanningConfig planning) { this.planning = planning; }
}
