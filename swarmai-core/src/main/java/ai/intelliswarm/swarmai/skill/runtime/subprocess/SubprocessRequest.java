package ai.intelliswarm.swarmai.skill.runtime.subprocess;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record SubprocessRequest(
    List<String> command,
    Path workingDirectory,
    Map<String, String> environment,
    String stdin,
    Duration timeout,
    long stdoutCapBytes,
    long stderrCapBytes
) {
    public static final long DEFAULT_STDOUT_CAP = 512 * 1024;
    public static final long DEFAULT_STDERR_CAP = 64 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public SubprocessRequest {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        command = List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (stdoutCapBytes <= 0) stdoutCapBytes = DEFAULT_STDOUT_CAP;
        if (stderrCapBytes <= 0) stderrCapBytes = DEFAULT_STDERR_CAP;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> command;
        private Path workingDirectory;
        private Map<String, String> environment = Map.of();
        private String stdin;
        private Duration timeout = DEFAULT_TIMEOUT;
        private long stdoutCapBytes = DEFAULT_STDOUT_CAP;
        private long stderrCapBytes = DEFAULT_STDERR_CAP;

        public Builder command(List<String> command) { this.command = command; return this; }
        public Builder command(String... command) { this.command = List.of(command); return this; }
        public Builder workingDirectory(Path cwd) { this.workingDirectory = cwd; return this; }
        public Builder environment(Map<String, String> env) { this.environment = env; return this; }
        public Builder stdin(String stdin) { this.stdin = stdin; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder stdoutCapBytes(long cap) { this.stdoutCapBytes = cap; return this; }
        public Builder stderrCapBytes(long cap) { this.stderrCapBytes = cap; return this; }

        public SubprocessRequest build() {
            return new SubprocessRequest(
                command, workingDirectory, environment, stdin,
                timeout, stdoutCapBytes, stderrCapBytes);
        }
    }
}
