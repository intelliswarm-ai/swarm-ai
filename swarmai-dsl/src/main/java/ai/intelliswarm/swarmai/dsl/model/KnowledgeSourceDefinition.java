package ai.intelliswarm.swarmai.dsl.model;

/**
 * YAML definition for a knowledge source to be loaded into the knowledge base.
 *
 * <pre>{@code
 * knowledgeSources:
 *   - id: "arch-patterns"
 *     content: "Information about architecture patterns..."
 *   - id: "tool-integration"
 *     content: "How to integrate tools..."
 * }</pre>
 */
public class KnowledgeSourceDefinition {

    private String id;
    private String content;

    // --- Getters & Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
