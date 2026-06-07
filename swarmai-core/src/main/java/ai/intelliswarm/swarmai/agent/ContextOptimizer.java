package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRole;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites a message history to drop redundant tool-result payloads BEFORE
 * we hand it to the LLM. The biggest token-leak in long sessions is repeated
 * identical reads: the model often re-reads a file three or four times during
 * a refactor, and each result is hundreds of tokens of duplicated content.
 *
 * <p>Strategy: for every "read-only" tool call (file_read, grep_search,
 * glob_files, web_fetch, web_search), record a fingerprint of (tool, args).
 * If we see a second call with the same fingerprint, replace the OLDER
 * result with a one-line stub pointing at the latest one. The newest
 * occurrence is always preserved verbatim — the agent still needs to "see"
 * the data on its most-recent fetch.
 *
 * <p>Write-side tools (file_edit, file_write, bash_exec, ...) are NEVER
 * deduplicated: their output is meaningful state, not a snapshot. Same for
 * tools we don't recognise (e.g. MCP tools) — we don't know if they're
 * idempotent.
 *
 * <p>Complement to {@link ConversationCompactor}: compactor SUMMARISES the
 * full history into a brief; this optimizer leaves the history shape intact
 * and just collapses the obvious duplicates. Use the optimizer per-turn (fast,
 * no LLM call); use the compactor periodically (slow, costs an LLM call).
 *
 * @since 1.0.26
 */
@PublicApi(since = "1.0.26")
public final class ContextOptimizer {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "file_read", "grep_search", "glob_files", "web_fetch", "web_search",
            "bg_output", "bg_list");

    private static final String STUB_FORMAT =
            "(de-duplicated; identical %s call appears later in history with full result)";

    private ContextOptimizer() {}

    /**
     * Returns a new message list with duplicate read-only tool results
     * collapsed to short stubs. Preserves message count + ids — only content
     * of the duplicate TOOL messages is shortened. Returns the original list
     * unchanged when no duplicates are found.
     *
     * <p>Safety: even when (tool, args) match across two calls, the older
     * result is only stubbed when its <em>content</em> is byte-identical to the
     * newest one. That guards against the case where state changed between
     * calls — a {@code file_read} repeated after a {@code file_edit} produces
     * the same fingerprint but different content, and we must keep both
     * snapshots so subsequent turns can reason about the change.
     */
    public static List<LlmMessage> deduplicateToolResults(List<LlmMessage> messages) {
        if (messages == null || messages.size() < 4) return messages;

        // Pass 1: map each tool_use_id → its fingerprint (tool + args).
        Map<String, String> idToFingerprint = new HashMap<>();
        for (LlmMessage m : messages) {
            if (m.role() != LlmRole.ASSISTANT || m.toolCalls().isEmpty()) continue;
            for (LlmToolCall c : m.toolCalls()) {
                if (READ_ONLY_TOOLS.contains(c.toolName())) {
                    idToFingerprint.put(c.id(), c.toolName() + "|" + c.arguments());
                }
            }
        }
        if (idToFingerprint.isEmpty()) return messages;

        // Pass 2: walk in reverse. For each fingerprint, remember the NEWEST
        // result's content. Older results with the same fingerprint are only
        // marked as duplicates when their content is byte-identical — different
        // content means state changed between calls and both snapshots matter.
        Map<String, String> newestContentByFingerprint = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage m = messages.get(i);
            if (m.role() != LlmRole.TOOL) continue;
            String fp = idToFingerprint.get(m.toolUseId());
            if (fp == null) continue;
            String content = m.content() == null ? "" : m.content();
            String newest = newestContentByFingerprint.get(fp);
            if (newest == null) {
                newestContentByFingerprint.put(fp, content);
            } else if (newest.equals(content)) {
                duplicateIds.add(m.toolUseId());
            }
            // else: same call, different output → state changed; keep both.
        }
        if (duplicateIds.isEmpty()) return messages;

        // Pass 3: rebuild with stubs.
        List<LlmMessage> out = new ArrayList<>(messages.size());
        for (LlmMessage m : messages) {
            if (m.role() == LlmRole.TOOL && duplicateIds.contains(m.toolUseId())) {
                String fp = idToFingerprint.getOrDefault(m.toolUseId(), "tool");
                String toolName = fp.split("\\|", 2)[0];
                out.add(LlmMessage.toolResult(m.toolUseId(),
                        String.format(STUB_FORMAT, toolName)));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Number of bytes the dedupe pass saved on this history. Useful for
     * reporting and for benchmarking the optimizer.
     */
    public static long estimateBytesSaved(List<LlmMessage> before, List<LlmMessage> after) {
        if (before == after) return 0;
        long beforeBytes = 0;
        long afterBytes = 0;
        for (LlmMessage m : before) beforeBytes += m.content() == null ? 0 : m.content().length();
        for (LlmMessage m : after)  afterBytes  += m.content() == null ? 0 : m.content().length();
        return Math.max(0, beforeBytes - afterBytes);
    }
}
