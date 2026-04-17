package ai.intelliswarm.swarmai.skill.runtime.subprocess;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class SubprocessExecutorTest {

    private static SubprocessExecutor executor;

    @BeforeAll
    static void setUp() {
        executor = new SubprocessExecutor();
    }

    @AfterAll
    static void tearDown() {
        executor.shutdown();
    }

    @Test
    void runsSimpleCommandAndCapturesStdout() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "echo hello-world")
            .build());

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertEquals("hello-world\n", result.stdout());
        assertFalse(result.timedOut());
        assertFalse(result.stdoutTruncated());
    }

    @Test
    void capturesStderrSeparately() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "echo on-stdout && echo on-stderr 1>&2")
            .build());

        assertTrue(result.success());
        assertTrue(result.stdout().contains("on-stdout"));
        assertTrue(result.stderr().contains("on-stderr"));
    }

    @Test
    void returnsNonZeroExitOnFailingCommand() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "exit 17")
            .build());

        assertFalse(result.success());
        assertEquals(17, result.exitCode());
        assertFalse(result.timedOut());
    }

    @Test
    void enforcesWallClockTimeoutAndDestroysProcess() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "sleep 10")
            .timeout(Duration.ofMillis(300))
            .build());

        assertTrue(result.timedOut());
        assertFalse(result.success());
        // After destroyForcibly, the shell typically exits non-zero
        assertNotEquals(0, result.exitCode());
        assertTrue(result.wallDuration().toMillis() < 2000,
            "Expected timeout destruction under 2s, got " + result.wallDuration().toMillis() + "ms");
    }

    @Test
    void passesStdinToChild() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "cat")
            .stdin("payload-from-parent")
            .build());

        assertTrue(result.success());
        assertEquals("payload-from-parent", result.stdout());
    }

    @Test
    void clearsEnvironmentByDefault() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "echo \"$PATH|$SWARMAI_TEST_SECRET\"")
            .environment(Map.of("PATH", "/usr/bin:/bin"))
            .build());

        assertTrue(result.success());
        assertEquals("/usr/bin:/bin|\n", result.stdout());
    }

    @Test
    void honorsWorkingDirectory(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("marker.txt"), "hello");

        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "cat marker.txt")
            .workingDirectory(tempDir)
            .build());

        assertTrue(result.success());
        assertEquals("hello", result.stdout());
    }

    @Test
    void capsStdoutAndTruncates() throws IOException {
        // Write ~64 KB to stdout; cap at 1 KB.
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "yes x | head -c 65536")
            .stdoutCapBytes(1024)
            .timeout(Duration.ofSeconds(5))
            .build());

        assertTrue(result.stdoutTruncated());
        assertTrue(result.stdout().length() <= 1024);
    }

    @Test
    void throwsIoExceptionOnMissingExecutable() {
        IOException e = assertThrows(IOException.class, () -> executor.execute(SubprocessRequest.builder()
            .command("/nonexistent/binary/path/for/test")
            .build()));
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
    }

    @Test
    void stdinNullUsesEmptyStdin() throws IOException {
        SubprocessResult result = executor.execute(SubprocessRequest.builder()
            .command("/bin/sh", "-c", "cat; echo END")
            .build());

        assertTrue(result.success());
        assertEquals("END\n", result.stdout());
        assertNull(null);  // placeholder — value test above is the assertion
    }
}
