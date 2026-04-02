package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for a node in a graph workflow.
 * Each node binds an agent to a task description.
 *
 * <pre>{@code
 * proponent:
 *   agent: proponent-agent
 *   task: "Argue FOR the proposition"
 *   expectedOutput: "A persuasive argument"
 * }</pre>
 */
public class GraphNodeDefinition {

    private String agent;

    private String task;

    @JsonProperty("expectedOutput")
    private String expectedOutput;

    // --- Getters & Setters ---

    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
}
