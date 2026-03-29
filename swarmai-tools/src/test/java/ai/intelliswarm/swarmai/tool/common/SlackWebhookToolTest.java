package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlackWebhookTool Tests")
class SlackWebhookToolTest {

    private SlackWebhookTool slackTool;

    @BeforeEach
    void setUp() {
        slackTool = new SlackWebhookTool();
    }

    @Test
    @DisplayName("Should return correct function name")
    void testGetFunctionName() {
        assertEquals("slack_webhook", slackTool.getFunctionName());
    }

    @Test
    @DisplayName("Should return correct description")
    void testGetDescription() {
        assertNotNull(slackTool.getDescription());
        assertTrue(slackTool.getDescription().contains("Slack"));
    }

    @Test
    @DisplayName("Should not be async")
    void testIsAsync() { assertFalse(slackTool.isAsync()); }

    @Test
    @DisplayName("Should have rate limit")
    void testMaxUsageCount() { assertEquals(20, slackTool.getMaxUsageCount()); }

    @Test
    @DisplayName("Should have correct parameter schema")
    void testGetParameterSchema() {
        Map<String, Object> schema = slackTool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("message"));
        assertTrue(properties.containsKey("webhook_url"));
        assertTrue(properties.containsKey("channel"));

        String[] required = (String[]) schema.get("required");
        assertEquals(1, required.length);
        assertEquals("message", required[0]);
    }

    @Test
    @DisplayName("Should error when no webhook URL configured")
    void testNoWebhookUrl() {
        Object result = slackTool.execute(Map.of("message", "Hello Slack!"));
        assertTrue(result.toString().contains("Error"), "Should error without webhook URL");
        assertTrue(result.toString().contains("not configured"), "Should explain. Got: " + result);
    }

    @Test
    @DisplayName("Should validate missing message")
    void testMissingMessage() {
        Map<String, Object> params = new HashMap<>();
        params.put("message", null);
        params.put("webhook_url", "https://hooks.slack.com/services/xxx");

        Object result = slackTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on null message");
    }

    @Test
    @DisplayName("Should validate empty message")
    void testEmptyMessage() {
        Map<String, Object> params = new HashMap<>();
        params.put("message", "");
        params.put("webhook_url", "https://hooks.slack.com/services/xxx");

        Object result = slackTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on empty message");
    }

    @Test
    @DisplayName("Should validate webhook URL format")
    void testInvalidWebhookUrl() {
        Map<String, Object> params = new HashMap<>();
        params.put("message", "Hello");
        params.put("webhook_url", "https://evil.com/webhook");

        Object result = slackTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should reject non-Slack URL");
        assertTrue(result.toString().contains("Invalid webhook"), "Should mention invalid. Got: " + result);
    }

    @Test
    @DisplayName("Should validate message length")
    void testLongMessage() {
        Map<String, Object> params = new HashMap<>();
        params.put("message", "x".repeat(50001));
        params.put("webhook_url", "https://hooks.slack.com/services/xxx");

        Object result = slackTool.execute(params);
        assertTrue(result.toString().contains("Error"), "Should error on too-long message");
        assertTrue(result.toString().contains("too long"), "Should mention too long");
    }

    @Test
    @DisplayName("Should accept valid Slack webhook URL format")
    void testValidWebhookUrlFormat() {
        Map<String, Object> params = new HashMap<>();
        params.put("message", "Test message");
        params.put("webhook_url", "https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXX");

        Object result = slackTool.execute(params);
        // Should fail on HTTP connection, not on URL validation
        String r = result.toString();
        assertFalse(r.contains("Invalid webhook"), "Should accept valid Slack URL format. Got: " + r);
    }
}
