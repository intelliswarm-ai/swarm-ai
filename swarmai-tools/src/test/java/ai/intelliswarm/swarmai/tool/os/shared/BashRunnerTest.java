package ai.intelliswarm.swarmai.tool.os.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BashRunner exec + capture + timeout")
class BashRunnerTest {

    private final BashRunner runner = new BashRunner();

    @Test
    @DisplayName("Empty argv throws — caller bug")
    void emptyArgv() {
        assertThat(catching(() -> runner.run(List.of(), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Non-existent binary returns a non-OK Result with stderr explanation")
    void missingBinary() {
        CommandRunner.Result r = runner.run(List.of("/no/such/bin-1234567890"), Duration.ofSeconds(2));
        assertThat(r.ok()).isFalse();
        assertThat(r.exitCode()).isNotEqualTo(0);
        assertThat(r.stderr()).contains("could not start");
    }

    @Test
    @DisplayName("On POSIX, /bin/echo prints its arg to stdout and exits 0")
    @DisabledOnOs(OS.WINDOWS)
    void echoOnPosix() {
        CommandRunner.Result r = runner.run(List.of("/bin/echo", "hello-bashrunner"), Duration.ofSeconds(5));
        assertThat(r.ok()).isTrue();
        assertThat(r.exitCode()).isEqualTo(0);
        assertThat(r.stdout().trim()).isEqualTo("hello-bashrunner");
        assertThat(r.timedOut()).isFalse();
    }

    @Test
    @DisplayName("On POSIX, a slow command is killed when the timeout elapses")
    @DisabledOnOs(OS.WINDOWS)
    void timeoutKillsSlowCommand() {
        // sleep 5; we time out after 500ms — should be killed and report timedOut=true.
        long start = System.currentTimeMillis();
        CommandRunner.Result r = runner.run(List.of("sleep", "5"), Duration.ofMillis(500));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(r.timedOut()).isTrue();
        assertThat(r.ok()).isFalse();
        assertThat(elapsed).isLessThan(3_000); // would-be 5s; we should be back near 500ms + small overhead
    }

    private static Throwable catching(Runnable r) {
        try { r.run(); return null; } catch (Throwable t) { return t; }
    }
}
