package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for agent conversation compaction configuration.
 *
 * <pre>{@code
 * compaction:
 *   enabled: true
 *   preserveRecentTurns: 4
 *   thresholdTokens: 80000
 * }</pre>
 */
public class CompactionConfigDefinition {

    private boolean enabled = true;

    @JsonProperty("preserveRecentTurns")
    private Integer preserveRecentTurns;

    @JsonProperty("thresholdTokens")
    private Long thresholdTokens;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Integer getPreserveRecentTurns() { return preserveRecentTurns; }
    public void setPreserveRecentTurns(Integer preserveRecentTurns) { this.preserveRecentTurns = preserveRecentTurns; }

    public Long getThresholdTokens() { return thresholdTokens; }
    public void setThresholdTokens(Long thresholdTokens) { this.thresholdTokens = thresholdTokens; }
}
