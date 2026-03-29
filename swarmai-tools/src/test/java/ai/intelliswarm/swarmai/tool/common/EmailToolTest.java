package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmailTool Tests")
class EmailToolTest {

    private EmailTool emailTool;

    @BeforeEach
    void setUp() {
        emailTool = new EmailTool(); // No mail sender configured
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("email_send", emailTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(emailTool.getDescription());
        assertTrue(emailTool.getDescription().contains("email"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() { assertFalse(emailTool.isAsync()); }

    @Test
    @DisplayName("Should have rate limit")
    void testMaxUsageCount() { assertEquals(10, emailTool.getMaxUsageCount()); }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = emailTool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("to"));
        assertTrue(properties.containsKey("subject"));
        assertTrue(properties.containsKey("body"));
        assertTrue(properties.containsKey("cc"));

        String[] required = (String[]) schema.get("required");
        assertEquals(3, required.length);
    }

    @Test
    @DisplayName("Should return error when mail not configured")
    void testNoMailConfigured() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "Test");
        params.put("body", "Hello");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error without mail config");
        assertTrue(result.toString().contains("not configured"), "Should explain. Got: " + result);
    }

    @Test
    @DisplayName("Should validate missing 'to'")
    void testMissingTo() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", null);
        params.put("subject", "Test");
        params.put("body", "Hello");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on missing to");
    }

    @Test
    @DisplayName("Should validate invalid email address")
    void testInvalidEmail() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", "not-an-email");
        params.put("subject", "Test");
        params.put("body", "Hello");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on invalid email");
        assertTrue(result.toString().contains("Invalid email"), "Should mention invalid. Got: " + result);
    }

    @Test
    @DisplayName("Should validate missing subject")
    void testMissingSubject() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "");
        params.put("body", "Hello");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on missing subject");
    }

    @Test
    @DisplayName("Should validate missing body")
    void testMissingBody() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "Test");
        params.put("body", "");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on missing body");
    }

    @Test
    @DisplayName("Should validate subject length")
    void testLongSubject() {
        Map<String, Object> params = new HashMap<>();
        params.put("to", "test@example.com");
        params.put("subject", "x".repeat(250));
        params.put("body", "Hello");

        Object result = emailTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on long subject");
        assertTrue(result.toString().contains("too long"), "Should mention too long");
    }
}
