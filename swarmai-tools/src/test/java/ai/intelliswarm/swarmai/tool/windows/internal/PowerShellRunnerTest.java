package ai.intelliswarm.swarmai.tool.windows.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PowerShellRunner Unit Tests")
class PowerShellRunnerTest {

    @Test
    @DisplayName("Result.ok() is true only when exitCode == 0")
    void okReflectsExitCode() {
        assertTrue(new PowerShellRunner.Result(0, "", "").ok());
        assertFalse(new PowerShellRunner.Result(1, "", "msg").ok());
        assertFalse(new PowerShellRunner.Result(-1, "", "").ok());
    }

    @Test
    @DisplayName("DefaultPowerShellRunner rejects null command/timeout and throws on a missing executable")
    void defaultRunnerInputValidationAndMissingExecutable() {
        DefaultPowerShellRunner runner = new DefaultPowerShellRunner("definitely-not-a-real-shell-binary-xyz");

        assertThrows(NullPointerException.class, () -> runner.run(null, Duration.ofSeconds(1)));
        assertThrows(NullPointerException.class, () -> runner.run("Get-Process", null));
        assertThrows(PowerShellException.class,
                () -> runner.run("Get-Process", Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("a fake runner can be supplied so other tools are testable without spawning processes")
    void mockableInterfaceShape() {
        PowerShellRunner stub = (cmd, t) -> new PowerShellRunner.Result(0, "OK\n", "");
        PowerShellRunner.Result r = stub.run("anything", Duration.ofSeconds(1));
        assertEquals(0, r.exitCode());
        assertEquals("OK\n", r.stdout());
        assertTrue(r.ok());
    }
}
