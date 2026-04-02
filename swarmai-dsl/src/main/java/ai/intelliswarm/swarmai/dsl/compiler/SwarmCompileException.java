package ai.intelliswarm.swarmai.dsl.compiler;

/**
 * Thrown when a SwarmDefinition cannot be compiled into a Swarm instance.
 */
public class SwarmCompileException extends RuntimeException {

    public SwarmCompileException(String message) {
        super(message);
    }

    public SwarmCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
