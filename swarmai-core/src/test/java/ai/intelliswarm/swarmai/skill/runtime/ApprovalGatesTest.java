package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.AutoApprovalGate;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.CallbackApprovalGate;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.ConsoleApprovalGate;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.DenyGate;
import ai.intelliswarm.swarmai.skill.runtime.ApprovalGates.RememberingApprovalGate;
import ai.intelliswarm.swarmai.skill.runtime.ImageApprovalGate.Decision;
import ai.intelliswarm.swarmai.skill.runtime.ImageApprovalGate.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Approval-gate behaviour. These tests are the security-critical core of
 * the dynamic capability layer — they verify the framework can't be
 * tricked into building or pulling an image without explicit consent.
 */
class ApprovalGatesTest {

    private static Request build(String tag) {
        return new Request(tag, "FROM alpine:3.19\nRUN echo hi\n", "test", "alpine:3.19");
    }

    private static Request pull(String tag) {
        return new Request(tag, null, "test", tag);
    }

    @Test
    @DisplayName("AutoApprovalGate always approves")
    void autoApproves() {
        var gate = new AutoApprovalGate();
        assertEquals(Decision.APPROVE, gate.approve(build("any:1")));
        assertEquals(Decision.APPROVE, gate.approve(pull("alpine:3.19")));
    }

    @Test
    @DisplayName("DenyGate always denies")
    void denyDenies() {
        var gate = new DenyGate();
        assertEquals(Decision.DENY, gate.approve(build("any:1")));
        assertEquals(Decision.DENY, gate.approve(pull("alpine:3.19")));
    }

    @Test
    @DisplayName("CallbackApprovalGate routes through the lambda")
    void callbackRoutes() {
        AtomicInteger callCount = new AtomicInteger();
        var gate = new CallbackApprovalGate(req -> {
            callCount.incrementAndGet();
            return req.imageTag().contains("trusted") ? Decision.APPROVE : Decision.DENY;
        });
        assertEquals(Decision.APPROVE, gate.approve(build("trusted:1")));
        assertEquals(Decision.DENY, gate.approve(build("sketchy:1")));
        assertEquals(2, callCount.get());
    }

    @Test
    @DisplayName("CallbackApprovalGate treats null lambda return as DENY (fail-safe)")
    void callbackNullIsDeny() {
        var gate = new CallbackApprovalGate(req -> null);
        assertEquals(Decision.DENY, gate.approve(build("any:1")));
    }

    @Test
    @DisplayName("ConsoleApprovalGate parses 'y' / 'r' / 'n' replies")
    void consoleParsesReplies() {
        // Three sequential approvals: y → APPROVE, r → APPROVE_AND_REMEMBER, n → DENY.
        BufferedReader in = new BufferedReader(new StringReader("y\nr\nn\n"));
        var out = new ByteArrayOutputStream();
        var gate = new ConsoleApprovalGate(new PrintStream(out, true, StandardCharsets.UTF_8), in);

        assertEquals(Decision.APPROVE, gate.approve(build("a:1")));
        assertEquals(Decision.APPROVE_AND_REMEMBER, gate.approve(build("b:1")));
        assertEquals(Decision.DENY, gate.approve(build("c:1")));

        String prompt = out.toString(StandardCharsets.UTF_8);
        assertTrue(prompt.contains("a:1") && prompt.contains("b:1") && prompt.contains("c:1"));
        assertTrue(prompt.contains("FROM alpine:3.19"));
    }

    @Test
    @DisplayName("ConsoleApprovalGate denies on EOF (CI / non-TTY safety)")
    void consoleDeniesOnEof() {
        BufferedReader in = new BufferedReader(new StringReader(""));
        var gate = new ConsoleApprovalGate(
                new PrintStream(new ByteArrayOutputStream()), in);
        assertEquals(Decision.DENY, gate.approve(build("any:1")));
    }

