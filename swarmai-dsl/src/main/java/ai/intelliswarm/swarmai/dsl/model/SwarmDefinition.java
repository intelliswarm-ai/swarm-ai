package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root YAML definition for a swarm workflow.
 *
 * <pre>{@code
 * swarm:
 *   name: "Research Pipeline"
 *   process: SEQUENTIAL
 *   verbose: true
 *   language: en
 *   maxRpm: 30
 *   tenantId: "tenant-1"
 *
 *   budget:
 *     maxTokens: 100000
 *     ...
 *
 *   agents:
 *     researcher:
 *       role: "Researcher"
 *       ...
 *
 *   tasks:
 *     research:
 *       description: "Research {{topic}}"
 *       ...
 *
 *   governance:
 *     approvalGates:
 *       - name: "Review Gate"
 *         ...
 *
 *   config:
 *     maxIterations: 5
 *     ...
 * }</pre>
 */
public class SwarmDefinition {

    private String name;

    private String process = "SEQUENTIAL";

    private boolean verbose = false;

    private String language = "en";

    @JsonProperty("maxRpm")
    private Integer maxRpm;

    @JsonProperty("tenantId")
    private String tenantId;

    @JsonProperty("managerAgent")
    private String managerAgent;

    private BudgetDefinition budget;

    private LinkedHashMap<String, AgentDefinition> agents = new LinkedHashMap<>();

    private LinkedHashMap<String, TaskDefinition> tasks = new LinkedHashMap<>();

    private GovernanceDefinition governance;

    private Map<String, Object> config = new LinkedHashMap<>();

    // --- Getters & Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProcess() { return process; }
    public void setProcess(String process) { this.process = process; }

    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean verbose) { this.verbose = verbose; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public Integer getMaxRpm() { return maxRpm; }
    public void setMaxRpm(Integer maxRpm) { this.maxRpm = maxRpm; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getManagerAgent() { return managerAgent; }
    public void setManagerAgent(String managerAgent) { this.managerAgent = managerAgent; }

    public BudgetDefinition getBudget() { return budget; }
    public void setBudget(BudgetDefinition budget) { this.budget = budget; }

    public LinkedHashMap<String, AgentDefinition> getAgents() { return agents; }
    public void setAgents(LinkedHashMap<String, AgentDefinition> agents) { this.agents = agents; }

    public LinkedHashMap<String, TaskDefinition> getTasks() { return tasks; }
    public void setTasks(LinkedHashMap<String, TaskDefinition> tasks) { this.tasks = tasks; }

    public GovernanceDefinition getGovernance() { return governance; }
    public void setGovernance(GovernanceDefinition governance) { this.governance = governance; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
