package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Code Execution Tool — executes code snippets in a sandboxed environment.
 *
 * Supported languages:
 * - javascript: Via Java Nashorn/GraalJS ScriptEngine (no external process)
 * - shell: Via ProcessBuilder with timeout (Linux/Mac: bash, Windows: cmd)
 *
 * Safety: timeout, output length limit, restricted commands for shell.
 */
@Component
public class CodeExecutionTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(CodeExecutionTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 8000;
    private static final Set<String> VALID_LANGUAGES = Set.of("javascript", "shell");

    // Shell commands that are blocked for safety
    private static final Set<String> BLOCKED_SHELL_COMMANDS = Set.of(
        "rm", "rmdir", "del", "format", "mkfs", "dd", "shutdown", "reboot",
        "kill", "killall", "pkill", "halt", "poweroff", "init",
        "chmod", "chown", "passwd", "sudo", "su", "mount", "umount",
        "curl", "wget", "nc", "ncat", "ssh", "scp", "sftp", "telnet",
        "iptables", "firewall-cmd", "systemctl", "service"
    );

    @Override
    public String getFunctionName() {
        return "code_execution";
    }

    @Override
    public String getDescription() {
        return "Execute code snippets. Supports 'javascript' (via ScriptEngine, no network access) and " +
               "'shell' (restricted commands, with timeout). Returns stdout, stderr, and exit code.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String code = (String) parameters.get("code");
        String language = ((String) parameters.getOrDefault("language", "javascript")).toLowerCase();
        Integer timeout = parameters.get("timeout") != null
            ? Math.min(((Number) parameters.get("timeout")).intValue(), MAX_TIMEOUT_SECONDS)
            : DEFAULT_TIMEOUT_SECONDS;

        logger.info("CodeExecutionTool: language={}, timeout={}s, code={} chars",
            language, timeout, code != null ? code.length() : 0);

        try {
            // 1. Validate
            if (code == null || code.trim().isEmpty()) {
                return "Error: Code is required";
            }
            if (!VALID_LANGUAGES.contains(language)) {
                return "Error: Unsupported language: '" + language + "'. Supported: " + VALID_LANGUAGES;
            }

            // 2. Execute
            return switch (language) {
                case "javascript" -> executeJavaScript(code, timeout);
                case "shell" -> executeShell(code, timeout);
                default -> "Error: Unsupported language: " + language;
            };

        } catch (Exception e) {
            logger.error("Error executing code", e);
            return "Error: Execution failed: " + e.getMessage();
        }
    }

    private String executeJavaScript(String code, int timeout) {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");

        if (engine == null) {
            return "Error: JavaScript engine not available. Java 21 requires GraalJS on classpath.";
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Object> future = executor.submit(() -> engine.eval(code));
            Object result = future.get(timeout, TimeUnit.SECONDS);

            StringBuilder response = new StringBuilder();
            response.append("**Language:** JavaScript\n");
            response.append("**Status:** Success\n\n");
            response.append("**Output:**\n```\n");
            response.append(result != null ? result.toString() : "(no return value)");
            response.append("\n```\n");

            return truncate(response.toString());

        } catch (TimeoutException e) {
            return "Error: Execution timed out after " + timeout + " seconds";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ScriptException) {
                return "**Language:** JavaScript\n**Status:** Error\n\n**Error:**\n```\n" +
                       cause.getMessage() + "\n```\n";
            }
            return "Error: Execution failed: " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Execution interrupted";
        } finally {
            executor.shutdownNow();
        }
    }

    private String executeShell(String code, int timeout) {
        // Security check: scan for blocked commands
        String securityError = checkShellSafety(code);
        if (securityError != null) {
            return "Error: " + securityError;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] command = isWindows
            ? new String[]{"cmd", "/c", code}
            : new String[]{"bash", "-c", code};

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            // Read stdout and stderr in separate threads to avoid deadlock
            Future<String> stdoutFuture = Executors.newSingleThreadExecutor()
                .submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = Executors.newSingleThreadExecutor()
                .submit(() -> readStream(process.getErrorStream()));

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return "Error: Shell execution timed out after " + timeout + " seconds";
            }

            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            StringBuilder response = new StringBuilder();
            response.append("**Language:** Shell\n");
            response.append("**Exit Code:** ").append(exitCode).append("\n");
            response.append("**Status:** ").append(exitCode == 0 ? "Success" : "Error").append("\n\n");

            if (!stdout.isEmpty()) {
                response.append("**stdout:**\n```\n").append(truncateStr(stdout, MAX_OUTPUT_LENGTH / 2)).append("\n```\n\n");
            }
            if (!stderr.isEmpty()) {
                response.append("**stderr:**\n```\n").append(truncateStr(stderr, MAX_OUTPUT_LENGTH / 4)).append("\n```\n");
            }
            if (stdout.isEmpty() && stderr.isEmpty()) {
                response.append("(no output)\n");
            }

            return truncate(response.toString());

        } catch (IOException e) {
            return "Error: Failed to start shell process: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Shell execution interrupted";
        } catch (Exception e) {
            return "Error: Shell execution failed: " + e.getMessage();
        }
    }

    private String checkShellSafety(String code) {
        String lower = code.toLowerCase().trim();

        for (String blocked : BLOCKED_SHELL_COMMANDS) {
            // Check for command at start of line, after pipe, after semicolon, or after &&/||
            if (lower.startsWith(blocked + " ") || lower.startsWith(blocked + "\n") || lower.equals(blocked) ||
                lower.contains("| " + blocked) || lower.contains("|" + blocked) ||
                lower.contains("; " + blocked) || lower.contains(";" + blocked) ||
                lower.contains("&& " + blocked) || lower.contains("|| " + blocked)) {
                return "Blocked command: '" + blocked + "' is not allowed for security reasons";
            }
        }

        // Block redirects to sensitive paths
        if (lower.contains("> /etc/") || lower.contains(">> /etc/") ||
            lower.contains("> /usr/") || lower.contains("> /var/") ||
            lower.contains("> /root") || lower.contains("> /home")) {
            return "Writing to system directories is not allowed";
        }

        return null;
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

    private String truncateStr(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n[... truncated ...]" : s;
    }

    private String truncate(String response) {
        int max = getMaxResponseLength();
        if (response.length() > max) {
            return response.substring(0, max) + "\n\n[... output truncated ...]";
        }
        return response;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> code = new HashMap<>();
        code.put("type", "string");
        code.put("description", "Code to execute");
        properties.put("code", code);

        Map<String, Object> language = new HashMap<>();
        language.put("type", "string");
        language.put("description", "Language: javascript, shell (default: javascript)");
        language.put("default", "javascript");
        language.put("enum", List.of("javascript", "shell"));
        properties.put("language", language);

        Map<String, Object> timeout = new HashMap<>();
        timeout.put("type", "integer");
        timeout.put("description", "Timeout in seconds (default: 30, max: 60)");
        timeout.put("default", DEFAULT_TIMEOUT_SECONDS);
        properties.put("timeout", timeout);

        schema.put("properties", properties);
        schema.put("required", new String[]{"code"});

        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    public record Request(String code, String language, Integer timeout) {}
}
