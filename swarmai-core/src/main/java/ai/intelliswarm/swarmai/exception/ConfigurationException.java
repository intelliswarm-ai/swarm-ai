package ai.intelliswarm.swarmai.exception;

/**
 * Thrown when framework configuration is invalid.
 * Used for fail-fast validation at startup.
 */
public class ConfigurationException extends SwarmException {

    private final String property;

    public ConfigurationException(String message, String property) {
        super(message);
        this.property = property;
    }

    public ConfigurationException(String message) {
        this(message, null);
    }

    public String getProperty() {
        return property;
    }
}
