package ai.intelliswarm.swarmai.selfimproving.evolution;

import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import ai.intelliswarm.swarmai.selfimproving.model.SpecificObservation;
import ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution;
import ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution.EvolutionType;
import ai.intelliswarm.swarmai.selfimproving.model.SwarmEvolution.TopologySnapshot;
import ai.intelliswarm.swarmai.selfimproving.model.WorkflowShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Processes INTERNAL observations and produces self-evolution actions.
 *
 * <p>Unlike the proposal pipeline (which reports to intelliswarm.ai for
 * framework code changes), the evolution engine applies optimizations
 * using capabilities the framework already has:
 *
 * <ul>
 *   <li>PROCESS_SUITABILITY → switch processType (SEQUENTIAL → PARALLEL)</li>
 *   <li>CONVERGENCE_PATTERN → adjust maxIterations default</li>
 *   <li>TOOL_SELECTION → update tool routing hints</li>
 *   <li>EXPENSIVE_TASK → rebalance token allocation</li>
 *   <li>SUCCESSFUL_SKILL → promote skill to built-in library</li>
 * </ul>
 *
 * <p>Each evolution produces a {@link SwarmEvolution} snapshot capturing the
 * before/after topology. These are persisted to H2 for cross-JVM learning
 * and rendered in Studio as an architecture evolution timeline.
 */
public class EvolutionEngine {

    private static final Logger log = LoggerFactory.getLogger(EvolutionEngine.class);

    private final LedgerStore ledgerStore;

    public EvolutionEngine(LedgerStore ledgerStore) {
        this.ledgerStore = ledgerStore;
    }

    /**
     * Process internal observations and produce evolution actions.
     * Returns the list of evolutions that should be applied to the swarm.
     */
    public List<SwarmEvolution> evolve(String swarmId,
                                        List<SpecificObservation> observations,
                                        WorkflowShape currentShape) {
        List<SwarmEvolution> evolutions = new ArrayList<>();

        for (SpecificObservation obs : observations) {
            if (obs.type() == null || !obs.type().isInternal()) continue;

            SwarmEvolution evolution = switch (obs.type()) {
                case PROCESS_SUITABILITY -> evolveProcessType(swarmId, obs, currentShape);
                case CONVERGENCE_PATTERN -> evolveConvergence(swarmId, obs, currentShape);
                case TOOL_SELECTION -> evolveToolRouting(swarmId, obs, currentShape);
                case EXPENSIVE_TASK -> evolveTokenBudget(swarmId, obs, currentShape);
                case SUCCESSFUL_SKILL -> evolveSkillPromotion(swarmId, obs, currentShape);
                default -> null;
            };

            if (evolution != null) {
                evolutions.add(evolution);
                persistEvolution(evolution);
            }
        }

        if (!evolutions.isEmpty()) {
            log.info("[{}] Self-evolution: {} optimizations identified ({})",
                    swarmId, evolutions.size(),
                    evolutions.stream()
                            .map(e -> e.type().name())
                            .distinct()
                            .reduce((a, b) -> a + ", " + b)
                            .orElse(""));
        }

        return evolutions;
    }

    private SwarmEvolution evolveProcessType(String swarmId, SpecificObservation obs,
                                              WorkflowShape shape) {
        // Sequential workflow with independent tasks → should be parallel
        TopologySnapshot before = TopologySnapshot.from(shape, Map.of(
                "processType", shape != null ? shape.processType() : "SEQUENTIAL"
        ));
        TopologySnapshot after = TopologySnapshot.from(shape, Map.of(
                "processType", "PARALLEL",
                "expectedLatencyReduction", "~" + (shape != null ? (shape.taskCount() - 1) * 100 / Math.max(shape.taskCount(), 1) : 0) + "%"
        ));

        return SwarmEvolution.create(swarmId, EvolutionType.PROCESS_TYPE_CHANGE,
                before, after, obs.description(), obs.type().name(), obs.impact());
    }

    private SwarmEvolution evolveConvergence(String swarmId, SpecificObservation obs,
                                              WorkflowShape shape) {
        Object convergedAt = obs.evidence().get("converged_at");
        Object maxIter = obs.evidence().get("max_iterations");

        TopologySnapshot before = TopologySnapshot.from(shape, Map.of(
                "maxIterations", maxIter != null ? maxIter : "unknown"
        ));
        TopologySnapshot after = TopologySnapshot.from(shape, Map.of(
                "maxIterations", convergedAt != null ? convergedAt : "unknown",
                "reason", "Early convergence detected — reduce max iterations to save tokens"
        ));

        return SwarmEvolution.create(swarmId, EvolutionType.CONVERGENCE_ADJUSTMENT,
                before, after, obs.description(), obs.type().name(), obs.impact());
    }

    private SwarmEvolution evolveToolRouting(String swarmId, SpecificObservation obs,
                                             WorkflowShape shape) {
        String toolName = (String) obs.evidence().getOrDefault("tool_name", "unknown");
        Object successRate = obs.evidence().get("success_rate");

        TopologySnapshot before = TopologySnapshot.from(shape, Map.of(
                "toolRouting", Map.of(toolName, "enabled")
        ));
        TopologySnapshot after = TopologySnapshot.from(shape, Map.of(
                "toolRouting", Map.of(toolName, "deprioritized"),
                "reason", "Success rate " + successRate + " below 50% threshold"
        ));

        return SwarmEvolution.create(swarmId, EvolutionType.TOOL_ROUTING_UPDATE,
                before, after, obs.description(), obs.type().name(), obs.impact());
    }

    private SwarmEvolution evolveTokenBudget(String swarmId, SpecificObservation obs,
                                              WorkflowShape shape) {
        Object tokenRatio = obs.evidence().get("token_ratio");
        Object turnCount = obs.evidence().get("turn_count");

        TopologySnapshot before = TopologySnapshot.from(shape, Map.of(
                "tokenDistribution", "uniform"
        ));
        TopologySnapshot after = TopologySnapshot.from(shape, Map.of(
                "tokenDistribution", "weighted",
                "reason", "Task consuming " + (tokenRatio != null ? String.format("%.0f%%", ((Number) tokenRatio).doubleValue() * 100) : "?") + " of budget",
                "suggestedMaxTurns", turnCount != null ? Math.max(1, ((Number) turnCount).intValue() / 2) : 2
        ));

        return SwarmEvolution.create(swarmId, EvolutionType.TOKEN_BUDGET_REBALANCE,
                before, after, obs.description(), obs.type().name(), obs.impact());
    }

    private SwarmEvolution evolveSkillPromotion(String swarmId, SpecificObservation obs,
                                                WorkflowShape shape) {
        String skillName = (String) obs.evidence().getOrDefault("skill_name", "unknown");

        TopologySnapshot before = TopologySnapshot.from(shape, Map.of(
                "skill", Map.of(skillName, "runtime-generated")
        ));
        TopologySnapshot after = TopologySnapshot.from(shape, Map.of(
                "skill", Map.of(skillName, "built-in"),
                "qualityScore", obs.evidence().getOrDefault("quality_score", 0.0),
                "reuseCount", obs.evidence().getOrDefault("reuse_count", 0)
        ));

        return SwarmEvolution.create(swarmId, EvolutionType.SKILL_PROMOTION,
                before, after, obs.description(), obs.type().name(), obs.impact());
    }

    private void persistEvolution(SwarmEvolution evolution) {
        try {
            ledgerStore.recordEvolution(evolution);
        } catch (Exception e) {
            log.debug("Failed to persist evolution (non-fatal): {}", e.getMessage());
        }
    }
}
