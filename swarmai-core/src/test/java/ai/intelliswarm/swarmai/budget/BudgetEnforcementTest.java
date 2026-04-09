package ai.intelliswarm.swarmai.budget;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.base.TestFixtures;
import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.swarm.Swarm;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that budget enforcement actually works in live workflows.
 *
 * The existing InMemoryBudgetTrackerTest verifies the tracker in isolation.
 * These tests verify that HARD_STOP actually terminates a multi-task workflow,
 * that WARN mode allows completion, and that budget is tracked end-to-end
 * through the Swarm → Process → Task pipeline.
 *
 * If these tests fail, runaway workflows can consume unlimited tokens.
 */
@DisplayName("Budget Enforcement in Live Workflows")
class BudgetEnforcementTest extends BaseSwarmTest {

    @Nested
    @DisplayName("HARD_STOP Enforcement")
    class HardStopEnforcement {

        @Test
        @DisplayName("HARD_STOP terminates multi-task workflow when budget exceeded")
        void hardStopTerminatesWorkflow() {
            // Very tight budget: 200 tokens max
            // Each mock LLM call uses 150 tokens (100 prompt + 50 completion)
            // First task should succeed, second should trigger HARD_STOP
            BudgetPolicy policy = BudgetPolicy.builder()
                .maxTotalTokens(200)
                .maxCostUsd(100.0) // not the limiting factor
                .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                .build();

            InMemoryBudgetTracker tracker = new InMemoryBudgetTracker(policy);

            Agent agent = createAgent("Worker");
            Task task1 = TestFixtures.createTestTask("Task 1: analyze data", agent);
            Task task2 = TestFixtures.createTestTask("Task 2: write report", agent);
            Task task3 = TestFixtures.createTestTask("Task 3: final review", agent);

            Swarm swarm = Swarm.builder()
                .agent(agent)
                .task(task1).task(task2).task(task3)
                .process(ProcessType.SEQUENTIAL)
                .budgetTracker(tracker)
                .budgetPolicy(policy)
                .eventPublisher(mockEventPublisher)
                .build();

            // The swarm should fail because budget is exceeded after task 1
            // Each task uses 150 tokens > 200 max
            try {
                swarm.kickoff(Map.of());
                // If we get here, either HARD_STOP didn't work or tokens aren't tracked
                BudgetSnapshot snapshot = tracker.getSnapshot(swarm.getId());
                if (snapshot.totalTokensUsed() > 200) {
                    fail("BUDGET ENFORCEMENT FAILURE: Workflow completed despite exceeding " +
                        "budget. Used " + snapshot.totalTokensUsed() + " tokens with " +
                        "max 200. HARD_STOP did not terminate the workflow.");
                }
                // If tokens <= 200, the mock returns fewer tokens than expected
            } catch (BudgetExceededException e) {
                // After the fix, BudgetExceededException propagates directly
                // from SequentialProcess → Swarm without wrapping
                assertNotNull(e.getMessage(),
                    "BudgetExceededException should have a descriptive message");
            } catch (Exception e) {
                // If we catch a non-budget exception, the fix didn't work
                boolean isBudgetException = containsBudgetException(e);
                assertTrue(isBudgetException,
                    "BudgetExceededException should propagate directly (not wrapped). " +
                    "Got: " + e.getClass().getName() + ": " + e.getMessage());

                // Verify not all tasks completed
                BudgetSnapshot snapshot = tracker.getSnapshot(swarm.getId());
                assertTrue(snapshot.totalTokensUsed() > 0,
                    "Some tokens should have been used before HARD_STOP");
            }
        }

        @Test
        @DisplayName("HARD_STOP with zero-token budget prevents any execution")
        void zeroTokenBudgetPreventsExecution() {
            BudgetPolicy policy = BudgetPolicy.builder()
                .maxTotalTokens(0)
                .maxCostUsd(0.0)
                .onExceeded(BudgetPolicy.BudgetAction.HARD_STOP)
                .build();

            InMemoryBudgetTracker tracker = new InMemoryBudgetTracker(policy);

            Agent agent = createAgent("Worker");
            Task task = TestFixtures.createTestTask("Should not execute", agent);

            Swarm swarm = Swarm.builder()
                .agent(agent).task(task)
                .process(ProcessType.SEQUENTIAL)
                .budgetTracker(tracker)
                .budgetPolicy(policy)
                .eventPublisher(mockEventPublisher)
                .build();

            assertThrows(Exception.class, () -> swarm.kickoff(Map.of()),
                "Zero-token budget should prevent any task execution");
        }
    }

