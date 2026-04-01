package ai.intelliswarm.swarmai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks the conversation history across multiple turns in a reactive agent loop.
 * Accumulates {@link ConversationTurn} records and provides aggregate statistics.
 * Supports auto-compaction: older turns can be summarized into a compact context
 * while recent turns are preserved verbatim.
 */
public class AgentConversation {

    private static final String CONTINUE_MARKER = "<CONTINUE>";
    private static final String DONE_MARKER = "<DONE>";

    private final List<ConversationTurn> turns = new ArrayList<>();

    /** Structured summary of compacted (removed) turns, or null if no compaction has occurred. */
    private String compactedContext;

    /** Total number of turns removed by compaction. */
    private int compactedTurnCount;

    /** Prompt tokens from compacted turns (tracked for accurate cumulative totals). */
    private long compactedPromptTokens;

    /** Completion tokens from compacted turns. */
    private long compactedCompletionTokens;

    /**
     * Records a completed turn.
     */
    public void addTurn(ConversationTurn turn) {
        turns.add(turn);
    }

    /**
     * Returns an unmodifiable view of active (non-compacted) turns.
     */
    public List<ConversationTurn> getTurns() {
        return Collections.unmodifiableList(turns);
    }

    /**
     * Returns the total number of turns including compacted ones.
     */
    public int getTurnCount() {
        return compactedTurnCount + turns.size();
    }

    /**
     * Returns the number of active (non-compacted) turns.
     */
    public int getActiveTurnCount() {
        return turns.size();
    }

    /**
     * Returns the final response with continuation markers stripped.
     * If no turns exist, returns null.
     */
    public String getFinalResponse() {
        if (turns.isEmpty()) {
            return null;
        }
        return stripMarkers(turns.get(turns.size() - 1).response());
    }

    /**
     * Returns the full conversation as a formatted context string,
     * suitable for injection into a follow-up prompt.
     * If compaction has occurred, the compacted summary is included first.
     */
    public String toContextString() {
        StringBuilder sb = new StringBuilder();

        if (compactedContext != null) {
            sb.append(ConversationCompactor.buildContinuationContext(
                    compactedContext, !turns.isEmpty()));
            sb.append("\n\n");
        }

        for (ConversationTurn turn : turns) {
            sb.append("--- Reasoning turn ").append(turn.turnIndex() + 1).append(" ---\n");
            sb.append(stripMarkers(turn.response()));
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns cumulative prompt tokens across all turns (including compacted ones).
     */
    public long getCumulativePromptTokens() {
        return compactedPromptTokens
                + turns.stream().mapToLong(ConversationTurn::promptTokens).sum();
    }

    /**
     * Returns cumulative completion tokens across all turns (including compacted ones).
     */
    public long getCumulativeCompletionTokens() {
        return compactedCompletionTokens
                + turns.stream().mapToLong(ConversationTurn::completionTokens).sum();
    }

    /**
     * Returns cumulative total tokens across all turns (including compacted ones).
     */
    public long getCumulativeTotalTokens() {
        return getCumulativePromptTokens() + getCumulativeCompletionTokens();
    }

    /**
     * Checks whether the latest turn's response contains a continuation signal.
     */
    public boolean lastTurnRequestsContinuation() {
        if (turns.isEmpty()) {
            return false;
        }
        String response = turns.get(turns.size() - 1).response();
        return response != null && response.contains(CONTINUE_MARKER);
    }

    /**
     * Returns true if this conversation has been compacted at least once.
     */
    public boolean hasBeenCompacted() {
        return compactedContext != null;
    }

    /**
     * Returns the number of turns that have been compacted away.
     */
    public int getCompactedTurnCount() {
        return compactedTurnCount;
    }

    /**
     * Applies a compaction result: removes old turns, stores their summary,
     * and preserves the specified recent turns.
     */
    public void applyCompaction(CompactionResult result) {
        if (!result.wasCompacted()) {
            return;
        }

        // Accumulate compacted token counts
        this.compactedPromptTokens += result.compactedPromptTokens();
        this.compactedCompletionTokens += result.compactedCompletionTokens();
        this.compactedTurnCount += result.removedTurnCount();

        // Merge summaries if there was a prior compaction
        if (this.compactedContext != null) {
            this.compactedContext = this.compactedContext + "\n\n" + result.summary();
        } else {
            this.compactedContext = result.summary();
        }

        // Replace turns with only the preserved ones
        this.turns.clear();
        this.turns.addAll(result.preservedTurns());
    }

    /**
     * Strips continuation/done markers from a response.
     */
    public static String stripMarkers(String response) {
        if (response == null) {
            return null;
        }
        return response.replace(CONTINUE_MARKER, "").replace(DONE_MARKER, "").trim();
    }

    /**
     * Checks if a response contains the continuation marker.
     */
    public static boolean shouldContinue(String response) {
        return response != null && response.contains(CONTINUE_MARKER);
    }
}
