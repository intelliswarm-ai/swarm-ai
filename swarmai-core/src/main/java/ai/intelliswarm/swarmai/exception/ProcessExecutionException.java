package ai.intelliswarm.swarmai.exception;

import ai.intelliswarm.swarmai.process.ProcessType;

/**
 * Thrown when a process (sequential, parallel, etc.) fails during execution.
 */
public class ProcessExecutionException extends SwarmException {

    private final ProcessType processType;

    public ProcessExecutionException(String message, Throwable cause, ProcessType processType,
                                      String swarmId, String correlationId) {
        super(message, cause, swarmId, correlationId);
        this.processType = processType;
    }

    public ProcessExecutionException(String message, Throwable cause, ProcessType processType) {
        this(message, cause, processType, null, null);
    }

    public ProcessType getProcessType() {
        return processType;
    }
}
