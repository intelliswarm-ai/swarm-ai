package ai.intelliswarm.swarmai.state;

/**
 * Describes a specific validation error found during swarm compilation.
 * Uses a sealed hierarchy so callers can exhaustively pattern-match on error types.
 */
public sealed interface CompilationError {

    /**
     * Human-readable description of the error.
     */
    String message();

    // ========================================
    // Error subtypes
    // ========================================

    record MissingManagerAgent(String processType) implements CompilationError {
        @Override
        public String message() {
            return "Manager agent is required for " + processType + " process";
        }
    }

    record NoAgents() implements CompilationError {
        @Override
        public String message() {
            return "At least one agent is required";
        }
    }

    record NoTasks() implements CompilationError {
        @Override
        public String message() {
            return "At least one task is required";
        }
    }

    record CyclicDependency(String taskId, String dependsOn) implements CompilationError {
        @Override
        public String message() {
            return "Cyclic dependency detected: task '" + taskId + "' depends on '" + dependsOn + "'";
        }
    }

    record InvalidDependency(String taskId, String missingDependency) implements CompilationError {
        @Override
        public String message() {
            return "Task '" + taskId + "' depends on non-existent task '" + missingDependency + "'";
        }
    }

    record TaskWithoutAgent(String taskId) implements CompilationError {
        @Override
        public String message() {
            return "Task '" + taskId + "' has no assigned agent";
        }
    }

    record InvalidConfiguration(String detail) implements CompilationError {
        @Override
        public String message() {
            return "Invalid configuration: " + detail;
        }
    }
}
