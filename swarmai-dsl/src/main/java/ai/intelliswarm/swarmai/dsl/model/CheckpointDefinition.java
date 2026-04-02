package ai.intelliswarm.swarmai.dsl.model;

/**
 * YAML definition for checkpoint configuration.
 *
 * <pre>{@code
 * checkpoint:
 *   enabled: true
 *   provider: in-memory
 * }</pre>
 */
public class CheckpointDefinition {

    private boolean enabled = false;
    private String provider = "in-memory";

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
}
