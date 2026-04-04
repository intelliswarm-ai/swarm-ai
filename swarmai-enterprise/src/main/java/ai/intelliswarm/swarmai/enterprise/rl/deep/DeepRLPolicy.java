package ai.intelliswarm.swarmai.enterprise.rl.deep;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.intelliswarm.swarmai.rl.*;
import ai.intelliswarm.swarmai.skill.SkillGapAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deep RL policy using DQN (Deep Q-Network) for decision making.
 * Implements {@link PolicyEngine} with neural network-based Q-value estimation.
 *
 * <p>Features:
 * <ul>
 *   <li>Epsilon-greedy exploration with linear decay</li>
 *   <li>Separate policy and target networks for stability</li>
 *   <li>Prioritized experience replay</li>
 *   <li>Cold-start delegation to {@link HeuristicPolicy}</li>
 *   <li>Async mini-batch training after every N decisions</li>
 * </ul>
 *
 * <pre>{@code
 * // Via Spring auto-config:
 * swarmai.deep-rl.enabled=true
 *
 * // Or programmatically:
 * DeepRLPolicy policy = new DeepRLPolicy(config);
 * SkillDecision decision = policy.shouldGenerateSkill(context);
 * }</pre>
 */
public class DeepRLPolicy implements PolicyEngine {

    private static final Logger logger = LoggerFactory.getLogger(DeepRLPolicy.class);

    private static final int SKILL_GEN_ACTIONS = 4;
    private static final int CONVERGENCE_ACTIONS = 2;

    private static final SkillGapAnalyzer.Recommendation[] SKILL_ACTIONS = {
            SkillGapAnalyzer.Recommendation.GENERATE,
            SkillGapAnalyzer.Recommendation.GENERATE_SIMPLE,
            SkillGapAnalyzer.Recommendation.USE_EXISTING,
            SkillGapAnalyzer.Recommendation.SKIP
    };

    private final NDManager manager;
    private final HeuristicPolicy heuristicFallback;
    private final Random random;

    // Skill generation DQN (8 inputs → 4 outputs)
    private final DQNNetwork skillPolicyNet;
    private final DQNNetwork skillTargetNet;
    private final NetworkTrainer skillTrainer;
    private final ReplayBuffer skillReplayBuffer;

    // Convergence DQN (6 inputs → 2 outputs)
    private final DQNNetwork convergencePolicyNet;
    private final DQNNetwork convergenceTargetNet;
    private final NetworkTrainer convergenceTrainer;
    private final ReplayBuffer convergenceReplayBuffer;

    // Exploration
    private final double epsilonStart;
    private final double epsilonEnd;
    private final int epsilonDecaySteps;
    private final int trainInterval;
    private final int targetUpdateInterval;
    private final int coldStartDecisions;
    private final AtomicInteger totalDecisions = new AtomicInteger(0);

    public DeepRLPolicy(DeepRLConfig config) {
        this.manager = NDManager.newBaseManager();
        this.heuristicFallback = new HeuristicPolicy();
        this.random = new Random();

        int hiddenSize = config.hiddenSize();

        // Skill generation networks
        this.skillPolicyNet = new DQNNetwork(manager,
                SkillGenerationContext.featureDimension(), hiddenSize, hiddenSize / 2, SKILL_GEN_ACTIONS);
        this.skillTargetNet = new DQNNetwork(manager,
                SkillGenerationContext.featureDimension(), hiddenSize, hiddenSize / 2, SKILL_GEN_ACTIONS);
        skillTargetNet.copyFrom(skillPolicyNet);
        this.skillTrainer = new NetworkTrainer(manager, skillPolicyNet, skillTargetNet,
                config.learningRate(), config.gamma());
        this.skillReplayBuffer = new ReplayBuffer(config.bufferCapacity());

        // Convergence networks
        this.convergencePolicyNet = new DQNNetwork(manager,
                ConvergenceContext.featureDimension(), hiddenSize / 4, hiddenSize / 8, CONVERGENCE_ACTIONS);
        this.convergenceTargetNet = new DQNNetwork(manager,
                ConvergenceContext.featureDimension(), hiddenSize / 4, hiddenSize / 8, CONVERGENCE_ACTIONS);
        convergenceTargetNet.copyFrom(convergencePolicyNet);
        this.convergenceTrainer = new NetworkTrainer(manager, convergencePolicyNet, convergenceTargetNet,
                config.learningRate(), config.gamma());
        this.convergenceReplayBuffer = new ReplayBuffer(config.bufferCapacity());

        this.epsilonStart = config.epsilonStart();
        this.epsilonEnd = config.epsilonEnd();
        this.epsilonDecaySteps = config.epsilonDecaySteps();
        this.trainInterval = config.trainInterval();
        this.targetUpdateInterval = config.targetUpdateInterval();
        this.coldStartDecisions = config.coldStartDecisions();

        logger.info("[DeepRL] Initialized DQN policy: skill net [{} → {} → {} → {}], " +
                        "convergence net [{} → {} → {} → {}]",
                SkillGenerationContext.featureDimension(), hiddenSize, hiddenSize / 2, SKILL_GEN_ACTIONS,
                ConvergenceContext.featureDimension(), hiddenSize / 4, hiddenSize / 8, CONVERGENCE_ACTIONS);
    }

