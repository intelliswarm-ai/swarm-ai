package ai.intelliswarm.swarmai.rl.deep;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple DQN (Deep Q-Network) implemented with raw NDArray operations.
 * Two-layer MLP: input → hidden1 (ReLU) → hidden2 (ReLU) → output (Q-values).
 *
 * <p>Uses manual weight management for simplicity and portability — no DJL Block/Model
 * abstractions, just matrix multiplications. This keeps the implementation transparent
 * and easy to debug.
 *
 * <pre>{@code
 * DQNNetwork net = new DQNNetwork(manager, 8, 64, 32, 4);
 * NDArray qValues = net.forward(stateVector);    // [4] Q-values
 * int bestAction = qValues.argMax().getInt();
 * }</pre>
 */
public class DQNNetwork {

    private static final Logger logger = LoggerFactory.getLogger(DQNNetwork.class);

    private final NDManager manager;
    private final int inputDim;
    private final int hidden1Dim;
    private final int hidden2Dim;
    private final int outputDim;

    // Weights and biases
    private NDArray w1, b1;  // input → hidden1
    private NDArray w2, b2;  // hidden1 → hidden2
    private NDArray w3, b3;  // hidden2 → output

    /**
     * Creates a DQN network with Xavier-initialized weights.
     */
    public DQNNetwork(NDManager manager, int inputDim, int hidden1Dim, int hidden2Dim, int outputDim) {
        this.manager = manager;
        this.inputDim = inputDim;
        this.hidden1Dim = hidden1Dim;
        this.hidden2Dim = hidden2Dim;
        this.outputDim = outputDim;

        // Xavier initialization
        double scale1 = Math.sqrt(2.0 / (inputDim + hidden1Dim));
        double scale2 = Math.sqrt(2.0 / (hidden1Dim + hidden2Dim));
        double scale3 = Math.sqrt(2.0 / (hidden2Dim + outputDim));

        this.w1 = manager.randomNormal(0, (float) scale1, new Shape(inputDim, hidden1Dim), DataType.FLOAT32);
        this.b1 = manager.zeros(new Shape(hidden1Dim), DataType.FLOAT32);
        this.w2 = manager.randomNormal(0, (float) scale2, new Shape(hidden1Dim, hidden2Dim), DataType.FLOAT32);
        this.b2 = manager.zeros(new Shape(hidden2Dim), DataType.FLOAT32);
        this.w3 = manager.randomNormal(0, (float) scale3, new Shape(hidden2Dim, outputDim), DataType.FLOAT32);
        this.b3 = manager.zeros(new Shape(outputDim), DataType.FLOAT32);
    }

    /**
     * Forward pass: state → Q-values for all actions.
     *
     * @param state input state vector [inputDim]
     * @return Q-values [outputDim]
     */
    public NDArray forward(NDArray state) {
        // Layer 1: ReLU(state @ w1 + b1)
        NDArray h1 = state.matMul(w1).add(b1);
        h1 = relu(h1);

        // Layer 2: ReLU(h1 @ w2 + b2)
        NDArray h2 = h1.matMul(w2).add(b2);
        h2 = relu(h2);

        // Output: h2 @ w3 + b3 (no activation — raw Q-values)
        return h2.matMul(w3).add(b3);
    }

    /**
     * Forward pass for a batch of states.
     *
     * @param states batch of state vectors [batchSize, inputDim]
     * @return Q-values [batchSize, outputDim]
     */
    public NDArray forwardBatch(NDArray states) {
        NDArray h1 = states.matMul(w1).add(b1);
        h1 = relu(h1);
        NDArray h2 = h1.matMul(w2).add(b2);
        h2 = relu(h2);
        return h2.matMul(w3).add(b3);
    }

