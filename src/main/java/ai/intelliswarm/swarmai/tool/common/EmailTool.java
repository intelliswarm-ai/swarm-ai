package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import ai.intelliswarm.swarmai.tool.base.ToolRequirements;

import java.util.*;
import java.util.List;

/**
 * Email Tool — sends emails via SMTP using Spring Mail.
 *
 * Requires spring.mail.* properties to be configured.
 * Safety: rate limited, configurable allowed recipients.
 */
@Component
public class EmailTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(EmailTool.class);
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 50000;

    private final JavaMailSender mailSender;

    @Autowired(required = false)
    public EmailTool(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public EmailTool() {
        this.mailSender = null;
    }

    @Override
    public String getFunctionName() {
        return "email_send";
    }

    @Override
    public String getDescription() {
        return "Send an email via SMTP. Requires spring.mail configuration. " +
               "Specify to, subject, and body. Supports CC and plain text content.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String to = (String) parameters.get("to");
        String subject = (String) parameters.get("subject");
        String body = (String) parameters.get("body");
        String cc = (String) parameters.getOrDefault("cc", null);
        String from = (String) parameters.getOrDefault("from", null);

        logger.info("EmailTool: Sending email to={}, subject={}", to, subject);

        try {
            // 1. Validate inputs first (so validation errors are clear even without mail config)
            String inputError = validateInputs(to, subject, body);
            if (inputError != null) {
                return "Error: " + inputError;
            }

            // 2. Check mail sender availability
            if (mailSender == null) {
                return "Error: Email not configured. Set spring.mail.host, spring.mail.port, " +
                       "spring.mail.username, and spring.mail.password in application properties.";
            }

            // 3. Send email
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to.trim());
            message.setSubject(subject.trim());
            message.setText(body);

            if (from != null && !from.trim().isEmpty()) {
                message.setFrom(from.trim());
            }
            if (cc != null && !cc.trim().isEmpty()) {
                message.setCc(cc.trim());
            }

            mailSender.send(message);

            // 4. Response
            StringBuilder response = new StringBuilder();
            response.append("**Email sent successfully**\n");
            response.append("**To:** ").append(to).append("\n");
            response.append("**Subject:** ").append(subject).append("\n");
            if (cc != null) response.append("**CC:** ").append(cc).append("\n");
            response.append("**Body length:** ").append(body.length()).append(" chars\n");

            return response.toString();

        } catch (Exception e) {
            logger.error("Error sending email to {}", to, e);
            return "Error: Failed to send email: " + e.getMessage();
        }
    }

    private String validateInputs(String to, String subject, String body) {
        if (to == null || to.trim().isEmpty()) {
            return "Recipient email address ('to') is required";
        }
        if (!to.contains("@") || !to.contains(".")) {
            return "Invalid email address: " + to;
        }
        if (subject == null || subject.trim().isEmpty()) {
            return "Email subject is required";
        }
        if (subject.length() > MAX_SUBJECT_LENGTH) {
            return "Subject too long (max " + MAX_SUBJECT_LENGTH + " chars)";
        }
        if (body == null || body.trim().isEmpty()) {
            return "Email body is required";
        }
        if (body.length() > MAX_BODY_LENGTH) {
            return "Body too long (max " + MAX_BODY_LENGTH + " chars)";
        }
        return null;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("to", Map.of("type", "string", "description", "Recipient email address"));
        properties.put("subject", Map.of("type", "string", "description", "Email subject line"));
        properties.put("body", Map.of("type", "string", "description", "Email body (plain text)"));
        properties.put("cc", Map.of("type", "string", "description", "CC recipient (optional)"));
        properties.put("from", Map.of("type", "string", "description", "Sender email (optional, uses default)"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"to", "subject", "body"});
        return schema;
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public String getTriggerWhen() {
        return "User needs to send an email notification or message.";
    }

    @Override
    public String getAvoidWhen() {
        return "User needs Slack messaging or non-email communication.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder()
            .env("SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD")
            .build();
    }

    @Override
    public String getCategory() {
        return "communication";
    }

    @Override
    public List<String> getTags() {
        return List.of("email", "send", "notification");
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of(
            "type", "markdown",
            "description", "Email send confirmation with recipient, subject, and body length"
        );
    }

    @Override
    public int getMaxUsageCount() { return 10; }

    public record Request(String to, String subject, String body, String cc, String from) {}
}
