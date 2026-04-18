package ai.intelliswarm.swarmai.tool.instrument;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.event.SwarmEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Wraps every Spring AI tool function bean ({@code @Bean Function<Request, String>}) so every
 * invocation emits {@link SwarmEvent.Type#TOOL_STARTED} / {@link SwarmEvent.Type#TOOL_COMPLETED} /
 * {@link SwarmEvent.Type#TOOL_FAILED}.
 *
 * <p>Without this, Spring AI's native tool-resolution path (Agent calls {@code toolNames(...)})
 * bypasses {@code Agent.executeToolWithHooks}, so demo traces show 0 tool invocations even when
 * web-search / calculator / finnhub / sec_filings ran dozens of times.</p>
 *
 * <p>Scope filter: only wraps beans whose name looks like a tool identifier
 * ({@code [a-z][a-z0-9_]*}) — e.g. {@code calculator}, {@code web_search}, {@code sec_filings}.
 * Prevents accidental wrapping of application-level Function beans that happen to return String.</p>
 */
@Component
public class ToolEventInterceptor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ToolEventInterceptor.class);

    /** Max characters retained for tool input/output in event metadata (avoid flooding the bus). */
    private static final int MAX_CAPTURE = 2000;

    @PostConstruct
    public void announce() {
        // Visible-by-default log so we can confirm the interceptor is reachable
        // from the example's component scan. If this line is missing from an
        // example's stdout, the BeanPostProcessor never registered and no tool
        // function beans get wrapped — which is exactly the `tool_calls: 0`
        // failure mode seen in recorded demo traces.
        log.info("ToolEventInterceptor: registered — will wrap tool @Bean Function<…> beans whose name matches [a-z][a-z0-9_]*");
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof Function)) return bean;
        if (!looksLikeToolName(beanName)) return bean;

        final String toolName = beanName;
        final Function<Object, Object> original = (Function<Object, Object>) bean;

        log.info("ToolEventInterceptor: wrapping tool function bean '{}'", toolName);

        return (Function<Object, Object>) input -> {
            // Dedupe with BaseToolEventInterceptor: Spring AI Function beans typically
            // adapt to BaseTool.execute(...), so a single call reaches both layers.
            // Whoever enters first owns emission; the inner layer skips publishing.
            boolean owns = ToolEventEmissionGuard.tryEnter(toolName);
            long start = owns ? System.currentTimeMillis() : 0L;
            if (owns) publishStarted(toolName, input);
            try {
                Object output = original.apply(input);
                if (owns) publishCompleted(toolName, input, output, System.currentTimeMillis() - start);
                return output;
            } catch (RuntimeException e) {
                if (owns) publishFailed(toolName, input, e, System.currentTimeMillis() - start);
                throw e;
            } finally {
                if (owns) ToolEventEmissionGuard.leave(toolName);
            }
        };
    }

    private static boolean looksLikeToolName(String name) {
        // Tool function beans use snake_case identifiers. This filter keeps us from wrapping
        // arbitrary camelCase application Functions.
        return name != null && name.matches("[a-z][a-z0-9_]*");
    }

    private static void publishStarted(String tool, Object input) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        SwarmEventBus.publish(ToolEventInterceptor.class, SwarmEvent.Type.TOOL_STARTED,
                "Tool started: " + tool, null, md);
    }

    private static void publishCompleted(String tool, Object input, Object output, long durationMs) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        md.put("output", truncate(String.valueOf(output)));
        md.put("durationMs", durationMs);
        SwarmEventBus.publish(ToolEventInterceptor.class, SwarmEvent.Type.TOOL_COMPLETED,
                "Tool completed: " + tool, null, md);
    }

    private static void publishFailed(String tool, Object input, Throwable e, long durationMs) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        md.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        md.put("durationMs", durationMs);
        SwarmEventBus.publish(ToolEventInterceptor.class, SwarmEvent.Type.TOOL_FAILED,
                "Tool failed: " + tool, null, md);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_CAPTURE ? s.substring(0, MAX_CAPTURE) + "…" : s;
    }
}
