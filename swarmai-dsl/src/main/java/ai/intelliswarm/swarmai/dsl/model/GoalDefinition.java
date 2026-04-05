package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for a distributed goal.
 *
 * <pre>{@code
 * goal:
 *   objective: "Audit 10,000 repositories for critical vulnerabilities"
 *   successCriteria:
 *     - "All repositories scanned"
 *     - "Critical vulnerabilities patched"
 *     - "Coverage >= 95%"
 *   deadline: "2026-04-30T00:00:00Z"
 * }</pre>
 */
public class GoalDefinition {

    private String objective;

    @JsonProperty("successCriteria")
    private List<String> successCriteria = new ArrayList<>();

    private String deadline;

    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }

    public List<String> getSuccessCriteria() { return successCriteria; }
    public void setSuccessCriteria(List<String> successCriteria) { this.successCriteria = successCriteria; }

    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
}
