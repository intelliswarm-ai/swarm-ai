package ai.intelliswarm.swarmai.dsl.model;

/**
 * YAML definition for memory configuration.
 *
 * <pre>{@code
 * memory:
 *   enabled: true
 *   provider: in-memory
 *   maxEntries: 10000
 * }</pre>
 */
public class MemoryDefinition {

    private boolean enabled = true;
    private String provider = "in-memory";
    private int maxEntries = 10000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
}