    /**
     * Updates weights using a simple gradient step.
     * Computes MSE loss between predicted Q-values and targets, then updates weights.
     *
     * @param states  batch of state vectors [batchSize, inputDim]
     * @param actions batch of action indices [batchSize]
     * @param targets batch of target Q-values [batchSize]
     * @param learningRate the learning rate
     * @return the mean loss value
     */
    public float trainStep(NDArray states, NDArray actions, NDArray targets, float learningRate) {
        int batchSize = (int) states.getShape().get(0);

        // Forward pass with intermediate activations for backprop
        NDArray z1 = states.matMul(w1).add(b1);
        NDArray h1 = relu(z1);
        NDArray z2 = h1.matMul(w2).add(b2);
        NDArray h2 = relu(z2);
        NDArray qAll = h2.matMul(w3).add(b3);

        // Gather Q-values for taken actions
        NDArray actionIndices = actions.toType(DataType.INT32, false);
        NDArray qPred = NDArrayUtil.gatherByAction(qAll, actionIndices, batchSize, outputDim, manager);

        // Loss: MSE = mean((qPred - targets)^2)
        NDArray diff = qPred.sub(targets);
        float loss = diff.mul(diff).mean().getFloat();

        // Backprop: dLoss/dQ = 2 * (qPred - targets) / batchSize
        NDArray dQ = diff.mul(2.0f / batchSize);

        // Scatter gradient to the action positions
        NDArray dQAll = NDArrayUtil.scatterByAction(dQ, actionIndices, batchSize, outputDim, manager);

        // Backprop through layer 3
        NDArray dW3 = h2.transpose().matMul(dQAll);
        NDArray dB3 = dQAll.sum(new int[]{0});
        NDArray dH2 = dQAll.matMul(w3.transpose());

        // Backprop through ReLU + layer 2
        NDArray dZ2 = dH2.mul(reluGrad(z2));
        NDArray dW2 = h1.transpose().matMul(dZ2);
        NDArray dB2 = dZ2.sum(new int[]{0});
        NDArray dH1 = dZ2.matMul(w2.transpose());

        // Backprop through ReLU + layer 1
        NDArray dZ1 = dH1.mul(reluGrad(z1));
        NDArray dW1 = states.transpose().matMul(dZ1);
        NDArray dB1 = dZ1.sum(new int[]{0});

        // SGD update
        w3 = w3.sub(dW3.mul(learningRate));
        b3 = b3.sub(dB3.mul(learningRate));
        w2 = w2.sub(dW2.mul(learningRate));
        b2 = b2.sub(dB2.mul(learningRate));
        w1 = w1.sub(dW1.mul(learningRate));
        b1 = b1.sub(dB1.mul(learningRate));

        return loss;
    }

    /**
     * Copies weights from another network (for target network updates).
     */
    public void copyFrom(DQNNetwork source) {
        this.w1 = source.w1.duplicate();
        this.b1 = source.b1.duplicate();
        this.w2 = source.w2.duplicate();
        this.b2 = source.b2.duplicate();
        this.w3 = source.w3.duplicate();
        this.b3 = source.b3.duplicate();
    }

    public int getInputDim() { return inputDim; }
    public int getOutputDim() { return outputDim; }

    private NDArray relu(NDArray x) {
        return x.maximum(0);
    }

    private NDArray reluGrad(NDArray z) {
        return z.gt(0).toType(DataType.FLOAT32, false);
    }

    /**
     * Utility class for action-based gather/scatter operations on Q-value tensors.
     */
    static class NDArrayUtil {
        /**
         * Gathers Q-values at specific action indices from a [batchSize, numActions] tensor.
         */
        static NDArray gatherByAction(NDArray qAll, NDArray actions, int batchSize, int numActions, NDManager manager) {
            float[] qFlat = qAll.toFloatArray();
            int[] actionIdx = actions.toIntArray();
            float[] gathered = new float[batchSize];
            for (int i = 0; i < batchSize; i++) {
                gathered[i] = qFlat[i * numActions + actionIdx[i]];
            }
            return manager.create(gathered);
        }

        /**
         * Scatters gradient values to specific action positions in a [batchSize, numActions] tensor.
         */
        static NDArray scatterByAction(NDArray grad, NDArray actions, int batchSize, int numActions, NDManager manager) {
            float[] gradVals = grad.toFloatArray();
            int[] actionIdx = actions.toIntArray();
            float[] scattered = new float[batchSize * numActions];
            for (int i = 0; i < batchSize; i++) {
                scattered[i * numActions + actionIdx[i]] = gradVals[i];
            }
            return manager.create(scattered, new Shape(batchSize, numActions));
        }
    }
}
