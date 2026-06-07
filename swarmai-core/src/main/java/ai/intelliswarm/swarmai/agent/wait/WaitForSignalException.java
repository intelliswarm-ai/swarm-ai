package ai.intelliswarm.swarmai.agent.wait;

/**
 * Thrown by {@link SignalDispatcher#awaitSignal} when the wait does not
 * complete normally. The three subclasses correspond to the three terminal
 * non-COMPLETED statuses.
 */
public class WaitForSignalException extends RuntimeException {

    private final String waitId;

    public WaitForSignalException(String waitId, String message) {
        super(message);
        this.waitId = waitId;
    }

    public String waitId() {
        return waitId;
    }

    /** The wait's TTL elapsed before any signal arrived. */
    public static class Timeout extends WaitForSignalException {
        public Timeout(String waitId, String message) {
            super(waitId, message);
        }
    }

    /** The wait was cancelled by the harness/operator before any signal arrived. */
    public static class Cancelled extends WaitForSignalException {
        public Cancelled(String waitId, String message) {
            super(waitId, message);
        }
    }

    /** {@link SignalDispatcher#awaitSignal} was called with an unknown waitId. */
    public static class UnknownWait extends WaitForSignalException {
        public UnknownWait(String waitId) {
            super(waitId, "no wait registered with id '" + waitId + "'");
        }
    }
}
