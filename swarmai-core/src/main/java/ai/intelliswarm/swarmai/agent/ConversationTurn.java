package ai.intelliswarm.swarmai.agent;

/**
 * A single turn in a multi-turn agent conversation.
 * Records the agent's response, token usage, and timing for each reasoning step.
 *
 * @param turnIndex     zero-based turn number
 * @param response      the LLM response text for this turn
 * @param promptTokens  tokens consumed by the prompt in this turn
 * @param completionTokens tokens generated in this turn
 * @param timestampMs   epoch millis when this turn completed
 */
public record ConversationTurn(
        int turnIndex,
        String response,
        long promptTokens,
        long completionTokens,
        long timestampMs
) {
    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
