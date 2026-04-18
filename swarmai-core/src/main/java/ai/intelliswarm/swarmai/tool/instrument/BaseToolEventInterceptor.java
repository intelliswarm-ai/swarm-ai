package ai.intelliswarm.swarmai.tool.instrument;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.event.SwarmEventBus;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps every {@link BaseTool} bean with a CGLIB proxy that emits
 * {@link SwarmEvent.Type#TOOL_STARTED}, {@link SwarmEvent.Type#TOOL_COMPLETED}, and
 * {@link SwarmEvent.Type#TOOL_FAILED} around each {@link BaseTool#execute(Map)} invocation.
 *
 * <p><strong>Why this exists alongside {@link ToolEventInterceptor}</strong>: Spring AI's
 * tool-calling path in 1.0.x no longer goes through the {@code @Bean Function<Request, String>}
 * layer for most tools — it adapts {@link BaseTool} directly into a ToolCallback and invokes
 * {@code execute(...)}. That makes the Function-level wrapper a no-op at runtime. Wrapping at
 * the BaseTool layer catches every invocation regardless of which adapter Spring AI picks.</p>
 *
 * <p>CGLIB is used (via {@code setProxyTargetClass(true)}) so any code doing
 * {@code @Autowired SECFilingsTool} keeps working — JDK dynamic proxies only implement the
 * interface and would break concrete-class injection.</p>
 */
@Component
public class BaseToolEventInterceptor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(BaseToolEventInterceptor.class);

    /** Max characters retained for tool input/output in event metadata (avoid flooding the bus). */
    private static final int MAX_CAPTURE = 2000;

    @PostConstruct
    public void announce() {
        log.info("BaseToolEventInterceptor: registered — will wrap every BaseTool bean's execute(...) with TOOL_STARTED/COMPLETED/FAILED events");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof BaseTool tool)) return bean;

        final String toolName = safeToolName(tool, beanName);
        log.info("BaseToolEventInterceptor: wrapping BaseTool '{}' (bean='{}', class={})",
                toolName, beanName, bean.getClass().getSimpleName());

        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true); // CGLIB so @Autowired ConcreteTool still resolves
        factory.addAdvice((MethodInterceptor) invocation -> {
            if (!"execute".equals(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            Object input = invocation.getArguments().length > 0 ? invocation.getArguments()[0] : null;
            // Dedupe with ToolEventInterceptor: when a Spring AI Function bean delegates
            // to this BaseTool's execute, we would otherwise emit TOOL_* events twice.
            boolean owns = ToolEventEmissionGuard.tryEnter(toolName);
            long start = owns ? System.currentTimeMillis() : 0L;
            if (owns) publishStarted(toolName, input);
            try {
                Object output = invocation.proceed();
                if (owns) publishCompleted(toolName, input, output, System.currentTimeMillis() - start);
                return output;
            } catch (Throwable t) {
                if (owns) publishFailed(toolName, input, t, System.currentTimeMillis() - start);
                throw t;
            } finally {
                if (owns) ToolEventEmissionGuard.leave(toolName);
            }
        });
        return factory.getProxy();
    }

    private static String safeToolName(BaseTool tool, String fallback) {
        try {
            String n = tool.getFunctionName();
            return (n != null && !n.isBlank()) ? n : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void publishStarted(String tool, Object input) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        SwarmEventBus.publish(BaseToolEventInterceptor.class, SwarmEvent.Type.TOOL_STARTED,
                "Tool started: " + tool, null, md);
    }

    private static void publishCompleted(String tool, Object input, Object output, long durationMs) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        md.put("output", truncate(String.valueOf(output)));
        md.put("durationMs", durationMs);
        SwarmEventBus.publish(BaseToolEventInterceptor.class, SwarmEvent.Type.TOOL_COMPLETED,
                "Tool completed: " + tool, null, md);
    }

    private static void publishFailed(String tool, Object input, Throwable e, long durationMs) {
        if (!SwarmEventBus.isActive()) return;
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tool", tool);
        md.put("input", truncate(String.valueOf(input)));
        md.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        md.put("durationMs", durationMs);
        SwarmEventBus.publish(BaseToolEventInterceptor.class, SwarmEvent.Type.TOOL_FAILED,
                "Tool failed: " + tool, null, md);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_CAPTURE ? s.substring(0, MAX_CAPTURE) + "…" : s;
    }
}
