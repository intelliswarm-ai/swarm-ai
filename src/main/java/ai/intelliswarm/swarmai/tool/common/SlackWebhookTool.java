package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Slack Webhook Tool — sends messages to Slack channels via incoming webhooks.
 *
 * Requires a Slack webhook URL (configured via property or passed as parameter).
 */
@Component
public class SlackWebhookTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(SlackWebhookTool.class);
    private static final int MAX_MESSAGE_LENGTH = 40000; // Slack limit

    @Value("${slack.webhook.url:}")
    private String defaultWebhookUrl;

    private final RestTemplate restTemplate;

    public SlackWebhookTool() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getFunctionName() {
        return "slack_webhook";
    }

    @Override
    public String getDescription() {
        return "Send a message to a Slack channel via incoming webhook. " +
               "Supports markdown formatting. Configure slack.webhook.url or pass webhook_url parameter.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String message = (String) parameters.get("message");
        String webhookUrl = (String) parameters.getOrDefault("webhook_url", defaultWebhookUrl);
        String channel = (String) parameters.getOrDefault("channel", null);
        String username = (String) parameters.getOrDefault("username", "SwarmAI");

        logger.info("SlackWebhookTool: Sending message ({} chars) via webhook", message != null ? message.length() : 0);

        try {
            // 1. Validate
            if (message == null || message.trim().isEmpty()) {
                return "Error: Message is required";
            }
            if (message.length() > MAX_MESSAGE_LENGTH) {
                return "Error: Message too long (max " + MAX_MESSAGE_LENGTH + " chars)";
            }
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                return "Error: Slack webhook URL not configured. Set slack.webhook.url in properties or pass webhook_url parameter.";
            }
            if (!webhookUrl.startsWith("https://hooks.slack.com/")) {
                return "Error: Invalid webhook URL. Must start with https://hooks.slack.com/";
            }

            // 2. Build payload
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", message);
            if (username != null) payload.put("username", username);
            if (channel != null) payload.put("channel", channel);

            // 3. Send
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                StringBuilder result = new StringBuilder();
                result.append("**Slack message sent successfully**\n");
                result.append("**Length:** ").append(message.length()).append(" chars\n");
                if (channel != null) result.append("**Channel:** ").append(channel).append("\n");
                result.append("**Username:** ").append(username).append("\n");
                return result.toString();
            } else {
                return "Error: Slack returned HTTP " + response.getStatusCode() + ": " + response.getBody();
            }

        } catch (Exception e) {
            logger.error("Error sending Slack message", e);
            return "Error: Failed to send Slack message: " + e.getMessage();
        }
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("message", Map.of("type", "string", "description", "Message to send (supports Slack markdown)"));
        properties.put("webhook_url", Map.of("type", "string", "description", "Slack webhook URL (optional if configured in properties)"));
        properties.put("channel", Map.of("type", "string", "description", "Override channel (e.g., #general)"));
        properties.put("username", Map.of("type", "string", "description", "Display name (default: SwarmAI)"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"message"});
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public int getMaxUsageCount() { return 20; }

    public record Request(String message, String webhookUrl, String channel, String username) {}
}
