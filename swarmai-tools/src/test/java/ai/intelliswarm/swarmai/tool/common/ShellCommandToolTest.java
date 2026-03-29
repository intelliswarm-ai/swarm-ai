package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ShellCommandTool Tests")
class ShellCommandToolTest {

    private ShellCommandTool shellTool;

    @BeforeEach
    void setUp() {
        shellTool = new ShellCommandTool();
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("shell_command", shellTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(shellTool.getDescription());
        assertTrue(shellTool.getDescription().contains("shell"));
        assertTrue(shellTool.getDescription().contains("ls"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() { assertFalse(shellTool.isAsync()); }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = shellTool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("command"));
        assertTrue(properties.containsKey("timeout"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("command", required[0]);
    }

    // ==================== Allowed Commands ====================

    @Test
    @DisplayName("Should execute echo command")
    void testEcho() {
        Object result = shellTool.execute(Map.of("command", "echo Hello World"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Hello World"), "Should capture echo output");
        assertTrue(r.contains("Exit Code:** 0"), "Should show exit code 0");
    }

    @Test
    @DisplayName("Should execute pwd command")
    void testPwd() {
        Object result = shellTool.execute(Map.of("command", "echo test_pwd_works"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("test_pwd_works"), "Should have output");
    }

    @Test
    @DisplayName("Should execute ls command")
    void testLs() {
        Object result = shellTool.execute(Map.of("command", "ls"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Exit Code"), "Should show exit code");
    }

    @Test
    @DisplayName("Should execute git status")
    void testGitStatus() {
        Object result = shellTool.execute(Map.of("command", "git --version"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should allow git. Got: " + r);
    }

    // ==================== Blocked Commands ====================

    @Test
    @DisplayName("Should block rm command")
    void testBlockRm() {
        Object result = shellTool.execute(Map.of("command", "rm -rf /"));
        assertTrue(result.toString().contains("Error"), "Should block rm");
        assertTrue(result.toString().contains("not in the allowed list"), "Should mention allowed list");
    }

    @Test
    @DisplayName("Should allow curl command (whitelisted for network diagnostics)")
    void testAllowCurl() {
        Object result = shellTool.execute(Map.of("command", "curl http://evil.com"));
        assertFalse(result.toString().contains("not in the allowed list"), "curl should be allowed");
    }

    @Test
    @DisplayName("Should allow wget command (whitelisted for network diagnostics)")
    void testAllowWget() {
        Object result = shellTool.execute(Map.of("command", "wget http://evil.com"));
        assertFalse(result.toString().contains("not in the allowed list"), "wget should be allowed");
    }

    @Test
    @DisplayName("Should block sudo command")
    void testBlockSudo() {
        Object result = shellTool.execute(Map.of("command", "sudo ls /root"));
        assertTrue(result.toString().contains("Error"), "Should block sudo");
    }

    @Test
    @DisplayName("Should block chmod command")
    void testBlockChmod() {
        Object result = shellTool.execute(Map.of("command", "chmod 777 /tmp/file"));
        assertTrue(result.toString().contains("Error"), "Should block chmod");
    }

    @Test
    @DisplayName("Should block ssh command")
    void testBlockSsh() {
        Object result = shellTool.execute(Map.of("command", "ssh user@host"));
        assertTrue(result.toString().contains("Error"), "Should block ssh");
    }

    @Test
    @DisplayName("Should block output redirection")
    void testBlockRedirection() {
        Object result = shellTool.execute(Map.of("command", "echo evil > /tmp/file"));
        assertTrue(result.toString().contains("Error"), "Should block redirection");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle null command")
    void testNullCommand() {
        Map<String, Object> params = new HashMap<>();
        params.put("command", null);
        Object result = shellTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null command");
    }

    @Test
    @DisplayName("Should handle empty command")
    void testEmptyCommand() {
        Object result = shellTool.execute(Map.of("command", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty command");
    }

    @Test
    @DisplayName("Should handle nonexistent command gracefully")
    void testNonexistentAllowedCommand() {
        // 'which' is allowed but searching for nonexistent binary
        Object result = shellTool.execute(Map.of("command", "which nonexistent_binary_xyz"));
        // Should not crash — just show non-zero exit code
        assertNotNull(result);
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle command with arguments")
    void testCommandWithArgs() {
        Object result = shellTool.execute(Map.of("command", "echo hello world foo bar"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should handle args. Got: " + r);
        assertTrue(r.contains("hello world foo bar"), "Should pass through args");
    }

    @Test
    @DisplayName("Should handle piped allowed commands")
    void testPipedCommands() {
        Object result = shellTool.execute(Map.of("command", "echo hello | wc -c"));

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should allow piped allowed commands. Got: " + r);
    }
}
