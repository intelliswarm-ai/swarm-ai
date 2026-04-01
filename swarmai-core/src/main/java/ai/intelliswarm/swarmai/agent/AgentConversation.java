package ai.intelliswarm.swarmai.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks the conversation history across multiple turns in a reactive agent loop.
 * Accumulates {@link ConversationTurn} records and provides aggregate statistics.
 */
public class AgentConversation {

    private static final String CONTINUE_MARKER = "<CONTINUE>";
    private static final String DONE_MARKER = "<DONE>";

    private final List<ConversationTurn> turns = new ArrayList<>();

    /**
     * Records a completed turn.
     */
    public void addTurn(ConversationTurn turn) {
        turns.add(turn);
    }

    /**
     * Returns an unmodifiable view of all turns.
     */
    public List<ConversationTurn> getTurns() {
        return Collections.unmodifiableList(turns);
    }

    /**
     * Returns the number of turns completed so far.
     */
    public int getTurnCount() {
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
     */
    public String toContextString() {
        StringBuilder sb = new StringBuilder();
        for (ConversationTurn turn : turns) {
            sb.append("--- Reasoning turn ").append(turn.turnIndex() + 1).append(" ---\n");
            sb.append(stripMarkers(turn.response()));
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Returns cumulative prompt tokens across all turns.
     */
    public long getCumulativePromptTokens() {
        return turns.stream().mapToLong(ConversationTurn::promptTokens).sum();
    }

    /**
     * Returns cumulative completion tokens across all turns.
     */
    public long getCumulativeCompletionTokens() {
        return turns.stream().mapToLong(ConversationTurn::completionTokens).sum();
    }

    /**
     * Returns cumulative total tokens across all turns.
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
