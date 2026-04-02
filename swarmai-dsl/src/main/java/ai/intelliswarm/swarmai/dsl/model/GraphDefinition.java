package ai.intelliswarm.swarmai.dsl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * YAML definition for a graph-based workflow with nodes and edges.
 *
 * <pre>{@code
 * graph:
 *   nodes:
 *     proponent:
 *       agent: proponent-agent
 *       task: "Argue FOR the proposition"
 *     judge:
 *       agent: judge-agent
 *       task: "Declare winner"
 *   edges:
 *     - from: START
 *       to: proponent
 *     - from: judge
 *       to: END
 * }</pre>
 */
public class GraphDefinition {

    private LinkedHashMap<String, GraphNodeDefinition> nodes = new LinkedHashMap<>();

    private List<EdgeDefinition> edges = new ArrayList<>();

    // --- Getters & Setters ---

    public LinkedHashMap<String, GraphNodeDefinition> getNodes() { return nodes; }
    public void setNodes(LinkedHashMap<String, GraphNodeDefinition> nodes) { this.nodes = nodes; }

    public List<EdgeDefinition> getEdges() { return edges; }
    public void setEdges(List<EdgeDefinition> edges) { this.edges = edges; }
}