    @Override
    public SkillDecision shouldGenerateSkill(SkillGenerationContext context) {
        int step = totalDecisions.incrementAndGet();

        // Cold start
        if (step <= coldStartDecisions) {
            return heuristicFallback.shouldGenerateSkill(context);
        }

        double[] stateVec = context.toFeatureVector();
        NDArray state = StateEncoder.encode(manager, context);

        int action;
        double epsilon = currentEpsilon(step);

        if (random.nextDouble() < epsilon) {
            // Explore: random action
            action = random.nextInt(SKILL_GEN_ACTIONS);
        } else {
            // Exploit: argmax Q(s, a)
            NDArray qValues = skillPolicyNet.forward(state);
            action = (int) qValues.argMax().getLong();
        }

        // Trigger training periodically
        if (step % trainInterval == 0 && skillReplayBuffer.size() >= 32) {
            trainSkillNetwork();
        }

        String reasoning = String.format("DQN selected %s (epsilon=%.3f, step=%d)",
                SKILL_ACTIONS[action], epsilon, step);

        return SkillDecision.of(SKILL_ACTIONS[action], 1.0 - epsilon, reasoning);
    }

    @Override
    public boolean shouldStopIteration(ConvergenceContext context) {
        int step = totalDecisions.get();

        if (step <= coldStartDecisions) {
            return heuristicFallback.shouldStopIteration(context);
        }

        NDArray state = StateEncoder.encode(manager, context);
        double epsilon = currentEpsilon(step);

        int action;
        if (random.nextDouble() < epsilon) {
            action = random.nextInt(CONVERGENCE_ACTIONS);
        } else {
            NDArray qValues = convergencePolicyNet.forward(state);
            action = (int) qValues.argMax().getLong();
        }

        // Trigger training
        if (step % trainInterval == 0 && convergenceReplayBuffer.size() >= 32) {
            trainConvergenceNetwork();
        }

        return action == 1; // 0 = CONTINUE, 1 = STOP
    }

    @Override
    public double[] getSelectionWeights(SelectionContext context) {
        // Selection weights use the same heuristic/bandit approach
        // DQN doesn't add value for continuous weight optimization
        return heuristicFallback.getSelectionWeights(context);
    }

    @Override
    public void recordOutcome(Decision decision, Outcome outcome) {
        switch (decision.type()) {
            case "skill_generation" -> {
                skillReplayBuffer.add(decision.stateVector(), decision.actionIndex(),
                        outcome.reward(), decision.stateVector()); // self-transition for single-step RL
            }
            case "convergence" -> {
                convergenceReplayBuffer.add(decision.stateVector(), decision.actionIndex(),
                        outcome.reward(), decision.stateVector());
            }
        }

        // Update target networks periodically
        int step = totalDecisions.get();
        if (step % targetUpdateInterval == 0) {
            skillTrainer.updateTargetNetwork();
            convergenceTrainer.updateTargetNetwork();
        }
    }

    private void trainSkillNetwork() {
        List<ReplayBuffer.PrioritizedExperience> batch = skillReplayBuffer.sample(32);
        float loss = skillTrainer.trainBatch(batch);
        logger.debug("[DeepRL] Skill network trained: loss={:.6f}, buffer={}", loss, skillReplayBuffer.size());
    }

    private void trainConvergenceNetwork() {
        List<ReplayBuffer.PrioritizedExperience> batch = convergenceReplayBuffer.sample(32);
        float loss = convergenceTrainer.trainBatch(batch);
        logger.debug("[DeepRL] Convergence network trained: loss={:.6f}", loss);
    }

    private double currentEpsilon(int step) {
        int effectiveStep = Math.max(0, step - coldStartDecisions);
        if (effectiveStep >= epsilonDecaySteps) return epsilonEnd;
        return epsilonStart - (epsilonStart - epsilonEnd) * effectiveStep / epsilonDecaySteps;
    }

    public boolean isColdStart() {
        return totalDecisions.get() <= coldStartDecisions;
    }

    public int getTotalDecisions() {
        return totalDecisions.get();
    }

    /**
     * Returns the current epsilon (exploration rate).
     */
    public double getCurrentEpsilon() {
        return currentEpsilon(totalDecisions.get());
    }

    /**
     * Returns the skill replay buffer size.
     */
    public int getSkillBufferSize() {
        return skillReplayBuffer.size();
    }

    /**
     * Returns the convergence replay buffer size.
     */
    public int getConvergenceBufferSize() {
        return convergenceReplayBuffer.size();
    }

    /**
     * Returns the number of training steps completed.
     */
    public int getSkillTrainSteps() {
        return skillTrainer.getTrainStepCount();
    }

    /**
     * Configuration record for DeepRLPolicy.
     */
    public record DeepRLConfig(
            float learningRate,
            float gamma,
            double epsilonStart,
            double epsilonEnd,
            int epsilonDecaySteps,
            int trainInterval,
            int targetUpdateInterval,
            int hiddenSize,
            int bufferCapacity,
            int coldStartDecisions
    ) {
        public static DeepRLConfig defaults() {
            return new DeepRLConfig(0.001f, 0.99f, 1.0, 0.05, 500, 10, 50, 64, 10000, 50);
        }
    }
}
