package ai.intelliswarm.swarmai.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SubagentSpec / SubagentResult Tests")
class SubagentSpecTest {

    @Test
    @DisplayName("Builder produces a spec with sensible defaults; userPrompt is required")
    void builderDefaults() {
        SubagentSpec spec = SubagentSpec.builder()
                .userPrompt("do the thing")
                .build();
        assertNotNull(spec.id());
        assertEquals("subagent", spec.type());
        assertEquals("", spec.systemPrompt());
        assertEquals("do the thing", spec.userPrompt());
        assertTrue(spec.allowedToolNames().isEmpty());
        assertEquals(Duration.ZERO, spec.timeout());
        assertNull(spec.workspaceFactory());
        assertTrue(spec.parentMetadata().isEmpty());
    }

    @Test
    @DisplayName("Blank userPrompt is rejected at build()")
    void blankUserPromptRejected() {
        assertThrows(IllegalStateException.class,
                () -> SubagentSpec.builder().build());
        assertThrows(IllegalStateException.class,
                () -> SubagentSpec.builder().userPrompt("").build());
        assertThrows(IllegalStateException.class,
                () -> SubagentSpec.builder().userPrompt("   ").build());
    }

    @Test
    @DisplayName("allowedTool / allowedTools both populate the immutable list")
    void allowedToolsImmutable() {
        SubagentSpec spec = SubagentSpec.builder()
                .userPrompt("x")
                .allowedTool("ls")
                .allowedTools(java.util.List.of("cat", "head"))
                .build();
        assertEquals(java.util.List.of("ls", "cat", "head"), spec.allowedToolNames());
        assertThrows(UnsupportedOperationException.class,
                () -> spec.allowedToolNames().add("rm"));
    }

    @Test
    @DisplayName("SubagentResult success/failure factories preserve fields and report status")
    void resultFactories() {
        SubagentResult ok = SubagentResult.success("id-1", "answer", java.util.Map.of("k", "v"));
        assertEquals(SubagentStatus.COMPLETED, ok.status());
        assertEquals("answer", ok.output());
        assertTrue(ok.succeeded());
        assertTrue(ok.isTerminal());
        assertEquals("v", ok.metrics().get("k"));

        SubagentResult bad = SubagentResult.failure("id-2", "boom", java.util.Map.of());
        assertEquals(SubagentStatus.FAILED, bad.status());
        assertEquals("boom", bad.output());
        assertTrue(bad.isTerminal());
    }

    @Test
    @DisplayName("SubagentStatus.isTerminal reflects the four terminal states")
    void statusTerminal() {
        assertTrue(SubagentStatus.COMPLETED.isTerminal());
        assertTrue(SubagentStatus.FAILED.isTerminal());
        assertTrue(SubagentStatus.CANCELLED.isTerminal());
        assertTrue(SubagentStatus.TIMED_OUT.isTerminal());
        assertTrue(!SubagentStatus.PENDING.isTerminal());
        assertTrue(!SubagentStatus.RUNNING.isTerminal());
    }
}
