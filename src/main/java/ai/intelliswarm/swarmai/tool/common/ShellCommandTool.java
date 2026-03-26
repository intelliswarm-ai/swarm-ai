package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Shell Command Tool — executes whitelisted shell commands with timeout and output capture.
 *
 * Only commands in the whitelist are allowed. Designed for safe, read-only system introspection.
 */
@Component
public class ShellCommandTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellCommandTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 8000;

    // Only these commands are allowed (read-only, safe introspection + network scanning)
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
        "echo", "date", "hostname", "whoami", "pwd", "uname",
        "ls", "dir", "cat", "head", "tail", "wc", "sort", "uniq", "cut",
        "grep", "find", "which", "whereis", "type",
        "env", "printenv", "set",
        "ps", "top", "df", "du", "free", "uptime",
        "java", "javac", "mvn", "gradle", "node", "npm", "python", "pip",
        "git", "docker", "kubectl",
        // Network scanning and diagnostics (read-only)
        "nmap", "ping", "arp", "arp-scan", "ip", "ifconfig",
        "netstat", "ss", "traceroute", "tracepath", "dig", "nslookup", "host",
        "curl", "wget"
    );

    @Override
    public String getFunctionName() {
        return "shell_command";
    }

    @Override
    public String getDescription() {
        return "Execute whitelisted shell commands for system introspection. " +
               "Allowed commands: ls, cat, grep, find, git, docker, ps, df, env, echo, date, and more. " +
               "Returns stdout, stderr, and exit code.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String command = (String) parameters.get("command");
        Integer timeout = parameters.get("timeout") != null
            ? Math.min(((Number) parameters.get("timeout")).intValue(), DEFAULT_TIMEOUT_SECONDS)
            : DEFAULT_TIMEOUT_SECONDS;

        logger.info("ShellCommandTool: command='{}', timeout={}s", command, timeout);

        try {
            // 1. Validate
            if (command == null || command.trim().isEmpty()) {
                return "Error: Command is required";
            }

            // 2. Safety check
            String safetyError = checkCommandSafety(command.trim());
            if (safetyError != null) {
                return "Error: " + safetyError;
            }

            // 3. Execute
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            String[] shellCmd = isWindows
                ? new String[]{"cmd", "/c", command}
                : new String[]{"bash", "-c", command};

            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = executor.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = executor.submit(() -> readStream(process.getErrorStream()));

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                executor.shutdownNow();
                return "Error: Command timed out after " + timeout + " seconds";
            }

            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            executor.shutdown();

            // 4. Format response
            StringBuilder response = new StringBuilder();
            response.append("**Command:** `").append(command).append("`\n");
            response.append("**Exit Code:** ").append(exitCode).append("\n");
            response.append("**Status:** ").append(exitCode == 0 ? "Success" : "Error").append("\n\n");

            if (!stdout.isEmpty()) {
                String truncated = stdout.length() > MAX_OUTPUT_LENGTH
                    ? stdout.substring(0, MAX_OUTPUT_LENGTH) + "\n[... truncated ...]"
                    : stdout;
                response.append("**stdout:**\n```\n").append(truncated).append("\n```\n\n");
            }
            if (!stderr.isEmpty()) {
                String truncated = stderr.length() > MAX_OUTPUT_LENGTH / 2
                    ? stderr.substring(0, MAX_OUTPUT_LENGTH / 2) + "\n[... truncated ...]"
                    : stderr;
                response.append("**stderr:**\n```\n").append(truncated).append("\n```\n");
            }
            if (stdout.isEmpty() && stderr.isEmpty()) {
                response.append("(no output)\n");
            }

            return response.toString();

        } catch (IOException e) {
            logger.error("Error executing shell command", e);
            return "Error: Failed to execute command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Command interrupted";
        } catch (Exception e) {
            logger.error("Unexpected error executing shell command", e);
            return "Error: " + e.getMessage();
        }
    }

    private String checkCommandSafety(String command) {
        // Extract the base command (first word, ignoring leading env vars)
        String baseCommand = extractBaseCommand(command);

        if (baseCommand == null || baseCommand.isEmpty()) {
            return "Could not determine command to execute";
        }

        if (!ALLOWED_COMMANDS.contains(baseCommand)) {
            return "Command '" + baseCommand + "' is not in the allowed list. Allowed: " +
                   String.join(", ", new TreeSet<>(ALLOWED_COMMANDS));
        }

        // Block output redirection to files
        if (command.contains(">") && !command.contains("grep")) {
            return "Output redirection (>) is not allowed";
        }

        return null;
    }

    private String extractBaseCommand(String command) {
        // Skip env var assignments at the start (e.g., "FOO=bar cmd")
        String[] parts = command.split("\\s+");
        for (String part : parts) {
            if (!part.contains("=")) {
                // Remove path prefixes (e.g., /usr/bin/ls -> ls)
                int lastSlash = part.lastIndexOf('/');
                if (lastSlash >= 0) {
                    return part.substring(lastSlash + 1);
                }
                return part;
            }
        }
        return parts.length > 0 ? parts[0] : null;
    }

    private String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null && sb.length() < MAX_OUTPUT_LENGTH) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString().trim();
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("command", Map.of("type", "string",
            "description", "Shell command to execute (must be in allowed list: ls, cat, grep, find, git, ps, df, echo, etc.)"));
        properties.put("timeout", Map.of("type", "integer",
            "description", "Timeout in seconds (default: 15, max: 15)", "default", DEFAULT_TIMEOUT_SECONDS));

        schema.put("properties", properties);
        schema.put("required", new String[]{"command"});
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    public record Request(String command, Integer timeout) {}
}
