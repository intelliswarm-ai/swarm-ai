package ai.intelliswarm.swarmai.rl;

import ai.intelliswarm.swarmai.rl.bandit.BayesianWeightOptimizer;
import ai.intelliswarm.swarmai.rl.bandit.LinUCBBandit;
import ai.intelliswarm.swarmai.rl.bandit.NeuralLinUCBBandit;
import ai.intelliswarm.swarmai.rl.bandit.ThompsonSampling;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * RL-based policy that learns optimal decisions from experience.
 * Composes three bandit algorithms:
 * <ul>
 *   <li>{@link LinUCBBandit} for skill generation (4 actions, 8-dim state)</li>
 *   <li>{@link ThompsonSampling} for convergence (2 actions: CONTINUE/STOP)</li>
 *   <li>{@link BayesianWeightOptimizer} for skill selection weights</li>
 * </ul>
 *
 * <p>Cold-start behavior: delegates to {@link HeuristicPolicy} for the first N decisions
 * (configurable, default 50) to gather initial experience before the learned policy takes over.
 *
 * <pre>{@code
 * LearningPolicy policy = new LearningPolicy(50, 1.0, 10000);
 *
 * // Decisions — delegates to heuristic for first 50, then uses learned policy
 * SkillDecision decision = policy.shouldGenerateSkill(context);
 * boolean stop = policy.shouldStopIteration(convergenceContext);
 * double[] weights = policy.getSelectionWeights(selectionContext);
 *
 * // Record outcomes — feeds reward to bandit algorithms
 * policy.recordOutcome(decision, outcome);
 * }</pre>
 */
public class LearningPolicy implements PolicyEngine {

    private static final Logger logger = LoggerFactory.getLogger(LearningPolicy.class);

    private static final int SKILL_GEN_ACTIONS = 4; // GENERATE, GENERATE_SIMPLE, USE_EXISTING, SKIP
    private static final int CONVERGENCE_ACTIONS = 2; // CONTINUE, STOP
    private static final int SELECTION_DIMS = 3;     // relevance, effectiveness, quality

    private static final SkillGapAnalyzer.Recommendation[] SKILL_ACTIONS = {
            SkillGapAnalyzer.Recommendation.GENERATE,
            SkillGapAnalyzer.Recommendation.GENERATE_SIMPLE,
            SkillGapAnalyzer.Recommendation.USE_EXISTING,
            SkillGapAnalyzer.Recommendation.SKIP
    };

    private final HeuristicPolicy heuristicFallback;
    private final LinUCBBandit skillGenerationBandit;
    private final NeuralLinUCBBandit neuralSkillBandit; // null if not enabled
    private final ThompsonSampling convergenceSampler;
    private final BayesianWeightOptimizer selectionOptimizer;
    private final ExperienceBuffer experienceBuffer;
    private final int coldStartDecisions;
    private final AtomicInteger totalDecisions = new AtomicInteger(0);

    /**
     * Creates a LearningPolicy with default settings (LinUCB for skill generation).
     */
    public LearningPolicy() {
        this(50, 1.0, 10000);
    }

    /**
     * Creates a LearningPolicy with custom settings (LinUCB for skill generation).
     *
     * @param coldStartDecisions number of decisions to delegate to heuristic before learning
     * @param linucbAlpha        exploration parameter for LinUCB
     * @param bufferCapacity     experience buffer capacity
     */
    public LearningPolicy(int coldStartDecisions, double linucbAlpha, int bufferCapacity) {
        this(coldStartDecisions, linucbAlpha, bufferCapacity, null);
    }

    /**
     * Creates a LearningPolicy with an optional NeuralLinUCB for skill generation.
     * When neuralBandit is non-null, it is used instead of LinUCB after cold-start.
     *
     * @param coldStartDecisions number of decisions to delegate to heuristic before learning
     * @param linucbAlpha        exploration parameter for LinUCB (fallback / cold-start training)
     * @param bufferCapacity     experience buffer capacity
     * @param neuralBandit       optional NeuralLinUCB bandit (null = use plain LinUCB)
     */
    public LearningPolicy(int coldStartDecisions, double linucbAlpha, int bufferCapacity,
                           NeuralLinUCBBandit neuralBandit) {
        this.coldStartDecisions = coldStartDecisions;
        this.heuristicFallback = new HeuristicPolicy();
        this.skillGenerationBandit = new LinUCBBandit(
                SKILL_GEN_ACTIONS, SkillGenerationContext.featureDimension(), linucbAlpha);
        this.neuralSkillBandit = neuralBandit;
        this.convergenceSampler = new ThompsonSampling(CONVERGENCE_ACTIONS);
        this.selectionOptimizer = new BayesianWeightOptimizer(SELECTION_DIMS, 10, 0.1);
        this.experienceBuffer = new ExperienceBuffer(bufferCapacity);
    }

