package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for an agent.
 *
 * <pre>{@code
 * researcher:
 *   role: "Senior Researcher"
 *   goal: "Find comprehensive information on the given topic"
 *   backstory: "You are an expert researcher with 20 years of experience"
 *   model: "anthropic/claude-sonnet-4-20250514"
 *   maxTurns: 3
 *   temperature: 0.7
 *   verbose: true
 *   allowDelegation: false
 *   maxExecutionTime: 300
 *   permissionMode: READ_ONLY
 *   tools:
 *     - web-search
 *     - file-read
 * }</pre>
 */
public class AgentDefinition {

    private String role;
    private String goal;
    private String backstory;
    private String model;

    @JsonProperty("maxTurns")
    private Integer maxTurns;

    private Double temperature;
    private boolean verbose = false;

    @JsonProperty("allowDelegation")
    private boolean allowDelegation = false;

    @JsonProperty("maxExecutionTime")
    private Integer maxExecutionTime;

    @JsonProperty("maxIter")
    private Integer maxIter;

    @JsonProperty("maxRpm")
    private Integer maxRpm;

    @JsonProperty("permissionMode")
    private String permissionMode;

    private CompactionConfigDefinition compaction;

    @JsonProperty("toolHooks")
    private List<ToolHookDefinition> toolHooks = new ArrayList<>();

    private boolean memory = false;

    private boolean knowledge = false;

    private List<String> tools = new ArrayList<>();

    // --- Getters & Setters ---

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getBackstory() { return backstory; }
    public void setBackstory(String backstory) { this.backstory = backstory; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getMaxTurns() { return maxTurns; }
    public void setMaxTurns(Integer maxTurns) { this.maxTurns = maxTurns; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }

    public boolean isAllowDelegation() { return allowDelegation; }
    public void setAllowDelegation(boolean allowDelegation) { this.allowDelegation = allowDelegation; }

    public Integer getMaxExecutionTime() { return maxExecutionTime; }
    public void setMaxExecutionTime(Integer maxExecutionTime) { this.maxExecutionTime = maxExecutionTime; }

    public Integer getMaxIter() { return maxIter; }
    public void setMaxIter(Integer maxIter) { this.maxIter = maxIter; }

    public Integer getMaxRpm() { return maxRpm; }
    public void setMaxRpm(Integer maxRpm) { this.maxRpm = maxRpm; }

    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }

    public CompactionConfigDefinition getCompaction() { return compaction; }
    public void setCompaction(CompactionConfigDefinition compaction) { this.compaction = compaction; }

    public List<ToolHookDefinition> getToolHooks() { return toolHooks; }
    public void setToolHooks(List<ToolHookDefinition> toolHooks) { this.toolHooks = toolHooks; }

    public boolean isMemory() { return memory; }
    public void setMemory(boolean memory) { this.memory = memory; }

    public boolean isKnowledge() { return knowledge; }
    public void setKnowledge(boolean knowledge) { this.knowledge = knowledge; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }
}
