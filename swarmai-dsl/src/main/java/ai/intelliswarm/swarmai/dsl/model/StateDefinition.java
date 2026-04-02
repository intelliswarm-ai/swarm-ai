package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;

/**
 * YAML definition for graph workflow state schema.
 *
 * <pre>{@code
 * state:
 *   allowUndeclaredKeys: true
 *   channels:
 *     round:
 *       type: counter
 *     debate_log:
 *       type: stringAppender
 *     verdict:
 *       type: lastWriteWins
 * }</pre>
 */
public class StateDefinition {

    @JsonProperty("allowUndeclaredKeys")
    private boolean allowUndeclaredKeys = true;

    private LinkedHashMap<String, ChannelDefinition> channels = new LinkedHashMap<>();

    // --- Getters & Setters ---

    public boolean isAllowUndeclaredKeys() { return allowUndeclaredKeys; }
    public void setAllowUndeclaredKeys(boolean allowUndeclaredKeys) { this.allowUndeclaredKeys = allowUndeclaredKeys; }

    public LinkedHashMap<String, ChannelDefinition> getChannels() { return channels; }
    public void setChannels(LinkedHashMap<String, ChannelDefinition> channels) { this.channels = channels; }
}