    @Override
    public SkillDecision shouldGenerateSkill(SkillGenerationContext context) {
        int count = totalDecisions.incrementAndGet();

        // Cold start: delegate to heuristic
        if (count <= coldStartDecisions) {
            SkillDecision heuristic = heuristicFallback.shouldGenerateSkill(context);
            // Still record the decision for future learning
            experienceBuffer.add("skill_generation", context.toFeatureVector(),
                    actionIndex(heuristic.recommendation()), 0.0); // reward filled later
            return heuristic;
        }

        // Use learned bandit (NeuralLinUCB if available, otherwise LinUCB)
        double[] state = context.toFeatureVector();
        int actionIdx;
        String banditName;
        double confidence;

        if (neuralSkillBandit != null) {
            actionIdx = neuralSkillBandit.selectAction(state);
            banditName = "NeuralLinUCB";
            confidence = 0.7; // NeuralLinUCB doesn't expose raw UCB scores
        } else {
            actionIdx = skillGenerationBandit.selectAction(state);
            double[] scores = skillGenerationBandit.getUCBScores(state);
            confidence = scores[actionIdx] / (Math.abs(scores[actionIdx]) + 1.0);
            banditName = "LinUCB";
        }

        SkillGapAnalyzer.Recommendation recommendation = SKILL_ACTIONS[actionIdx];
        String reasoning = String.format("%s selected %s (total decisions=%d)",
                banditName, recommendation, count);

        logger.debug("[RL] Skill generation: {} — {}", recommendation, reasoning);

        experienceBuffer.add("skill_generation", state, actionIdx, 0.0);

        return SkillDecision.of(recommendation, Math.max(0, Math.min(1, confidence)), reasoning);
    }

    @Override
    public boolean shouldStopIteration(ConvergenceContext context) {
        int count = totalDecisions.get();

        // Cold start: delegate to heuristic
        if (count <= coldStartDecisions) {
            return heuristicFallback.shouldStopIteration(context);
        }

        // Use Thompson Sampling: action 0 = CONTINUE, action 1 = STOP
        int action = convergenceSampler.selectAction();
        boolean shouldStop = (action == 1);

        logger.debug("[RL] Convergence: {} (mean CONTINUE={:.3f}, mean STOP={:.3f})",
                shouldStop ? "STOP" : "CONTINUE",
                convergenceSampler.getMean(0), convergenceSampler.getMean(1));

        experienceBuffer.add("convergence", context.toFeatureVector(), action, 0.0);

        return shouldStop;
    }

    @Override
    public double[] getSelectionWeights(SelectionContext context) {
        int count = totalDecisions.get();

        // Cold start: delegate to heuristic
        if (count <= coldStartDecisions) {
            return heuristicFallback.getSelectionWeights(context);
        }

        return selectionOptimizer.getNextWeights();
    }

    @Override
    public void recordOutcome(Decision decision, Outcome outcome) {
        switch (decision.type()) {
            case "skill_generation" -> {
                skillGenerationBandit.update(decision.stateVector(), decision.actionIndex(), outcome.reward());
                if (neuralSkillBandit != null) {
                    neuralSkillBandit.update(decision.stateVector(), decision.actionIndex(), outcome.reward());
                }
                experienceBuffer.add("skill_generation", decision.stateVector(),
                        decision.actionIndex(), outcome.reward());
                logger.debug("[RL] Skill generation reward: action={} reward={:.3f}",
                        decision.actionIndex(), outcome.reward());
            }
            case "convergence" -> {
                boolean success = outcome.reward() > 0;
                convergenceSampler.update(decision.actionIndex(), success);
                experienceBuffer.add("convergence", decision.stateVector(),
                        decision.actionIndex(), outcome.reward());
                logger.debug("[RL] Convergence reward: action={} success={}",
                        decision.actionIndex(), success);
            }
            case "selection" -> {
                selectionOptimizer.recordFitness(decision.stateVector(), outcome.reward());
                experienceBuffer.add("selection", decision.stateVector(),
                        decision.actionIndex(), outcome.reward());

                // Evolve selection weights periodically
                if (selectionOptimizer.getEvaluatedCount() % 10 == 0) {
                    selectionOptimizer.evolve();
                    logger.debug("[RL] Selection weights evolved. Best: {}",
                            java.util.Arrays.toString(selectionOptimizer.getBestWeights()));
                }
            }
        }
    }

    /**
     * Returns true if the policy is still in cold-start phase.
     */
    public boolean isColdStart() {
        return totalDecisions.get() <= coldStartDecisions;
    }

    /**
     * Returns the total number of decisions made.
     */
    public int getTotalDecisions() {
        return totalDecisions.get();
    }

    /**
     * Returns the experience buffer for persistence.
     */
    public ExperienceBuffer getExperienceBuffer() {
        return experienceBuffer;
    }

    /**
     * Returns summary statistics for monitoring.
     */
    public String getStats() {
        return String.format("LearningPolicy[decisions=%d, coldStart=%s, " +
                        "linucbUpdates=%d, tsCountContinue=%.0f, tsCountStop=%.0f, " +
                        "bestSelectionWeights=%s, bufferSize=%d]",
                totalDecisions.get(), isColdStart(),
                skillGenerationBandit.getTotalUpdates(),
                convergenceSampler.getCount(0), convergenceSampler.getCount(1),
                java.util.Arrays.toString(selectionOptimizer.getBestWeights()),
                experienceBuffer.size());
    }

    private int actionIndex(SkillGapAnalyzer.Recommendation recommendation) {
        return switch (recommendation) {
            case GENERATE -> 0;
            case GENERATE_SIMPLE -> 1;
            case USE_EXISTING -> 2;
            case SKIP -> 3;
        };
    }
}
