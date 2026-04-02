package ai.intelliswarm.swarmai.dsl.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * YAML definition for a conditional edge branch.
 *
 * <pre>{@code
 * conditional:
 *   - when: "round < 3"
 *     to: proponent
 *   - when: "score >= 80"
 *     to: finalize
 *   - default: judge       # fallback when no condition matches
 * }</pre>
 */
public class ConditionalEdgeDefinition {

    private String when;
    private String to;

    @JsonProperty("default")
    private String defaultTo;

    // --- Getters & Setters ---

    public String getWhen() { return when; }
    public void setWhen(String when) { this.when = when; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getDefaultTo() { return defaultTo; }
    public void setDefaultTo(String defaultTo) { this.defaultTo = defaultTo; }

    public boolean isDefault() {
        return defaultTo != null;
    }

    /**
     * Returns the target node — either from 'to' (conditional) or 'default' (fallback).
     */
    public String target() {
        return isDefault() ? defaultTo : to;
    }
}