    @Test
    @DisplayName("ConsoleApprovalGate prints the dockerfile but truncates very long ones")
    void consoleTruncatesLongDockerfile() {
        BufferedReader in = new BufferedReader(new StringReader("n\n"));
        var out = new ByteArrayOutputStream();
        var gate = new ConsoleApprovalGate(new PrintStream(out, true, StandardCharsets.UTF_8), in);

        StringBuilder long_ = new StringBuilder("FROM alpine:3.19\n");
        for (int i = 0; i < 1000; i++) long_.append("RUN echo line").append(i).append("\n");
        gate.approve(new Request("big:1", long_.toString(), "test", "alpine:3.19"));

        String shown = out.toString(StandardCharsets.UTF_8);
        // The dockerfile prints with "  | " line prefix; just assert it was clipped.
        assertTrue(shown.contains("..."));
        assertTrue(shown.length() < long_.length(),
                "console output should be shorter than the raw dockerfile after truncation");
    }

    @Test
    @DisplayName("RememberingApprovalGate caches APPROVE_AND_REMEMBER across approvals")
    void rememberingCaches(@TempDir Path tmp) {
        Path store = tmp.resolve("approved.txt");
        AtomicInteger inner = new AtomicInteger();
        var wrapped = new RememberingApprovalGate(req -> {
            inner.incrementAndGet();
            return Decision.APPROVE_AND_REMEMBER;
        }, store);

        assertEquals(Decision.APPROVE_AND_REMEMBER, wrapped.approve(build("kali:1")));
        assertEquals(Decision.APPROVE, wrapped.approve(build("kali:1")));   // cached
        assertEquals(Decision.APPROVE, wrapped.approve(build("kali:1")));   // still cached
        assertEquals(1, inner.get(), "inner gate should be consulted exactly once");
    }

    @Test
    @DisplayName("RememberingApprovalGate persists APPROVE_AND_REMEMBER across instances")
    void rememberingPersistsAcrossInstances(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("approved.txt");
        AtomicInteger calls = new AtomicInteger();

        var first = new RememberingApprovalGate(req -> {
            calls.incrementAndGet();
            return Decision.APPROVE_AND_REMEMBER;
        }, store);
        first.approve(build("nmap-toolbox:1"));

        // Verify the file was written.
        List<String> lines = Files.readAllLines(store);
        assertTrue(lines.contains("nmap-toolbox:1"));

        // New gate instance over the same file: should NOT consult inner.
        AtomicInteger secondCalls = new AtomicInteger();
        var second = new RememberingApprovalGate(req -> {
            secondCalls.incrementAndGet();
            return Decision.DENY;
        }, store);
        assertEquals(Decision.APPROVE, second.approve(build("nmap-toolbox:1")));
        assertEquals(0, secondCalls.get(), "persistent cache should bypass the inner gate");
    }

    @Test
    @DisplayName("RememberingApprovalGate caches plain APPROVE for the session only")
    void rememberingSessionCacheIsTransient(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("approved.txt");
        AtomicInteger calls = new AtomicInteger();
        var first = new RememberingApprovalGate(req -> {
            calls.incrementAndGet();
            return Decision.APPROVE; // not "remember"
        }, store);

        first.approve(build("transient:1"));
        first.approve(build("transient:1"));
        assertEquals(1, calls.get(), "session cache should bypass the inner gate within one instance");

        // New instance: session cache is empty; persistent file should be empty too.
        assertFalse(Files.exists(store) && !Files.readAllLines(store).contains("transient:1"));
        var second = new RememberingApprovalGate(req -> {
            calls.incrementAndGet();
            return Decision.APPROVE;
        }, store);
        second.approve(build("transient:1"));
        assertEquals(2, calls.get(), "new instance should re-consult the inner gate");
    }

    @Test
    @DisplayName("RememberingApprovalGate denies pass through to caller without persisting")
    void rememberingDenyDoesNotPersist(@TempDir Path tmp) {
        Path store = tmp.resolve("approved.txt");
        var gate = new RememberingApprovalGate(req -> Decision.DENY, store);
        assertEquals(Decision.DENY, gate.approve(build("evil:1")));
        assertFalse(Files.exists(store), "denial should not persist anything");
    }

    @Test
    @DisplayName("Request.isBuild() distinguishes build from pull")
    void requestIsBuild() {
        assertTrue(build("a:1").isBuild());
        assertFalse(pull("a:1").isBuild());
    }
}
