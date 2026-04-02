package ai.intelliswarm.swarmai.dsl.parser;

/**
 * Thrown when a YAML workflow definition is invalid or cannot be parsed.
 */
public class SwarmParseException extends RuntimeException {

    public SwarmParseException(String message) {
        super(message);
    }

    public SwarmParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
