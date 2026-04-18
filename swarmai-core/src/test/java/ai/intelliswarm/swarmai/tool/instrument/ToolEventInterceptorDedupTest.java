package ai.intelliswarm.swarmai.tool.instrument;

import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.event.SwarmEventBus;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedup behaviour between {@link ToolEventInterceptor} (Function-bean wrapper) and
 * {@link BaseToolEventInterceptor} (BaseTool proxy).
 *
 * <p>The two interceptors coexist because different Spring AI code paths drive them;
 * however in the common case — a {@code @Bean Function<Req, String>} whose body calls
 * {@code tool.execute(...)} on a {@link BaseTool} — both run on the same invocation.
 * Without deduping, each call would publish {@code TOOL_STARTED/COMPLETED/FAILED} twice,
 * inflating tool-call metrics and timeline rows.</p>
 */
@DisplayName("ToolEventInterceptor + BaseToolEventInterceptor dedup")
class ToolEventInterceptorDedupTest {

    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        SwarmEventBus.setPublisher(publisher);
    }

    @AfterEach
    void tearDown() {
        SwarmEventBus.setPublisher(null);
    }

    @Nested
    @DisplayName("when Function bean delegates to a BaseTool")
    class NestedInvocationTests {

        @Test
        @DisplayName("publishes exactly one TOOL_STARTED and one TOOL_COMPLETED per call")
        void nestedSuccessEmitsSingleEventPair() {
            StubBaseTool tool = new StubBaseTool("calculator", params -> "42");
            BaseTool proxiedTool = wrapBaseTool(tool, "calculator");

            Function<Object, Object> functionBean = input ->
                    proxiedTool.execute(Map.of("expression", String.valueOf(input)));
            @SuppressWarnings("unchecked")
            Function<Object, Object> wrappedFunction =
                    (Function<Object, Object>) new ToolEventInterceptor()
                            .postProcessAfterInitialization(functionBean, "calculator");

            Object result = wrappedFunction.apply("2+2");

            assertEquals("42", result);
            assertEquals(1, tool.executeCalls, "Underlying execute must run exactly once");
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_STARTED),
                    "Nested interceptors must not duplicate TOOL_STARTED");
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_COMPLETED),
                    "Nested interceptors must not duplicate TOOL_COMPLETED");
            assertEquals(0, publisher.countOf(SwarmEvent.Type.TOOL_FAILED));
        }

        @Test
        @DisplayName("publishes exactly one TOOL_FAILED when the tool throws")
        void nestedFailureEmitsSingleFailedEvent() {
            StubBaseTool tool = new StubBaseTool("calculator", params -> {
                throw new IllegalStateException("boom");
            });
            BaseTool proxiedTool = wrapBaseTool(tool, "calculator");

            Function<Object, Object> functionBean = input ->
                    proxiedTool.execute(Map.of("expression", String.valueOf(input)));
            @SuppressWarnings("unchecked")
            Function<Object, Object> wrappedFunction =
                    (Function<Object, Object>) new ToolEventInterceptor()
                            .postProcessAfterInitialization(functionBean, "calculator");

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> wrappedFunction.apply("bad"));
            assertTrue(thrown.getMessage().contains("boom"));
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_STARTED));
            assertEquals(0, publisher.countOf(SwarmEvent.Type.TOOL_COMPLETED));
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_FAILED),
                    "Failure must surface once, not twice");
        }

        @Test
        @DisplayName("guard is released after a successful call (no ThreadLocal leak)")
        void guardIsReleasedBetweenCalls() {
            StubBaseTool tool = new StubBaseTool("calculator", params -> "ok");
            BaseTool proxiedTool = wrapBaseTool(tool, "calculator");
            Function<Object, Object> functionBean = input ->
                    proxiedTool.execute(Map.of("expression", String.valueOf(input)));
            @SuppressWarnings("unchecked")
            Function<Object, Object> wrappedFunction =
                    (Function<Object, Object>) new ToolEventInterceptor()
                            .postProcessAfterInitialization(functionBean, "calculator");

            wrappedFunction.apply("1");
            wrappedFunction.apply("2");
            wrappedFunction.apply("3");

            assertEquals(3, publisher.countOf(SwarmEvent.Type.TOOL_STARTED));
            assertEquals(3, publisher.countOf(SwarmEvent.Type.TOOL_COMPLETED));
            assertTrue(ToolEventEmissionGuard.tryEnter("calculator"),
                    "Guard must be empty between top-level calls");
            ToolEventEmissionGuard.leave("calculator");
        }

        @Test
        @DisplayName("guard is released after a failing call")
        void guardIsReleasedAfterFailure() {
            StubBaseTool tool = new StubBaseTool("calculator", params -> {
                throw new IllegalStateException("boom");
            });
            BaseTool proxiedTool = wrapBaseTool(tool, "calculator");
            Function<Object, Object> functionBean = input ->
                    proxiedTool.execute(Map.of("expression", String.valueOf(input)));
            @SuppressWarnings("unchecked")
            Function<Object, Object> wrappedFunction =
                    (Function<Object, Object>) new ToolEventInterceptor()
                            .postProcessAfterInitialization(functionBean, "calculator");

            assertThrows(RuntimeException.class, () -> wrappedFunction.apply("x"));

            assertTrue(ToolEventEmissionGuard.tryEnter("calculator"),
                    "Guard must clear even when tool throws");
            ToolEventEmissionGuard.leave("calculator");
        }
    }

    @Nested
    @DisplayName("when only one interceptor is in the call path")
    class SingleLayerTests {

        @Test
        @DisplayName("BaseTool-only call publishes one event pair")
        void baseToolOnlyStillEmitsEvents() {
            StubBaseTool tool = new StubBaseTool("web_search", params -> "results");
            BaseTool proxiedTool = wrapBaseTool(tool, "web_search");

            Object result = proxiedTool.execute(Map.of("query", "anthropic"));

            assertEquals("results", result);
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_STARTED));
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_COMPLETED));
        }

        @Test
        @DisplayName("pure Function bean (no BaseTool underneath) still emits one event pair")
        void functionWithoutBaseToolEmitsEvents() {
            Function<Object, Object> pureFunction = input -> "output:" + input;
            @SuppressWarnings("unchecked")
            Function<Object, Object> wrapped =
                    (Function<Object, Object>) new ToolEventInterceptor()
                            .postProcessAfterInitialization(pureFunction, "custom_fn");

            Object result = wrapped.apply("hello");

            assertEquals("output:hello", result);
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_STARTED));
            assertEquals(1, publisher.countOf(SwarmEvent.Type.TOOL_COMPLETED));
        }
    }

    // ---------------- helpers ----------------

    private static BaseTool wrapBaseTool(BaseTool raw, String beanName) {
        return (BaseTool) new BaseToolEventInterceptor()
                .postProcessAfterInitialization(raw, beanName);
    }

    /** Minimal BaseTool stub: delegates execute() to a lambda and counts invocations.
     *  Non-final so CGLIB ({@link BaseToolEventInterceptor}) can subclass it. */
    private static class StubBaseTool implements BaseTool {
        private final String name;
        private final Function<Map<String, Object>, Object> body;
        int executeCalls;

        StubBaseTool(String name, Function<Map<String, Object>, Object> body) {
            this.name = name;
            this.body = body;
        }

        @Override public String getFunctionName() { return name; }
        @Override public String getDescription() { return "stub tool " + name; }
        @Override public Map<String, Object> getParameterSchema() { return Map.of(); }
        @Override public boolean isAsync() { return false; }

        @Override
        public Object execute(Map<String, Object> parameters) {
            executeCalls++;
            return body.apply(parameters);
        }
    }

    /** Captures every SwarmEvent the bus dispatches so tests can assert per-type counts. */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        private final List<SwarmEvent> events = new ArrayList<>();
        private final Map<SwarmEvent.Type, Integer> counts = new EnumMap<>(SwarmEvent.Type.class);

        @Override
        public void publishEvent(Object event) {
            if (event instanceof SwarmEvent se) {
                events.add(se);
                counts.merge(se.getType(), 1, Integer::sum);
            }
        }

        int countOf(SwarmEvent.Type type) {
            return counts.getOrDefault(type, 0);
        }
    }
}
