package ai.intelliswarm.swarmai.state.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BanditApprovalTrainer Tests")
class BanditApprovalTrainerTest {

    private static PlanEntry entry(String id, Risk risk) {
        return PlanEntry.builder()
                .id(id).proposedBy("tool:x").payload("op-" + id).risk(risk).build();
    }

    @Test
    @DisplayName("APPROVED → REJECTED transition feeds the bandit a negative reward")
    void rejectedAfterApproveTrainsNegative() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        // Drive 20 entries through APPROVED → REJECTED
        for (int i = 0; i < 20; i++) {
            channel.append(entry("e" + i, Risk.LOW));
            channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "policy", BanditApprovalTrainer.DEFAULT_SCORED_DECIDED_BY);
            channel.markStatus("e" + i, PlanEntry.Status.REJECTED, "operator override");
        }

        String key = "tool:x:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 20);
        // Mostly negatives → low success rate
        assertTrue(bandit.meanSuccessRate(key) < 0.2,
                "Expected low success rate after 20 negative trains, got " + bandit.meanSuccessRate(key));
    }

    @Test
    @DisplayName("APPROVED → EXECUTED transition feeds the bandit a positive reward")
    void executedAfterApproveTrainsPositive() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        for (int i = 0; i < 20; i++) {
            channel.append(entry("e" + i, Risk.LOW));
            channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "policy", BanditApprovalTrainer.DEFAULT_SCORED_DECIDED_BY);
            channel.markStatus("e" + i, PlanEntry.Status.EXECUTED, "ran ok");
        }

        String key = "tool:x:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) >= 20);
        assertTrue(bandit.meanSuccessRate(key) > 0.8,
                "Expected high success rate after 20 positive trains, got " + bandit.meanSuccessRate(key));
    }

    @Test
    @DisplayName("PROPOSED → REJECTED (never approved) does NOT feed the bandit — only post-approval transitions count")
    void proposedToRejectedDoesNotTrain() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        for (int i = 0; i < 5; i++) {
            channel.append(entry("e" + i, Risk.LOW));
            channel.markStatus("e" + i, PlanEntry.Status.REJECTED, "denied at gate");
        }

        String key = "tool:x:" + Risk.LOW;
        // The bucket might be lazily allocated to 0; in any case, no real observations.
        assertTrue(bandit.observationCount(key) == 0,
                "Expected no observations when entries skipped APPROVED, got " + bandit.observationCount(key));
    }

    @Test
    @DisplayName("APPROVED → SUPERSEDED is neutral — no reward signal")
    void supersedeIsNeutral() {
        InMemoryPlanChannelAccessor channel = new InMemoryPlanChannelAccessor();
        BanditApprovalPolicy bandit = new BanditApprovalPolicy(
                BanditApprovalPolicy.DEFAULT_BUCKET_KEY, /*warmup*/ 0);
        BanditApprovalTrainer.attach(channel, bandit);

        for (int i = 0; i < 5; i++) {
            channel.append(entry("e" + i, Risk.LOW));
            channel.markStatus("e" + i, PlanEntry.Status.APPROVED, "policy", BanditApprovalTrainer.DEFAULT_SCORED_DECIDED_BY);
            channel.markStatus("e" + i, PlanEntry.Status.SUPERSEDED, "replanner");
        }

        String key = "tool:x:" + Risk.LOW;
        assertTrue(bandit.observationCount(key) == 0,
                "Expected SUPERSEDED to be neutral, got " + bandit.observationCount(key));
    }
}
