package ai.intelliswarm.swarmai.tool.safety;

import ai.intelliswarm.swarmai.state.plan.BanditApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.BanditApprovalTrainer;
import ai.intelliswarm.swarmai.state.plan.DriftBudgetApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.InMemoryPlanChannelAccessor;
import ai.intelliswarm.swarmai.state.plan.PlanApprovalPolicy;
import ai.intelliswarm.swarmai.state.plan.PlanEntry;
import ai.intelliswarm.swarmai.state.plan.Risk;
import ai.intelliswarm.swarmai.state.plan.RiskTieredApprovalPolicy;
import ai.intelliswarm.swarmai.tool.windows.WindowsFileSystemTool;
import ai.intelliswarm.swarmai.tool.windows.internal.PathGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that the bandit trainer picks up real PLAN_POLICY → EXECUTED
 * transitions from a production tool ({@link WindowsFileSystemTool}) once that
 * tool calls {@code mutationGuard.observe(...)} via {@link GuardedOperation}.
 *
 * <p>Before the {@code observe} propagation in 1.0.20, the trainer's positive
 * reward path only fired in tests using stub tools. This test confirms the
 * fix: drive a real tool through a {@link PlanAwareMutationGuard}, watch the
 * bandit's bucket count grow with each successful operation.
 */
@DisplayName("Production tool → bandit training (integration test)")
class ProductionToolBanditTrainingIT {

    @Test
    @DisplayName("WindowsFileSystemTool's mkdir flows to bandit training via observe()")
    void mkdirAccumulatesBanditObservations(@TempDir Path tempDir) {
        // ---- Wire ----
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();

        // Pre-seed the channel so RiskTieredApprovalPolicy considers itself seeded
        // — without this, the first LOW-risk mkdir would route to human and the
        // trainer's PLAN_POLICY filter would skip it.
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:test").payload("seed")
                .risk(Risk.MEDIUM).build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "operator", "alice");

        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        BanditApprovalTrainer.attach(channel, bandit);

        PlanApprovalPolicy policy = PlanApprovalPolicy.all(
                new RiskTieredApprovalPolicy(),
                new DriftBudgetApprovalPolicy(100));

        MutationGuard humanFallback = plan ->
                MutationGuard.Decision.approve("alice", "operator approved");

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                humanFallback, channel, policy);

        // Allow the tempDir as a writable area
        PathGuard pathGuard = new PathGuard(java.util.List.of(tempDir.toString()));
        WindowsFileSystemTool tool = new WindowsFileSystemTool(pathGuard, guard, true);

        // ---- Drive 10 successful mkdir operations ----
        for (int i = 0; i < 10; i++) {
            Path target = tempDir.resolve("dir-" + i);
            Object result = tool.execute(Map.of(
                    "operation", "mkdir",
                    "path", target.toString(),
                    "apply", true));
            assertTrue(result.toString().startsWith("Created directory"),
                    "Expected mkdir success; got: " + result);
        }

        // ---- Verify bandit trained ----
        // Each mkdir is LOW risk; bucket key is "tool:os_filesystem:LOW".
        String bucketKey = "tool:" + tool.getFunctionName() + ":" + Risk.LOW;
        assertEquals(10, bandit.observationCount(bucketKey),
                "Bandit should have 10 observations from the PLAN_POLICY-decided mkdir flow");
        // 10 successes → mean ≈ 1.0 (Beta(11,1) gives 11/12 ≈ 0.917 with prior)
        assertTrue(bandit.meanSuccessRate(bucketKey) > 0.85,
                "Expected high mean after 10 successes; got " + bandit.meanSuccessRate(bucketKey));
    }

    @Test
    @DisplayName("when a tool's mutation throws, the bandit gets a failure observation")
    void failedMutationFeedsNegativeReward(@TempDir Path tempDir) {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        channel.append(PlanEntry.builder()
                .id("seed").proposedBy("tool:test").payload("seed")
                .risk(Risk.MEDIUM).build());
        channel.markStatus("seed", PlanEntry.Status.APPROVED, "operator", "alice");

        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, 0);
        BanditApprovalTrainer.attach(channel, bandit);

        PlanAwareMutationGuard guard = new PlanAwareMutationGuard(
                plan -> MutationGuard.Decision.approve("alice", "ok"),
                channel,
                PlanApprovalPolicy.all(new RiskTieredApprovalPolicy(),
                        new DriftBudgetApprovalPolicy(100)));

        PathGuard pathGuard = new PathGuard(java.util.List.of(tempDir.toString()));
        WindowsFileSystemTool tool = new WindowsFileSystemTool(pathGuard, guard, true);

        // First, do a successful mkdir to get the bucket trained positive
        for (int i = 0; i < 5; i++) {
            tool.execute(Map.of("operation", "mkdir",
                    "path", tempDir.resolve("ok-" + i).toString(),
                    "apply", true));
            // each mkdir is one site; the helper tracks observe automatically
        }

        // Now try to move a file that doesn't exist — that's an IllegalArgumentException
        // ("source must be a regular file"). The exception propagates as a
        // ToolException; the guard's observe() records the failure first.
        try {
            tool.execute(Map.of(
                    "op", "move",
                    "path", tempDir.resolve("nope.txt").toString(),
                    "to", tempDir.resolve("dst.txt").toString(),
                    "apply", true));
        } catch (RuntimeException expected) {
            // expected — the move source doesn't exist
        }

        String bucketKey = "tool:" + tool.getFunctionName() + ":" + Risk.LOW;
        // The 5 mkdirs succeeded → all positive; bucket should still be high mean
        assertEquals(5, bandit.observationCount(bucketKey));
        assertTrue(bandit.meanSuccessRate(bucketKey) > 0.85,
                "5 successful mkdirs should leave bucket strongly positive");
    }
}
