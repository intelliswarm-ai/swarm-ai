package ai.intelliswarm.swarmai.agent;

import java.util.List;

/**
 * Result of a conversation compaction operation.
 *
 * @param summary                  structured summary of compacted turns (empty if no compaction)
 * @param removedTurnCount         number of turns that were compacted
 * @param compactedPromptTokens    prompt tokens from the removed turns
 * @param compactedCompletionTokens completion tokens from the removed turns
 * @param preservedTurns           turns kept verbatim after compaction
 */
public record CompactionResult(
        String summary,
        int removedTurnCount,
        long compactedPromptTokens,
        long compactedCompletionTokens,
        List<ConversationTurn> preservedTurns
) {
    /**
     * Returns true if compaction occurred.
     */
    public boolean wasCompacted() {
        return removedTurnCount > 0;
    }

    /**
     * Returns a no-op result (no compaction performed).
     */
    public static CompactionResult none() {
        return new CompactionResult("", 0, 0L, 0L, List.of());
    }
}
