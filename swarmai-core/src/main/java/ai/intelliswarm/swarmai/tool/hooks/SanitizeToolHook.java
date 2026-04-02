package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Built-in tool hook that redacts sensitive patterns (e.g. emails, phone numbers)
 * from tool output using regex replacement.
 */
public class SanitizeToolHook implements ToolHook {

    private static final Logger logger = LoggerFactory.getLogger(SanitizeToolHook.class);
    private static final String REDACTED = "[REDACTED]";

    private final List<Pattern> patterns;

    public SanitizeToolHook(List<Pattern> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    @Override
    public ToolHookResult afterToolUse(ToolHookContext context) {
        if (context.output() == null || context.hasError()) {
            return ToolHookResult.allow();
        }

        String sanitized = context.output();
        boolean modified = false;

        for (Pattern pattern : patterns) {
            String result = pattern.matcher(sanitized).replaceAll(REDACTED);
            if (!result.equals(sanitized)) {
                modified = true;
                sanitized = result;
            }
        }

        if (modified) {
            logger.info("[SANITIZE] Redacted sensitive content from tool '{}' output", context.toolName());
            return ToolHookResult.withModifiedOutput(sanitized);
        }

        return ToolHookResult.allow();
    }
}
