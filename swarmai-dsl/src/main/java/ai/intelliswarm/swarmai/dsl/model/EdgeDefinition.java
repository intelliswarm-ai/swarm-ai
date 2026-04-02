package ai.intelliswarm.swarmai.dsl.model;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for an edge in a graph workflow.
 * Supports both static edges and conditional routing.
 *
 * <pre>{@code
 * # Static edge
 * - from: START
 *   to: proponent
 *
 * # Conditional edge
 * - from: opponent
 *   conditional:
 *     - when: "round < 3"
 *       to: proponent
 *     - default: judge
 * }</pre>
 */
public class EdgeDefinition {

    private String from;
    private String to;
    private List<ConditionalEdgeDefinition> conditional = new ArrayList<>();

    // --- Getters & Setters ---

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public List<ConditionalEdgeDefinition> getConditional() { return conditional; }
    public void setConditional(List<ConditionalEdgeDefinition> conditional) { this.conditional = conditional; }

    public boolean isConditional() {
        return conditional != null && !conditional.isEmpty();
    }
}
