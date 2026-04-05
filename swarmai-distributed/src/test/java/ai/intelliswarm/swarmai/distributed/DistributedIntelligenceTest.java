package ai.intelliswarm.swarmai.distributed;

import ai.intelliswarm.swarmai.distributed.consensus.RaftNode;
import ai.intelliswarm.swarmai.distributed.execution.DistributedIntelligence;
import ai.intelliswarm.swarmai.distributed.execution.DistributedIntelligence.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class DistributedIntelligenceTest {

    private RaftNode leader;
    private DistributedIntelligence intelligence;

    @BeforeEach
    void setUp() {
        // single-node cluster — leader can propose and commit immediately
        RaftNode.MessageTransport noop = (t, m) -> {};
        leader = new RaftNode("leader", noop);
        leader.start();
        leader.startElection(); // become leader in single-node cluster

        intelligence = new DistributedIntelligence(leader);
    }

    @AfterEach
    void tearDown() {
        leader.stop();
    }

    @Test
    void shouldShareSkillAcrossCluster() {
        List<SharedSkill> received = new CopyOnWriteArrayList<>();
        intelligence.onSkillShared(received::add);

        intelligence.shareSkill("skill-1", "web_search_first", "TOOL_SELECTION",
                0.85, Map.of("pattern", "always search before HTTP request"));

        // in single-node cluster, committed immediately
        assertEquals(1, intelligence.sharedSkills().size());
        assertEquals(1, received.size());
        assertEquals("web_search_first", received.get(0).skillName());
        assertEquals("leader", received.get(0).sourceNode());
    }

    @Test
    void shouldAcceptHighConfidenceImprovementRule() {
        List<ImprovementRule> accepted = new CopyOnWriteArrayList<>();
        intelligence.onRuleAccepted(accepted::add);

        intelligence.proposeImprovementRule("rule-1", "CONVERGENCE",
                Map.of("max_depth", "<=3"), "Reduce maxIterations to 3",
                0.90, 10);

        // high confidence (0.90 >= 0.85) + sufficient observations (10 >= 5) → auto-accepted
        assertEquals(1, intelligence.acceptedRules().size());
        assertEquals(1, accepted.size());
        assertEquals("Reduce maxIterations to 3", accepted.get(0).recommendation());
    }

    @Test
    void shouldNotAcceptLowConfidenceRule() {
        intelligence.proposeImprovementRule("rule-low", "TOOL_SELECTION",
                Map.of(), "Untested pattern", 0.50, 2);

        assertEquals(0, intelligence.acceptedRules().size());
    }

    @Test
    void shouldShareConvergenceInsight() {
        List<ConvergenceInsight> insights = new CopyOnWriteArrayList<>();
        intelligence.onConvergenceInsight(insights::add);

        intelligence.shareConvergenceInsight("insight-1", "maxIterations", 3,
                35.0, Map.of("task_count", "<=3", "has_skill_gen", false));

        assertEquals(1, intelligence.convergenceInsights().size());
        assertEquals(1, insights.size());
        assertEquals("maxIterations", insights.get(0).parameterName());
        assertEquals(3, insights.get(0).optimalValue());
        assertEquals(35.0, insights.get(0).improvementPercent());
    }

    @Test
    void shouldTrackSkillFeedbackFromMultipleNodes() {
        // share a skill
        intelligence.shareSkill("skill-2", "parallel_decompose", "ORCHESTRATION",
                0.75, Map.of());

        // report feedback from simulated nodes
        intelligence.reportSkillFeedback("skill-2", true, 0.88, 5000, "data-pipeline");
        intelligence.reportSkillFeedback("skill-2", true, 0.92, 7200, "security-audit");
        intelligence.reportSkillFeedback("skill-2", true, 0.85, 4800, "code-review");

        // skill should have 3 feedback entries
        var snapshot = intelligence.snapshot();
        assertEquals(3, snapshot.totalSkillFeedbackEntries());
    }

    @Test
    void shouldProduceIntelligenceSnapshot() {
        intelligence.shareSkill("s1", "skill-a", "CAT", 0.9, Map.of());
        intelligence.shareSkill("s2", "skill-b", "CAT", 0.8, Map.of());
        intelligence.proposeImprovementRule("r1", "X", Map.of(), "rec", 0.90, 10);
        intelligence.shareConvergenceInsight("i1", "temp", 0.3, 20.0, Map.of());

        ClusterIntelligenceSnapshot snapshot = intelligence.snapshot();

        assertEquals(2, snapshot.totalSharedSkills());
        assertEquals(1, snapshot.totalAcceptedRules());
        assertEquals(1, snapshot.totalConvergenceInsights());
    }

    @Test
    void shouldNotShareSkillIfNotLeader() {
        // create a follower node
        RaftNode follower = new RaftNode("follower", (t, m) -> {});
        follower.start();
        DistributedIntelligence followerIntel = new DistributedIntelligence(follower);

        try {
            followerIntel.shareSkill("s1", "test", "CAT", 0.9, Map.of());
            // follower can't propose — skill won't be shared
            assertEquals(0, followerIntel.sharedSkills().size());
        } finally {
            follower.stop();
        }
    }

    @Test
    void shouldShareMultipleSkillsAndRules() {
        // simulate a rich intelligence exchange
        intelligence.shareSkill("tool-order", "search_before_request", "TOOL_SELECTION",
                0.88, Map.of("before", "web_search", "after", "http_request"));

        intelligence.shareSkill("prompt-cache", "cache_similar_prompts", "COST_OPTIMIZATION",
                0.72, Map.of("similarity_threshold", 0.95));

        intelligence.proposeImprovementRule("convergence-shallow", "CONVERGENCE",
                Map.of("task_count", "<=3", "has_skill_gen", false),
                "Set maxIterations=3 for shallow workflows", 0.92, 15);

        intelligence.proposeImprovementRule("budget-split", "BUDGET",
                Map.of("process_type", "PARALLEL"),
                "Allocate 60% budget to first task, 40% to remaining", 0.87, 8);

        intelligence.shareConvergenceInsight("temp-code", "temperature", 0.2,
                18.0, Map.of("task_type", "code_generation"));

        intelligence.shareConvergenceInsight("temp-creative", "temperature", 0.7,
                12.0, Map.of("task_type", "creative_writing"));

        var snapshot = intelligence.snapshot();
        assertEquals(2, snapshot.totalSharedSkills());
        assertEquals(2, snapshot.totalAcceptedRules());
        assertEquals(2, snapshot.totalConvergenceInsights());
    }
}
