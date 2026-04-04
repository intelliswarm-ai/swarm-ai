package ai.intelliswarm.swarmai.exception;

/**
 * Base exception for all SwarmAI framework errors.
 * Carries structured context (swarmId, correlationId) for production debugging.
 */
public class SwarmException extends RuntimeException {

    private final String swarmId;
    private final String correlationId;

    public SwarmException(String message) {
        this(message, null, null, null);
    }

    public SwarmException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public SwarmException(String message, Throwable cause, String swarmId, String correlationId) {
        super(message, cause);
        this.swarmId = swarmId;
        this.correlationId = correlationId;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
