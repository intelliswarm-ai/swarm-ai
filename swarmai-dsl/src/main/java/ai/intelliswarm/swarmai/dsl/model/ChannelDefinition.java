package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for a state channel with merge semantics.
 *
 * <pre>{@code
 * round:
 *   type: counter
 * messages:
 *   type: appender
 * status:
 *   type: lastWriteWins
 *   default: "PENDING"
 * }</pre>
 *
 * Supported types: lastWriteWins, appender, counter, stringAppender
 */
public class ChannelDefinition {

    private String type = "lastWriteWins";

    @JsonProperty("default")
    private Object defaultValue;

    // --- Getters & Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Object getDefaultValue() { return defaultValue; }
    public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }
}
