package ai.intelliswarm.swarmai.governance;

import ai.intelliswarm.swarmai.process.Process;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("GovernanceInterceptor Tests")
class GovernanceInterceptorTest {

    private Process mockDelegate;
    private WorkflowGovernanceEngine mockEngine;
    private SwarmOutput mockOutput;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(Process.class);
        mockEngine = mock(WorkflowGovernanceEngine.class);

        mockOutput = SwarmOutput.builder()
                .swarmId("test-swarm")
                .rawOutput("test output")
                .successful(true)
                .build();

        when(mockDelegate.execute(any(), any(Map.class), anyString())).thenReturn(mockOutput);
        when(mockDelegate.getType()).thenReturn(ProcessType.SEQUENTIAL);
        when(mockDelegate.isAsync()).thenReturn(false);
    }

    private Task createTestTask(String id) {
        return Task.builder()
                .id(id)
                .description("Test task " + id)
                .build();
    }

    private ApprovalGate createGate(String name, GateTrigger trigger) {
        return ApprovalGate.builder()
                .name(name)
                .description("Test gate: " + name)
                .trigger(trigger)
                .timeout(Duration.ofMinutes(5))
                .policy(new ApprovalPolicy(1, List.of(), true))
                .build();
    }

    @Nested
    @DisplayName("No gates = direct delegation")
    class NoGatesTests {

        @Test
        @DisplayName("delegates directly when no gates are configured")
        void execute_noGates_delegatesDirectly() {
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            List<Task> tasks = List.of(createTestTask("task-1"));
            SwarmOutput result = interceptor.execute(tasks, Map.of(), "swarm-1");

            assertSame(mockOutput, result);
            verify(mockDelegate).execute(tasks, Map.of(), "swarm-1");
            verifyNoInteractions(mockEngine);
        }

        @Test
        @DisplayName("delegates directly when gates have non-task triggers only")
        void execute_nonTaskGatesOnly_delegatesDirectly() {
            ApprovalGate budgetGate = createGate("budget-gate", GateTrigger.ON_BUDGET_WARNING);
            ApprovalGate skillGate = createGate("skill-gate", GateTrigger.BEFORE_SKILL_PROMOTION);

            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of(budgetGate, skillGate));

            List<Task> tasks = List.of(createTestTask("task-1"));
            SwarmOutput result = interceptor.execute(tasks, Map.of(), "swarm-1");

            assertSame(mockOutput, result);
            verify(mockDelegate).execute(tasks, Map.of(), "swarm-1");
            verifyNoInteractions(mockEngine);
        }

        @Test
        @DisplayName("delegates directly when null gates list is provided")
        void execute_nullGates_delegatesDirectly() {
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, null);

            List<Task> tasks = List.of(createTestTask("task-1"));
            SwarmOutput result = interceptor.execute(tasks, Map.of(), "swarm-1");

            assertSame(mockOutput, result);
            verifyNoInteractions(mockEngine);
        }
    }

    @Nested
    @DisplayName("BEFORE_TASK gate pauses before execution")
    class BeforeTaskGateTests {

        @Test
        @DisplayName("checks BEFORE_TASK gate for each task before delegating")
        void execute_withBeforeTaskGate_checksBeforeExecution() {
            ApprovalGate beforeGate = createGate("pre-check", GateTrigger.BEFORE_TASK);

            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of(beforeGate));

            List<Task> tasks = List.of(
                    createTestTask("task-1"),
                    createTestTask("task-2")
            );

            interceptor.execute(tasks, Map.of(), "swarm-1");

            // Engine should be called twice (once per task) for BEFORE_TASK
            verify(mockEngine, times(2)).checkGate(eq(beforeGate), any(GovernanceContext.class));
            // Delegate should still be called after gates pass
            verify(mockDelegate).execute(tasks, Map.of(), "swarm-1");
        }

        @Test
        @DisplayName("does not delegate if BEFORE_TASK gate throws")
        void execute_withBeforeTaskGateRejection_doesNotDelegate() {
            ApprovalGate beforeGate = createGate("strict-pre-check", GateTrigger.BEFORE_TASK);

            doThrow(new GovernanceException(
                    "Gate rejected", beforeGate.gateId(), "req-1", ApprovalStatus.REJECTED))
                    .when(mockEngine).checkGate(eq(beforeGate), any(GovernanceContext.class));

            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of(beforeGate));

            List<Task> tasks = List.of(createTestTask("task-1"));

            assertThrows(GovernanceException.class, () ->
                    interceptor.execute(tasks, Map.of(), "swarm-1"));

            // Delegate should NOT be called since the gate threw
            verify(mockDelegate, never()).execute(any(), any(Map.class), anyString());
        }
    }

    @Nested
    @DisplayName("AFTER_TASK gate checks after execution")
    class AfterTaskGateTests {

        @Test
        @DisplayName("checks AFTER_TASK gate for each task after delegating")
        void execute_withAfterTaskGate_checksAfterExecution() {
            ApprovalGate afterGate = createGate("post-check", GateTrigger.AFTER_TASK);

            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of(afterGate));

            List<Task> tasks = List.of(
                    createTestTask("task-1"),
                    createTestTask("task-2")
            );

            interceptor.execute(tasks, Map.of(), "swarm-1");

            // Delegate should be called first
            verify(mockDelegate).execute(tasks, Map.of(), "swarm-1");
            // Then engine should be called twice (once per task) for AFTER_TASK
            verify(mockEngine, times(2)).checkGate(eq(afterGate), any(GovernanceContext.class));
        }
    }

    @Nested
    @DisplayName("getType() delegates correctly")
    class DelegationTests {

        @Test
        @DisplayName("getType() returns delegate's type")
        void getType_returnsDelegateType() {
            when(mockDelegate.getType()).thenReturn(ProcessType.SEQUENTIAL);
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            assertEquals(ProcessType.SEQUENTIAL, interceptor.getType());
        }

        @Test
        @DisplayName("getType() returns HIERARCHICAL when delegate is hierarchical")
        void getType_returnsHierarchical() {
            when(mockDelegate.getType()).thenReturn(ProcessType.HIERARCHICAL);
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            assertEquals(ProcessType.HIERARCHICAL, interceptor.getType());
        }

        @Test
        @DisplayName("isAsync() returns delegate's async status")
        void isAsync_returnsDelegateValue() {
            when(mockDelegate.isAsync()).thenReturn(true);
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            assertTrue(interceptor.isAsync());
        }

        @Test
        @DisplayName("validateTasks() delegates to wrapped process")
        void validateTasks_delegatesToWrapped() {
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());
            List<Task> tasks = List.of(createTestTask("task-1"));

            interceptor.validateTasks(tasks);

            verify(mockDelegate).validateTasks(tasks);
        }

        @Test
        @DisplayName("interpolateInputs() delegates to wrapped process")
        void interpolateInputs_delegatesToWrapped() {
            when(mockDelegate.interpolateInputs(anyString(), any())).thenReturn("interpolated");
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            String result = interceptor.interpolateInputs("template {var}", Map.of("var", "value"));

            assertEquals("interpolated", result);
            verify(mockDelegate).interpolateInputs("template {var}", Map.of("var", "value"));
        }

        @Test
        @DisplayName("getDelegate() returns the wrapped process")
        void getDelegate_returnsWrappedProcess() {
            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of());

            assertSame(mockDelegate, interceptor.getDelegate());
        }
    }

    @Nested
    @DisplayName("Mixed gate triggers")
    class MixedGateTests {

        @Test
        @DisplayName("handles both BEFORE_TASK and AFTER_TASK gates")
        void execute_withBothGates_checksBothBeforeAndAfter() {
            ApprovalGate beforeGate = createGate("pre-check", GateTrigger.BEFORE_TASK);
            ApprovalGate afterGate = createGate("post-check", GateTrigger.AFTER_TASK);

            GovernanceInterceptor interceptor = new GovernanceInterceptor(
                    mockDelegate, mockEngine, List.of(beforeGate, afterGate));

            List<Task> tasks = List.of(createTestTask("task-1"));

            interceptor.execute(tasks, Map.of(), "swarm-1");

            // Before gate checked once, after gate checked once
            verify(mockEngine).checkGate(eq(beforeGate), any(GovernanceContext.class));
            verify(mockEngine).checkGate(eq(afterGate), any(GovernanceContext.class));
            verify(mockDelegate).execute(tasks, Map.of(), "swarm-1");
        }
    }
}
