package ai.intelliswarm.swarmai.state;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The result of compiling a {@link SwarmGraph}. Either a successfully compiled
 * {@link CompiledSwarm}, or a list of {@link CompilationError}s describing why
 * compilation failed.
 *
 * <p>Usage:
 * <pre>{@code
 * CompilationResult result = graph.compile();
 * if (result.isSuccess()) {
 *     SwarmOutput output = result.compiled().kickoff(state);
 * } else {
 *     result.errors().forEach(e -> System.err.println(e.message()));
 * }
 * }</pre>
 */
public final class CompilationResult {

    private final CompiledSwarm compiledSwarm;
    private final List<CompilationError> errors;

    private CompilationResult(CompiledSwarm compiledSwarm, List<CompilationError> errors) {
        this.compiledSwarm = compiledSwarm;
        this.errors = errors;
    }

    /**
     * Creates a successful result.
     */
    static CompilationResult success(CompiledSwarm compiled) {
        Objects.requireNonNull(compiled, "CompiledSwarm cannot be null");
        return new CompilationResult(compiled, List.of());
    }

    /**
     * Creates a failed result with the given errors.
     */
    static CompilationResult failure(List<CompilationError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("Failure result must have at least one error");
        }
        return new CompilationResult(null, Collections.unmodifiableList(errors));
    }

    /**
     * Returns true if compilation succeeded.
     */
    public boolean isSuccess() {
        return compiledSwarm != null;
    }

    /**
     * Returns the compiled swarm. Throws if compilation failed.
     */
    public CompiledSwarm compiled() {
        if (compiledSwarm == null) {
            throw new IllegalStateException(
                    "Compilation failed with " + errors.size() + " error(s): " +
                    errors.stream().map(CompilationError::message)
                            .reduce((a, b) -> a + "; " + b).orElse("unknown"));
        }
        return compiledSwarm;
    }

    /**
     * Returns compilation errors (empty if successful).
     */
    public List<CompilationError> errors() {
        return errors;
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return "CompilationResult{success}";
        }
        return "CompilationResult{errors=" + errors.size() + ": " +
                errors.stream().map(CompilationError::message)
                        .reduce((a, b) -> a + "; " + b).orElse("") + "}";
    }
}
