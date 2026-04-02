package ai.intelliswarm.swarmai.rl.deep;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Trains a DQN network using mini-batch experience replay.
 * Implements the standard DQN training loop with a target network for stability.
 *
 * <pre>{@code
 * NetworkTrainer trainer = new NetworkTrainer(manager, policyNet, targetNet, 0.001f, 0.99f);
 * float loss = trainer.trainBatch(experiences);
 * trainer.updateTargetNetwork();  // periodically sync target
 * }</pre>
 */
public class NetworkTrainer {

    private static final Logger logger = LoggerFactory.getLogger(NetworkTrainer.class);

    private final NDManager manager;
    private final DQNNetwork policyNetwork;
    private final DQNNetwork targetNetwork;
    private final float learningRate;
    private final float gamma; // discount factor
    private int trainStepCount = 0;

    public NetworkTrainer(NDManager manager, DQNNetwork policyNetwork,
                          DQNNetwork targetNetwork, float learningRate, float gamma) {
        this.manager = manager;
        this.policyNetwork = policyNetwork;
        this.targetNetwork = targetNetwork;
        this.learningRate = learningRate;
        this.gamma = gamma;
    }

    /**
     * Trains the policy network on a mini-batch of experiences.
     *
     * @param experiences the mini-batch from the replay buffer
     * @return the mean loss
     */
    public float trainBatch(List<ReplayBuffer.PrioritizedExperience> experiences) {
        if (experiences.isEmpty()) return 0f;

        int batchSize = experiences.size();
        int stateDim = experiences.get(0).state().length;

        // Build batch arrays
        float[] stateFlat = new float[batchSize * stateDim];
        float[] actionFlat = new float[batchSize];
        float[] rewardFlat = new float[batchSize];
        float[] nextStateFlat = new float[batchSize * stateDim];
        boolean[] hasnext = new boolean[batchSize];

        for (int i = 0; i < batchSize; i++) {
            var exp = experiences.get(i);
            for (int j = 0; j < stateDim; j++) {
                stateFlat[i * stateDim + j] = (float) exp.state()[j];
            }
            actionFlat[i] = exp.action();
            rewardFlat[i] = (float) exp.reward();
            if (exp.nextState() != null) {
                hasnext[i] = true;
                for (int j = 0; j < stateDim; j++) {
                    nextStateFlat[i * stateDim + j] = (float) exp.nextState()[j];
                }
            }
        }

        NDArray states = manager.create(stateFlat, new Shape(batchSize, stateDim));
        NDArray actions = manager.create(actionFlat, new Shape(batchSize));
        NDArray rewards = manager.create(rewardFlat, new Shape(batchSize));
        NDArray nextStates = manager.create(nextStateFlat, new Shape(batchSize, stateDim));

        // Compute target Q-values: r + gamma * max_a'(Q_target(s', a'))
        NDArray nextQValues = targetNetwork.forwardBatch(nextStates);
        NDArray maxNextQ = nextQValues.max(new int[]{1});

        // Zero out future value for terminal states
        float[] maskValues = new float[batchSize];
        for (int i = 0; i < batchSize; i++) {
            maskValues[i] = hasnext[i] ? 1.0f : 0.0f;
        }
        NDArray mask = manager.create(maskValues);
        NDArray targets = rewards.add(maxNextQ.mul(mask).mul(gamma));

        // Train step
        float loss = policyNetwork.trainStep(states, actions, targets, learningRate);
        trainStepCount++;

        if (trainStepCount % 10 == 0) {
            logger.debug("[DQN] Train step {} — loss: {:.6f}", trainStepCount, loss);
        }

        return loss;
    }

    /**
     * Copies policy network weights to the target network.
     */
    public void updateTargetNetwork() {
        targetNetwork.copyFrom(policyNetwork);
        logger.debug("[DQN] Target network updated at step {}", trainStepCount);
    }

    public int getTrainStepCount() {
        return trainStepCount;
    }
}
