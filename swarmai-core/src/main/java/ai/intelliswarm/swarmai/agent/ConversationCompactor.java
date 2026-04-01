package ai.intelliswarm.swarmai.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compacts conversation history by summarizing older turns into a structured context block
 * while preserving recent turns verbatim. Uses heuristic extraction (no LLM call),
 * keeping compaction itself zero-cost in tokens.
 *
 * <p>The summary captures:
 * <ul>
 *   <li>Scope — how many turns were compacted and their token cost</li>
 *   <li>Key actions and decisions from each compacted turn</li>
 *   <li>Tool names mentioned in the conversation</li>
 *   <li>Pending work signals ({@code <CONTINUE>} turns)</li>
 *   <li>Key file paths referenced</li>
 * </ul>
 */
public class ConversationCompactor {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCompactor.class);

    private static final int MAX_EXCERPT_CHARS = 160;
    private static final int MAX_KEY_FILES = 8;
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("(?:^|\\s|[\"'`])(/[\\w./-]+\\.[a-zA-Z]{1,6})");
    private static final Pattern TOOL_CALL_PATTERN =
            Pattern.compile("(?:called|using|executed|invoked|tool[:\\s]+)\\s*[`\"']?(\\w+)[`\"']?", Pattern.CASE_INSENSITIVE);

    /**
     * Checks whether compaction should be triggered.
     */
    public static boolean shouldCompact(AgentConversation conversation, CompactionConfig config) {
        if (!config.enabled()) {
            return false;
        }
        return conversation.getTurnCount() > config.preserveRecentTurns()
                && conversation.getCumulativeTotalTokens() >= config.compactionThresholdTokens();
    }

    /**
     * Compacts the conversation: summarizes older turns and preserves recent ones.
     * Returns a {@link CompactionResult} with the summary and modified conversation.
     *
     * @param conversation the conversation to compact
     * @param config       compaction settings
     * @return result containing the summary and the number of turns removed
     */
    public static CompactionResult compact(AgentConversation conversation, CompactionConfig config) {
        if (!shouldCompact(conversation, config)) {
            return CompactionResult.none();
        }

        List<ConversationTurn> allTurns = conversation.getTurns();
        int keepFrom = Math.max(0, allTurns.size() - config.preserveRecentTurns());

        List<ConversationTurn> oldTurns = allTurns.subList(0, keepFrom);
        List<ConversationTurn> recentTurns = allTurns.subList(keepFrom, allTurns.size());

        if (oldTurns.isEmpty()) {
            return CompactionResult.none();
        }

        String summary = summarizeTurns(oldTurns);
        int removedCount = oldTurns.size();

        long compactedPromptTokens = oldTurns.stream().mapToLong(ConversationTurn::promptTokens).sum();
        long compactedCompletionTokens = oldTurns.stream().mapToLong(ConversationTurn::completionTokens).sum();

        logger.info("Compacted {} turns ({} tokens) into summary ({} chars), preserving {} recent turns",
                removedCount,
                compactedPromptTokens + compactedCompletionTokens,
                summary.length(),
                recentTurns.size());

        return new CompactionResult(
                summary,
                removedCount,
                compactedPromptTokens,
                compactedCompletionTokens,
                new ArrayList<>(recentTurns));
    }

    /**
     * Builds a structured summary from a list of old turns.
     */
    static String summarizeTurns(List<ConversationTurn> turns) {
        StringBuilder summary = new StringBuilder();

        // Scope
        long totalTokens = turns.stream().mapToLong(ConversationTurn::totalTokens).sum();
        summary.append("<summary>\n");
        summary.append("Conversation compacted: ").append(turns.size())
                .append(" earlier reasoning turns summarized (")
                .append(totalTokens).append(" tokens).\n");

        // Tool mentions
        Set<String> toolNames = extractToolNames(turns);
        if (!toolNames.isEmpty()) {
            summary.append("Tools used: ").append(String.join(", ", toolNames)).append(".\n");
        }

        // Key files
        Set<String> filePaths = extractFilePaths(turns);
        if (!filePaths.isEmpty()) {
            summary.append("Key files referenced: ").append(String.join(", ", filePaths)).append(".\n");
        }

        // Key actions timeline
        summary.append("Key actions:\n");
        for (ConversationTurn turn : turns) {
            String excerpt = extractExcerpt(turn.response());
            boolean continued = AgentConversation.shouldContinue(turn.response());
            summary.append("  - Turn ").append(turn.turnIndex() + 1).append(": ");
            summary.append(excerpt);
            if (continued) {
                summary.append(" [continued]");
            }
            summary.append("\n");
        }

        // Pending work (from the last compacted turn if it had CONTINUE)
        ConversationTurn lastCompacted = turns.get(turns.size() - 1);
        if (AgentConversation.shouldContinue(lastCompacted.response())) {
            String pendingExcerpt = extractPendingWork(lastCompacted.response());
            if (pendingExcerpt != null) {
                summary.append("Pending at compaction point: ").append(pendingExcerpt).append("\n");
            }
        }

        summary.append("</summary>");
        return summary.toString();
    }

    /**
     * Builds the continuation context that replaces compacted turns.
     */
    public static String buildContinuationContext(String summary, boolean hasRecentTurns) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("Earlier reasoning in this task has been compacted to save context space. ");
        ctx.append("The summary below covers the compacted portion.\n\n");
        ctx.append(summary);
        if (hasRecentTurns) {
            ctx.append("\n\nRecent reasoning turns are preserved verbatim below.");
        }
        ctx.append("\nContinue from where you left off. Do not recap the summary.");
        return ctx.toString();
    }

    // --- Extraction helpers ---

    private static String extractExcerpt(String response) {
        if (response == null || response.isBlank()) {
            return "[empty]";
        }
        String clean = AgentConversation.stripMarkers(response);
        // Take the first meaningful line or first N chars
        String firstLine = clean.lines().filter(l -> !l.isBlank()).findFirst().orElse(clean);
        if (firstLine.length() > MAX_EXCERPT_CHARS) {
            return firstLine.substring(0, MAX_EXCERPT_CHARS - 3) + "...";
        }
        return firstLine;
    }

    private static String extractPendingWork(String response) {
        if (response == null) return null;
        String clean = AgentConversation.stripMarkers(response);
        // Look for the last paragraph or sentence as "pending work"
        String[] paragraphs = clean.split("\n\n");
        if (paragraphs.length > 0) {
            String last = paragraphs[paragraphs.length - 1].trim();
            if (last.length() > MAX_EXCERPT_CHARS) {
                return last.substring(0, MAX_EXCERPT_CHARS - 3) + "...";
            }
            return last;
        }
        return null;
    }

    static Set<String> extractToolNames(List<ConversationTurn> turns) {
        Set<String> names = new LinkedHashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.response() == null) continue;
            Matcher m = TOOL_CALL_PATTERN.matcher(turn.response());
            while (m.find()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    static Set<String> extractFilePaths(List<ConversationTurn> turns) {
        Set<String> paths = new LinkedHashSet<>();
        for (ConversationTurn turn : turns) {
            if (turn.response() == null) continue;
            Matcher m = FILE_PATH_PATTERN.matcher(turn.response());
            while (m.find() && paths.size() < MAX_KEY_FILES) {
                paths.add(m.group(1));
            }
        }
        return paths;
    }
}
