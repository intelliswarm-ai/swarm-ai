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
    private final LinUCBBandit convergenceBandit;
    private final ThompsonSampling convergenceSampler;
    private final BayesianWeightOptimizer selectionOptimizer;
    private final ExperienceBuffer experienceBuffer;
    private final int coldStartDecisions;
    private final AtomicInteger totalDecisions = new AtomicInteger(0);

    /**
     * Rewards actually received, per decision type. A bandit that has never been fed a reward
     * has learned nothing: LinUCB still has A=I and b=0, so every action's UCB score ties and
     * argmax silently collapses onto action 0; Thompson Sampling still has Beta(1,1) priors, so
     * it coin-flips. Gating on decisions made (the previous behaviour) meant that a caller who
     * forgot to wire {@link #recordOutcome} got a policy that looked trained and was in fact
     * choosing degenerately — strictly worse than the heuristic it replaced.
     *
     * <p>So cold-start is governed by FEEDBACK, not by activity: until a decision type has
     * received {@link #MIN_REWARDS_TO_TRUST} rewards, that decision defers to the heuristic.
     * This makes the learning policy safe by default — its worst case is the heuristic.
     */
    private final AtomicInteger skillGenerationRewards = new AtomicInteger(0);
    private final AtomicInteger convergenceRewards = new AtomicInteger(0);

    /** Rewards a decision type must have seen before its learned model is trusted. */
    private static final int MIN_REWARDS_TO_TRUST = 10;

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
        // Convergence is a CONTEXTUAL decision — whether to stop depends on output growth, gap
        // repetition and whether new skills appeared. The old ThompsonSampling here was
        // non-contextual: it never looked at ConvergenceContext at all and chose by sampling a
        // Beta prior, which is how an unfed policy ended up halting healthy runs at random.
        this.convergenceBandit = new LinUCBBandit(
                CONVERGENCE_ACTIONS, ConvergenceContext.featureDimension(), linucbAlpha);
        this.convergenceSampler = new ThompsonSampling(CONVERGENCE_ACTIONS);
        this.selectionOptimizer = new BayesianWeightOptimizer(SELECTION_DIMS, 10, 0.1);
        this.experienceBuffer = new ExperienceBuffer(bufferCapacity);
    }

    @Override
    public SkillDecision shouldGenerateSkill(SkillGenerationContext context) {
        int count = totalDecisions.incrementAndGet();

        // Cold start: delegate to heuristic until the model has BOTH been exercised and
        // actually been taught something. Without the reward check, a caller that never wires
        // recordOutcome graduates into an untrained LinUCB whose UCB scores all tie — which
        // degenerates to "always GENERATE". See MIN_REWARDS_TO_TRUST.
        if (count <= coldStartDecisions || skillGenerationRewards.get() < MIN_REWARDS_TO_TRUST) {
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

        // Cold start: defer to the heuristic until the convergence model has actually been
        // taught something (see MIN_REWARDS_TO_TRUST).
        if (count <= coldStartDecisions || convergenceRewards.get() < MIN_REWARDS_TO_TRUST) {
            return heuristicFallback.shouldStopIteration(context);
        }

        // SAFETY FLOOR: never halt a run that is visibly still improving, whatever the model
        // says. Stopping early silently truncates the user's workflow and destroys work that
        // was about to pay off — an asymmetric, hard-to-notice failure. Exploration is fine on
        // a stale run; it is not fine on a productive one.
        if (isClearlyProgressing(context)) {
            logger.debug("[RL] Convergence: CONTINUE (safety floor — run is still improving)");
            return false;
        }

        // Contextual: action 0 = CONTINUE, action 1 = STOP
        double[] state = context.toFeatureVector();
        int action = convergenceBandit.selectAction(state);
        boolean shouldStop = (action == 1);

        logger.debug("[RL] Convergence: {} (contextual LinUCB, rewards seen={})",
                shouldStop ? "STOP" : "CONTINUE", convergenceRewards.get());

        experienceBuffer.add("convergence", state, action, 0.0);

        return shouldStop;
    }

    /**
     * True when the signals unambiguously say the loop is still doing useful work: the output
     * is growing meaningfully AND the agent is not just re-reporting the same gaps.
     */
    private boolean isClearlyProgressing(ConvergenceContext context) {
        boolean outputGrew = context.outputGrowthRate() > OUTPUT_GROWTH_FLOOR;
        boolean gapsAreFresh = context.gapRepetitionRate() < 0.99;
        boolean producedSkills = context.newSkillsThisIteration() > 0;
        return outputGrew && (gapsAreFresh || producedSkills);
    }

    /** Matches HeuristicPolicy's growth threshold — below this, growth is noise. */
    private static final double OUTPUT_GROWTH_FLOOR = 1.1;

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
                skillGenerationRewards.incrementAndGet();
                experienceBuffer.add("skill_generation", decision.stateVector(),
                        decision.actionIndex(), outcome.reward());
                logger.debug("[RL] Skill generation reward: action={} reward={:.3f}",
                        decision.actionIndex(), outcome.reward());
            }
            case "convergence" -> {
                boolean success = outcome.reward() > 0;
                convergenceBandit.update(decision.stateVector(), decision.actionIndex(), outcome.reward());
                convergenceSampler.update(decision.actionIndex(), success); // kept for getStats()
                convergenceRewards.incrementAndGet();
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
     * Returns true if the policy is still in cold-start phase — i.e. still deferring to the
     * heuristic. This is true both before {@code coldStartDecisions} decisions have been made
     * AND whenever the models have not yet received enough rewards to have learned anything.
     */
    public boolean isColdStart() {
        return totalDecisions.get() <= coldStartDecisions
                || skillGenerationRewards.get() < MIN_REWARDS_TO_TRUST;
    }

    /**
     * True when the policy is making decisions but has received no feedback — meaning the
     * caller has not wired {@link #recordOutcome} and this policy can never learn. Surfaced so
     * that operators (and {@code SelfImprovingProcess}) can warn loudly rather than silently
     * running a policy that will never improve.
     */
    public boolean isRewardLoopOpen() {
        return totalDecisions.get() > coldStartDecisions
                && skillGenerationRewards.get() == 0;
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
