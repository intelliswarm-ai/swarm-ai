package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for a task.
 *
 * <pre>{@code
 * research:
 *   description: "Research the topic: {{topic}}"
 *   expectedOutput: "A comprehensive research report"
 *   agent: researcher
 *   dependsOn:
 *     - gather-data
 *   outputFormat: MARKDOWN
 *   outputFile: "output/research.md"
 *   asyncExecution: false
 *   maxExecutionTime: 600
 *   tools:
 *     - web-search
 * }</pre>
 */
public class TaskDefinition {

    private String description;

    @JsonProperty("expectedOutput")
    private String expectedOutput;

    private String agent;

    @JsonProperty("dependsOn")
    private List<String> dependsOn = new ArrayList<>();

    @JsonProperty("outputFormat")
    private String outputFormat;

    @JsonProperty("outputFile")
    private String outputFile;

    @JsonProperty("asyncExecution")
    private boolean asyncExecution = false;

    @JsonProperty("maxExecutionTime")
    private Integer maxExecutionTime;

    private List<String> tools = new ArrayList<>();

    // --- Getters & Setters ---

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getOutputFile() { return outputFile; }
    public void setOutputFile(String outputFile) { this.outputFile = outputFile; }

    public boolean isAsyncExecution() { return asyncExecution; }
    public void setAsyncExecution(boolean asyncExecution) { this.asyncExecution = asyncExecution; }

    public Integer getMaxExecutionTime() { return maxExecutionTime; }
    public void setMaxExecutionTime(Integer maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
}
