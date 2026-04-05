package ai.intelliswarm.swarmai.dsl.model;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML definition for knowledge base configuration.
 *
 * <pre>{@code
 * knowledge:
 *   enabled: true
 *   provider: in-memory
 *   sources:
 *     - sourceId: "security-policies"
 *       content: "OWASP Top 10 security guidelines..."
 *     - sourceId: "coding-standards"
 *       content: "Java coding standards..."
 * }</pre>
 */
public class KnowledgeDefinition {

    private boolean enabled = true;
    private String provider = "in-memory";
    private List<KnowledgeSourceDefinition> sources = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<KnowledgeSourceDefinition> getSources() { return sources; }
    public void setSources(List<KnowledgeSourceDefinition> sources) { this.sources = sources; }
}