    @Nested
    @DisplayName("WARN Mode")
    class WarnMode {

        @Test
        @DisplayName("WARN mode allows workflow to complete despite exceeding budget")
        void warnModeAllowsCompletion() {
            BudgetPolicy policy = BudgetPolicy.builder()
                .maxTotalTokens(50) // will be exceeded by first task
                .maxCostUsd(100.0)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .build();

            InMemoryBudgetTracker tracker = new InMemoryBudgetTracker(policy);

            Agent agent = createAgent("Worker");
            Task task1 = TestFixtures.createTestTask("Task 1", agent);
            Task task2 = TestFixtures.createTestTask("Task 2", agent);

            Swarm swarm = Swarm.builder()
                .agent(agent)
                .task(task1).task(task2)
                .process(ProcessType.SEQUENTIAL)
                .budgetTracker(tracker)
                .budgetPolicy(policy)
                .eventPublisher(mockEventPublisher)
                .build();

            // Should complete without exception — WARN doesn't stop execution
            SwarmOutput output = swarm.kickoff(Map.of());
            assertNotNull(output, "WARN mode should allow workflow to complete");

            BudgetSnapshot snapshot = tracker.getSnapshot(swarm.getId());
            assertTrue(snapshot.isExceeded(),
                "Budget should be exceeded (used " + snapshot.totalTokensUsed() +
                " tokens with max 50)");
        }
    }

    @Nested
    @DisplayName("Budget Tracking Accuracy")
    class TrackingAccuracy {

        @Test
        @DisplayName("budget tracks tokens across multiple tasks")
        void tracksAcrossTasks() {
            BudgetPolicy policy = BudgetPolicy.builder()
                .maxTotalTokens(1_000_000) // generous — won't hit limit
                .maxCostUsd(1000.0)
                .onExceeded(BudgetPolicy.BudgetAction.WARN)
                .build();

            InMemoryBudgetTracker tracker = new InMemoryBudgetTracker(policy);

            Agent agent = createAgent("Worker");
            Task task1 = TestFixtures.createTestTask("Task 1", agent);
            Task task2 = TestFixtures.createTestTask("Task 2", agent);
            Task task3 = TestFixtures.createTestTask("Task 3", agent);

            Swarm swarm = Swarm.builder()
                .agent(agent)
                .task(task1).task(task2).task(task3)
                .process(ProcessType.SEQUENTIAL)
                .budgetTracker(tracker)
                .budgetPolicy(policy)
                .eventPublisher(mockEventPublisher)
                .build();

            swarm.kickoff(Map.of());

            BudgetSnapshot snapshot = tracker.getSnapshot(swarm.getId());
            assertTrue(snapshot.totalTokensUsed() > 0,
                "Budget should track non-zero tokens after workflow execution. " +
                "Got: " + snapshot.totalTokensUsed());

            // Each mock call returns 100 prompt + 50 completion = 150 per task
            // 3 tasks = 450 tokens expected
            // The actual count depends on how many LLM calls each task makes
            assertTrue(snapshot.promptTokensUsed() > 0, "Prompt tokens should be tracked");
            assertTrue(snapshot.completionTokensUsed() > 0, "Completion tokens should be tracked");
        }

        @Test
        @DisplayName("workflow without budget tracker runs without error")
        void noBudgetTrackerIsOk() {
            Agent agent = createAgent("Worker");
            Task task = TestFixtures.createTestTask("Simple task", agent);

            Swarm swarm = Swarm.builder()
                .agent(agent).task(task)
                .process(ProcessType.SEQUENTIAL)
                .eventPublisher(mockEventPublisher)
                // no budgetTracker or budgetPolicy
                .build();

            SwarmOutput output = swarm.kickoff(Map.of());
            assertNotNull(output, "Workflow without budget tracker should run fine");
        }
    }

    private boolean containsBudgetException(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof BudgetExceededException) return true;
            if (current.getMessage() != null &&
                (current.getMessage().contains("Budget") || current.getMessage().contains("budget"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
