package ai.intelliswarm.swarmai.tool.hooks;

import ai.intelliswarm.swarmai.tool.base.ToolHook;
import ai.intelliswarm.swarmai.tool.base.ToolHookContext;
import ai.intelliswarm.swarmai.tool.base.ToolHookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Built-in tool hook that tracks tool call frequency and warns when
 * the rate exceeds a configurable threshold within a time window.
 */
public class RateLimitToolHook implements ToolHook {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitToolHook.class);

    private final int maxCalls;
    private final long windowMs;
    private final ConcurrentLinkedDeque<Long> callTimestamps = new ConcurrentLinkedDeque<>();

    public RateLimitToolHook(int maxCalls, int windowSeconds) {
        this.maxCalls = maxCalls;
        this.windowMs = windowSeconds * 1000L;
    }

    @Override
    public ToolHookResult beforeToolUse(ToolHookContext context) {
        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        // Evict expired timestamps
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() < cutoff) {
            callTimestamps.pollFirst();
        }

        callTimestamps.addLast(now);

        if (callTimestamps.size() > maxCalls) {
            String msg = String.format(
                    "[RATE-LIMIT] Tool '%s' exceeded %d calls in %d seconds (current: %d)",
                    context.toolName(), maxCalls, windowMs / 1000, callTimestamps.size());
            logger.warn(msg);
            return ToolHookResult.warn(msg);
        }

        return ToolHookResult.allow();
    }
}
