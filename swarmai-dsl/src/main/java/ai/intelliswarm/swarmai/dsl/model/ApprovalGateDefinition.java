package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for an approval gate.
 *
 * <pre>{@code
 * - name: "Quality Review"
 *   description: "Review task output before proceeding"
 *   trigger: AFTER_TASK
 *   timeoutMinutes: 30
 * }</pre>
 */
public class ApprovalGateDefinition {

    private String name;
    private String description;
    private String trigger;

    @JsonProperty("timeoutMinutes")
    private Integer timeoutMinutes;

    // --- Getters & Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    public Integer getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(Integer timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
}
