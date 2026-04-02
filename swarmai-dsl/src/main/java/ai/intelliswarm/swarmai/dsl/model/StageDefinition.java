package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for a single stage in a COMPOSITE process pipeline.
 *
 * <pre>{@code
 * stages:
 *   - process: PARALLEL
 *   - process: HIERARCHICAL
 *     managerAgent: manager
 *   - process: ITERATIVE
 *     managerAgent: manager
 *     maxIterations: 3
 *     qualityCriteria: "Report must be comprehensive"
 * }</pre>
 */
public class StageDefinition {

    private String process;

    @JsonProperty("managerAgent")
    private String managerAgent;

    @JsonProperty("maxIterations")
    private Integer maxIterations;

    @JsonProperty("qualityCriteria")
    private String qualityCriteria;

    @JsonProperty("maxParallelAgents")
    private Integer maxParallelAgents;

    // --- Getters & Setters ---

    public String getProcess() { return process; }
    public void setProcess(String process) { this.process = process; }

    public String getManagerAgent() { return managerAgent; }
    public void setManagerAgent(String managerAgent) { this.managerAgent = managerAgent; }

    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }

    public String getQualityCriteria() { return qualityCriteria; }
    public void setQualityCriteria(String qualityCriteria) { this.qualityCriteria = qualityCriteria; }

    public Integer getMaxParallelAgents() { return maxParallelAgents; }
    public void setMaxParallelAgents(Integer maxParallelAgents) { this.maxParallelAgents = maxParallelAgents; }
}
