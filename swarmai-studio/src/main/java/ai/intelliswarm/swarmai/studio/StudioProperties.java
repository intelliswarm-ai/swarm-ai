package ai.intelliswarm.swarmai.studio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the SwarmAI Studio Web UI.
 * Studio provides runtime inspection and visualization of workflows,
 * for dynamic inspection of workflow processes.
 *
 * Bound via @EnableConfigurationProperties in StudioAutoConfiguration.
 */
@ConfigurationProperties(prefix = "swarmai.studio")
public class StudioProperties {

    /**
     * Whether the Studio UI and API endpoints are enabled.
     */
    private boolean enabled = false;

    /**
     * Base path for serving the Studio UI.
     */
    private String basePath = "/studio";

    /**
     * Maximum number of concurrent SSE connections allowed.
     */
    private int maxSseConnections = 50;

    /**
     * Number of recent events to buffer for replay on new SSE connections.
     */
    private int eventBufferSize = 1000;

    // Getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public int getMaxSseConnections() {
        return maxSseConnections;
    }

    public void setMaxSseConnections(int maxSseConnections) {
        this.maxSseConnections = maxSseConnections;
    }

    public int getEventBufferSize() {
        return eventBufferSize;
    }

    public void setEventBufferSize(int eventBufferSize) {
        this.eventBufferSize = eventBufferSize;
    }
}
