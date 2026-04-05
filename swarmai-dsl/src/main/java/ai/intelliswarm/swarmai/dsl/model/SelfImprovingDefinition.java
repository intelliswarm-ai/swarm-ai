package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for self-improvement configuration.
 *
 * <pre>{@code
 * selfImproving:
 *   enabled: true
 *   reservePercent: 0.10
 *   skillDirectory: "output/skills"
 *   maxSkillsPerAgent: 20
 *   skillSimilarityThreshold: 0.35
 *   skillPersistence: true
 *   policy: LEARNING
 *   linucbAlpha: 1.0
 *   coldStartDecisions: 50
 * }</pre>
 */
public class SelfImprovingDefinition {

    private boolean enabled = true;

    @JsonProperty("reservePercent")
    private double reservePercent = 0.10;

    @JsonProperty("skillDirectory")
    private String skillDirectory = "output/skills";

    @JsonProperty("maxSkillsPerAgent")
    private int maxSkillsPerAgent = 20;

    @JsonProperty("skillSimilarityThreshold")
    private double skillSimilarityThreshold = 0.35;

    @JsonProperty("skillPersistence")
    private boolean skillPersistence = true;

    private String policy = "HEURISTIC";

    @JsonProperty("linucbAlpha")
    private double linucbAlpha = 1.0;

    @JsonProperty("coldStartDecisions")
    private int coldStartDecisions = 50;

    @JsonProperty("experienceBufferCapacity")
    private int experienceBufferCapacity = 10000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getReservePercent() { return reservePercent; }
    public void setReservePercent(double reservePercent) { this.reservePercent = reservePercent; }

    public String getSkillDirectory() { return skillDirectory; }
    public void setSkillDirectory(String skillDirectory) { this.skillDirectory = skillDirectory; }

    public int getMaxSkillsPerAgent() { return maxSkillsPerAgent; }
    public void setMaxSkillsPerAgent(int maxSkillsPerAgent) { this.maxSkillsPerAgent = maxSkillsPerAgent; }

    public double getSkillSimilarityThreshold() { return skillSimilarityThreshold; }
    public void setSkillSimilarityThreshold(double t) { this.skillSimilarityThreshold = t; }

    public boolean isSkillPersistence() { return skillPersistence; }
    public void setSkillPersistence(boolean p) { this.skillPersistence = p; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public double getLinucbAlpha() { return linucbAlpha; }
    public void setLinucbAlpha(double linucbAlpha) { this.linucbAlpha = linucbAlpha; }

    public int getColdStartDecisions() { return coldStartDecisions; }
    public void setColdStartDecisions(int coldStartDecisions) { this.coldStartDecisions = coldStartDecisions; }

    public int getExperienceBufferCapacity() { return experienceBufferCapacity; }
    public void setExperienceBufferCapacity(int cap) { this.experienceBufferCapacity = cap; }
}
