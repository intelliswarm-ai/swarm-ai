package ai.intelliswarm.swarmai.agent;

import ai.intelliswarm.swarmai.agent.llm.LlmMessage;
import ai.intelliswarm.swarmai.agent.llm.LlmRole;
import ai.intelliswarm.swarmai.agent.llm.LlmToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ContextOptimizer — read-only tool-result deduplication (1.0.26)")
class ContextOptimizerTest {

    @Test
    void returnsSameListWhenNoDuplicates() {
        var history = List.of(
                LlmMessage.user("read foo.py"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t1", "contents of foo.py"));
        var out = ContextOptimizer.deduplicateToolResults(history);
        assertSame(history, out, "no duplicates → no rewrite, return original list");
    }

    @Test
    void replacesOlderDuplicateReadOnlyResultWithStub() {
        // Same call → same content → safe to dedupe the older snapshot.
        // (If content differed, the keepBothWhenStateChangedBetweenReads test
        // covers the opposite branch.)
        String body = "<big contents 500 chars>";
        var history = List.of(
                LlmMessage.user("read foo.py"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t1", body),
                LlmMessage.user("re-read to confirm"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t2", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t2", body));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertNotSame(history, out);
        assertEquals(history.size(), out.size(), "message count preserved");

        // t1 (older) becomes a stub
        assertEquals(LlmRole.TOOL, out.get(2).role());
        assertEquals("t1", out.get(2).toolUseId());
        assertTrue(out.get(2).content().contains("de-duplicated"),
                "older identical read becomes a stub, got: " + out.get(2).content());

        // t2 (newer) is preserved verbatim
        assertEquals(body, out.get(5).content());
    }

    @Test
    void neverDeduplicatesWriteSideTools() {
        var history = List.of(
                LlmMessage.user("edit"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_edit", Map.of("path", "x.py",
                                "old_string", "a", "new_string", "b")))),
                LlmMessage.toolResult("t1", "Edited x.py"),
                LlmMessage.user("edit again"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t2", "file_edit", Map.of("path", "x.py",
                                "old_string", "a", "new_string", "b")))),
                LlmMessage.toolResult("t2", "Edited x.py"));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertSame(history, out, "write-side tools must never be deduped — output is state, not a snapshot");
    }

    @Test
    void differentArgsDoNotMatch() {
        var history = List.of(
                LlmMessage.user("read two files"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t1", "foo contents"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t2", "file_read", Map.of("path", "bar.py")))),
                LlmMessage.toolResult("t2", "bar contents"));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertSame(history, out, "different args → different fingerprints → no dedupe");
    }

    @Test
    void handlesEmptyAndShortHistories() {
        assertNull(ContextOptimizer.deduplicateToolResults(null));
        assertEquals(List.of(), ContextOptimizer.deduplicateToolResults(List.of()));
        var oneMsg = List.of(LlmMessage.user("hi"));
        assertSame(oneMsg, ContextOptimizer.deduplicateToolResults(oneMsg));
    }

    @Test
    void estimateBytesSavedZeroWhenSameList() {
        var h = List.of(LlmMessage.user("hello"));
        assertEquals(0, ContextOptimizer.estimateBytesSaved(h, h));
    }

    @Test
    @DisplayName("regression: same args but DIFFERENT result → both kept (state changed)")
    void keepBothWhenStateChangedBetweenReads() {
        // file_read X → file_edit X → file_read X again. The two reads have the
        // same fingerprint, but their content differs because the file was
        // edited between calls. The optimizer must preserve both snapshots.
        var history = List.of(
                LlmMessage.user("read foo.py"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t1", "ORIGINAL contents of foo.py"),
                LlmMessage.user("edit then re-read"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t2", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t2", "EDITED contents of foo.py"));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertSame(history, out, "different content with same fingerprint must NOT be deduped");
    }

    @Test
    @DisplayName("regression: bg_output growing over time → older snapshot preserved")
    void keepBothWhenBgOutputGrows() {
        var history = List.of(
                LlmMessage.user("poll"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new LlmToolCall("t1", "bg_output", Map.of("job_id", "j1")))),
                LlmMessage.toolResult("t1", "[stdout]\nstarting...\n"),
                LlmMessage.user("poll again"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new LlmToolCall("t2", "bg_output", Map.of("job_id", "j1")))),
                LlmMessage.toolResult("t2", "[stdout]\nstarting...\nworking...\ndone\n"));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertSame(history, out, "bg_output growth must not be stubbed away");
    }

    @Test
    @DisplayName("identical reads of an unchanged file ARE still deduped (the happy path)")
    void stillDedupesWhenContentIdentical() {
        var history = List.of(
                LlmMessage.user("read"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t1", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t1", "same bytes"),
                LlmMessage.user("read again to confirm"),
                LlmMessage.assistantWithToolCalls("ok", List.of(
                        new LlmToolCall("t2", "file_read", Map.of("path", "foo.py")))),
                LlmMessage.toolResult("t2", "same bytes"));

        var out = ContextOptimizer.deduplicateToolResults(history);
        assertNotSame(history, out);
        assertTrue(out.get(2).content().contains("de-duplicated"));
        assertEquals("same bytes", out.get(5).content());
    }

    @Test
    void estimateBytesSavedReportsTrueDelta() {
        var before = List.of(LlmMessage.toolResult("t1", "0".repeat(1000)));
        var after = List.of(LlmMessage.toolResult("t1", "(stub)"));
        long delta = ContextOptimizer.estimateBytesSaved(before, after);
        assertTrue(delta > 990, "expected ~994 bytes saved, got " + delta);
    }
}
