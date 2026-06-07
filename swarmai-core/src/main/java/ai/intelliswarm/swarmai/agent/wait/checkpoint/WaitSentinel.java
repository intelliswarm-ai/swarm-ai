package ai.intelliswarm.swarmai.agent.wait.checkpoint;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker token convention for cross-session pause-resume. The
 * {@link CheckpointingWaitForSignalTool} returns a string containing this
 * sentinel to signal "the agent has paused; do not block, persist instead."
 *
 * <p>Format: {@code WAITING_FOR_SIGNAL:wait_<id>}. The system prompt instructs
 * the LLM to forward this sentinel verbatim in its response so the caller-side
 * {@link PauseDetector} can recognise it and trigger persistence.
 *
 * <p>Public so harness code, examples, and integration tests can build/parse
 * the same token without depending on internal helpers.
 */
public final class WaitSentinel {

    /** Stable prefix of the sentinel token. Public so prompts can reference it. */
    public static final String PREFIX = "WAITING_FOR_SIGNAL:";

    /** Match {@code WAITING_FOR_SIGNAL:wait_xxxx} anywhere in a string. */
    private static final Pattern SENTINEL_PATTERN =
            Pattern.compile(Pattern.quote(PREFIX) + "(wait_[A-Za-z0-9]+)");

    private WaitSentinel() {}

    /** Build the sentinel token for the given wait id. */
    public static String forWaitId(String waitId) {
        Objects.requireNonNull(waitId, "waitId");
        if (waitId.isBlank()) throw new IllegalArgumentException("waitId must not be blank");
        return PREFIX + waitId;
    }

    /**
     * Try to extract a wait id from a string. Returns the FIRST sentinel
     * match — if the LLM accidentally includes multiple, only the first wins.
     */
    public static Optional<String> parseWaitId(String text) {
        if (text == null) return Optional.empty();
        Matcher m = SENTINEL_PATTERN.matcher(text);
        if (m.find()) return Optional.of(m.group(1));
        return Optional.empty();
    }

    /** True if {@code text} contains a sentinel token. */
    public static boolean indicatesPause(String text) {
        return parseWaitId(text).isPresent();
    }
}
