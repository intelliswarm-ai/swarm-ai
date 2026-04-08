package ai.intelliswarm.swarmai.rl.benchmark;

import ai.intelliswarm.swarmai.rl.bandit.LinUCBBandit;
import ai.intelliswarm.swarmai.rl.bandit.ThompsonSampling;

import java.util.Arrays;
import java.util.Random;

// Note: LinUCBBandit.invert() is package-private, so we inline a simple matrix inverse here.

/**
 * Alternative baseline algorithms for comparison against production implementations.
 * Each baseline implements {@link BanditAlgorithm} for plug-and-play benchmarking.
 */
public final class AlgorithmBaselines {

    private AlgorithmBaselines() {}

    /** Cholesky-based inversion for symmetric positive-definite matrices. */
    static double[][] choleskyInvert(double[][] matrix) {
        int n = matrix.length;
        double[][] L = new double[n][n];
        // Cholesky decomposition: A = L * L^T
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (int k = 0; k < j; k++) sum += L[i][k] * L[j][k];
                if (i == j) {
                    double val = matrix[i][i] - sum;
                    if (val <= 0) return null;
                    L[i][j] = Math.sqrt(val);
                } else {
                    L[i][j] = (matrix[i][j] - sum) / L[j][j];
                }
            }
        }
        // Invert L
        double[][] Linv = new double[n][n];
        for (int i = 0; i < n; i++) {
            Linv[i][i] = 1.0 / L[i][i];
            for (int j = i + 1; j < n; j++) {
                double sum = 0;
                for (int k = i; k < j; k++) sum += L[j][k] * Linv[k][i];
                Linv[j][i] = -sum / L[j][j];
            }
        }
        // A^{-1} = L^{-T} * L^{-1}
        double[][] result = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (int k = i; k < n; k++) sum += Linv[k][i] * Linv[k][j];
                result[i][j] = sum;
                result[j][i] = sum;
            }
        }
        return result;
    }

    // ======================================================================
    // Production algorithm wrappers
    // ======================================================================

    /** Wraps our LinUCB implementation for benchmarking. */
    public static BanditAlgorithm linUCB(int numActions, int stateDim, double alpha) {
        return new BanditAlgorithm() {
            private LinUCBBandit bandit = new LinUCBBandit(numActions, stateDim, alpha);

            @Override public String name() { return String.format("LinUCB(a=%.2f)", alpha); }
            @Override public int selectAction(double[] state) { return bandit.selectAction(state); }
            @Override public void update(double[] state, int action, double reward) {
                bandit.update(state, action, reward);
            }
            @Override public void reset() { bandit = new LinUCBBandit(numActions, stateDim, alpha); }
        };
    }

    /** Wraps our Thompson Sampling implementation for benchmarking. */
    public static BanditAlgorithm thompsonSampling(int numActions, long seed) {
        return new BanditAlgorithm() {
            private ThompsonSampling ts = new ThompsonSampling(numActions, new Random(seed));

            @Override public String name() { return "ThompsonSampling"; }
            @Override public int selectAction(double[] state) { return ts.selectAction(); }
            @Override public void update(double[] state, int action, double reward) {
                ts.update(action, reward);
            }
            @Override public void reset() { ts = new ThompsonSampling(numActions, new Random(seed)); }
        };
    }

    // ======================================================================
    // Baseline: Random
    // ======================================================================

    public static BanditAlgorithm random(int numActions, long seed) {
        return new BanditAlgorithm() {
            private Random rng = new Random(seed);

            @Override public String name() { return "Random"; }
            @Override public int selectAction(double[] state) { return rng.nextInt(numActions); }
            @Override public void update(double[] state, int action, double reward) { /* no-op */ }
            @Override public void reset() { rng = new Random(seed); }
        };
    }

    // ======================================================================
    // Baseline: UCB1 (context-free)
    // ======================================================================

    public static BanditAlgorithm ucb1(int numActions, double c) {
        return new BanditAlgorithm() {
            private double[] totalReward;
            private int[] counts;
            private int totalCount;

            { reset(); }

            @Override public String name() { return String.format("UCB1(c=%.2f)", c); }

            @Override
            public int selectAction(double[] state) {
                // Play each action once first
                for (int a = 0; a < numActions; a++) {
                    if (counts[a] == 0) return a;
                }
                int best = 0;
                double bestUCB = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < numActions; a++) {
                    double mean = totalReward[a] / counts[a];
                    double exploration = c * Math.sqrt(Math.log(totalCount) / counts[a]);
                    double ucb = mean + exploration;
                    if (ucb > bestUCB) { bestUCB = ucb; best = a; }
                }
                return best;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                totalReward[action] += reward;
                counts[action]++;
                totalCount++;
            }

            @Override
            public void reset() {
                totalReward = new double[numActions];
                counts = new int[numActions];
                totalCount = 0;
            }
        };
    }

    // ======================================================================
    // Baseline: Monte Carlo Averaging (epsilon-greedy)
    // ======================================================================

    public static BanditAlgorithm monteCarlo(int numActions, double epsilon, long seed) {
        return new BanditAlgorithm() {
            private double[] totalReward;
            private int[] counts;
            private Random rng;

            { reset(); }

            @Override public String name() { return String.format("MonteCarlo(e=%.2f)", epsilon); }

            @Override
            public int selectAction(double[] state) {
                if (rng.nextDouble() < epsilon) {
                    return rng.nextInt(numActions);
                }
                // Greedy: pick best mean
                int best = 0;
                double bestMean = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < numActions; a++) {
                    double mean = counts[a] == 0 ? 0 : totalReward[a] / counts[a];
                    if (mean > bestMean) { bestMean = mean; best = a; }
                }
                return best;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                totalReward[action] += reward;
                counts[action]++;
            }

            @Override
            public void reset() {
                totalReward = new double[numActions];
                counts = new int[numActions];
                rng = new Random(seed);
            }
        };
    }

    /**
     * Monte Carlo with decaying average — more recent rewards weighted higher.
     * Uses exponential moving average: Q(a) = (1-alpha)*Q(a) + alpha*reward.
     */
    public static BanditAlgorithm monteCarloDecaying(int numActions, double epsilon,
                                                      double decayAlpha, long seed) {
        return new BanditAlgorithm() {
            private double[] qValues;
            private int[] counts;
            private Random rng;

            { reset(); }

            @Override public String name() {
                return String.format("MC_Decay(e=%.2f,a=%.2f)", epsilon, decayAlpha);
            }

            @Override
            public int selectAction(double[] state) {
                if (rng.nextDouble() < epsilon) return rng.nextInt(numActions);
                int best = 0;
                for (int a = 1; a < numActions; a++) {
                    if (qValues[a] > qValues[best]) best = a;
                }
                return best;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                counts[action]++;
                if (counts[action] == 1) {
                    qValues[action] = reward;
                } else {
                    qValues[action] = (1.0 - decayAlpha) * qValues[action] + decayAlpha * reward;
                }
            }

            @Override
            public void reset() {
                qValues = new double[numActions];
                counts = new int[numActions];
                rng = new Random(seed);
            }
        };
    }

    // ======================================================================
    // Baseline: EXP3 (adversarial bandit)
    // ======================================================================

    public static BanditAlgorithm exp3(int numActions, double gamma, long seed) {
        return new BanditAlgorithm() {
            private double[] weights;
            private Random rng;

            { reset(); }

            @Override public String name() { return String.format("EXP3(g=%.2f)", gamma); }

            @Override
            public int selectAction(double[] state) {
                double[] probs = getProbs();
                double u = rng.nextDouble();
                double cumulative = 0;
                for (int a = 0; a < numActions; a++) {
                    cumulative += probs[a];
                    if (u <= cumulative) return a;
                }
                return numActions - 1;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                double[] probs = getProbs();
                // Importance-weighted reward estimate
                double estimatedReward = reward / probs[action];
                weights[action] *= Math.exp(gamma * estimatedReward / numActions);

                // Prevent overflow
                double maxW = Arrays.stream(weights).max().orElse(1.0);
                if (maxW > 1e10) {
                    for (int a = 0; a < numActions; a++) weights[a] /= maxW;
                }
            }

            private double[] getProbs() {
                double sumW = Arrays.stream(weights).sum();
                double[] probs = new double[numActions];
                for (int a = 0; a < numActions; a++) {
                    probs[a] = (1.0 - gamma) * (weights[a] / sumW) + gamma / numActions;
                }
                return probs;
            }

            @Override
            public void reset() {
                weights = new double[numActions];
                Arrays.fill(weights, 1.0);
                rng = new Random(seed);
            }
        };
    }

    // ======================================================================
    // Baseline: Softmax / Boltzmann Exploration
    // ======================================================================

    public static BanditAlgorithm softmax(int numActions, double temperature, long seed) {
        return new BanditAlgorithm() {
            private double[] totalReward;
            private int[] counts;
            private Random rng;

            { reset(); }

            @Override public String name() { return String.format("Softmax(t=%.2f)", temperature); }

            @Override
            public int selectAction(double[] state) {
                double[] qValues = new double[numActions];
                for (int a = 0; a < numActions; a++) {
                    qValues[a] = counts[a] == 0 ? 0 : totalReward[a] / counts[a];
                }

                // Softmax probabilities
                double maxQ = Arrays.stream(qValues).max().orElse(0);
                double[] expQ = new double[numActions];
                double sumExp = 0;
                for (int a = 0; a < numActions; a++) {
                    expQ[a] = Math.exp((qValues[a] - maxQ) / temperature);
                    sumExp += expQ[a];
                }

                double u = rng.nextDouble() * sumExp;
                double cumulative = 0;
                for (int a = 0; a < numActions; a++) {
                    cumulative += expQ[a];
                    if (u <= cumulative) return a;
                }
                return numActions - 1;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                totalReward[action] += reward;
                counts[action]++;
            }

            @Override
            public void reset() {
                totalReward = new double[numActions];
                counts = new int[numActions];
                rng = new Random(seed);
            }
        };
    }

    // ======================================================================
    // Baseline: Contextual Thompson Sampling (Linear)
    // ======================================================================

    /**
     * Linear Thompson Sampling — Bayesian alternative to LinUCB.
     * Maintains posterior N(mu_a, Sigma_a) for each action's weight vector.
     * Samples theta_a ~ N(mu_a, v^2 * B_a^{-1}) and selects argmax_a(x^T theta_a).
     */
    public static BanditAlgorithm contextualThompsonSampling(int numActions, int stateDim,
                                                              double v, long seed) {
        return new BanditAlgorithm() {
            private double[][][] bMatrices; // [action][d][d] precision matrices
            private double[][] muVectors;   // [action][d] mean vectors
            private double[][] fVectors;    // [action][d] = B^{-1} * sum(reward * x)
            private Random rng;

            { reset(); }

            @Override public String name() {
                return String.format("ContextualTS(v=%.2f)", v);
            }

            @Override
            public int selectAction(double[] state) {
                int best = 0;
                double bestVal = Double.NEGATIVE_INFINITY;

                for (int a = 0; a < numActions; a++) {
                    // Sample theta ~ N(mu_a, v^2 * B_a^{-1})
                    double[][] bInv = invertMatrix(bMatrices[a]);
                    if (bInv == null) {
                        // Fallback: use mean
                        double val = dot(state, muVectors[a]);
                        if (val > bestVal) { bestVal = val; best = a; }
                        continue;
                    }

                    double[] theta = new double[stateDim];
                    for (int i = 0; i < stateDim; i++) {
                        double noise = 0;
                        for (int j = 0; j < stateDim; j++) {
                            noise += bInv[i][j] * rng.nextGaussian();
                        }
                        theta[i] = muVectors[a][i] + v * noise;
                    }

                    double val = dot(state, theta);
                    if (val > bestVal) { bestVal = val; best = a; }
                }
                return best;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                // B_a += x * x^T
                for (int i = 0; i < stateDim; i++) {
                    for (int j = 0; j < stateDim; j++) {
                        bMatrices[action][i][j] += state[i] * state[j];
                    }
                    fVectors[action][i] += reward * state[i];
                }
                // mu_a = B_a^{-1} * f_a
                double[][] bInv = invertMatrix(bMatrices[action]);
                if (bInv != null) {
                    for (int i = 0; i < stateDim; i++) {
                        muVectors[action][i] = 0;
                        for (int j = 0; j < stateDim; j++) {
                            muVectors[action][i] += bInv[i][j] * fVectors[action][j];
                        }
                    }
                }
            }

            @Override
            public void reset() {
                bMatrices = new double[numActions][stateDim][stateDim];
                muVectors = new double[numActions][stateDim];
                fVectors = new double[numActions][stateDim];
                rng = new Random(seed);
                for (int a = 0; a < numActions; a++) {
                    for (int i = 0; i < stateDim; i++) {
                        bMatrices[a][i][i] = 1.0; // identity prior
                    }
                }
            }

            private double[][] invertMatrix(double[][] matrix) {
                return AlgorithmBaselines.choleskyInvert(matrix);
            }

            private double dot(double[] a, double[] b) {
                double sum = 0;
                for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
                return sum;
            }
        };
    }

    // ======================================================================
    // Simulated DQN (pure Java, no DJL — mirrors enterprise DeepRLPolicy)
    // ======================================================================

    /**
     * Simulated DQN matching enterprise DeepRLPolicy architecture:
     * - 2-layer MLP with ReLU activations
     * - Epsilon-greedy exploration with linear decay
     * - Experience replay with mini-batch SGD
     * - Target network updated periodically
     * - Self-transitions (single-step contextual bandit, not sequential MDP)
     *
     * This allows benchmarking DQN against bandits without requiring DJL.
     */
    public static BanditAlgorithm simulatedDQN(int numActions, int stateDim,
                                                int hidden1, int hidden2,
                                                float learningRate, double epsilonStart,
                                                double epsilonEnd, int epsilonDecaySteps,
                                                int trainInterval, int targetUpdateInterval,
                                                int coldStart, long seed) {
        return new BanditAlgorithm() {
            // Weights: input→hidden1→hidden2→output
            private double[][] w1, w2, w3;
            private double[] b1, b2, b3;
            // Target network weights
            private double[][] tw1, tw2, tw3;
            private double[] tb1, tb2, tb3;
            // Replay buffer
            private double[][] bufStates;
            private int[] bufActions;
            private double[] bufRewards;
            private int bufSize, bufIdx;
            private static final int BUF_CAPACITY = 10000;
            private static final int BATCH_SIZE = 32;

            private Random rng;
            private int step;

            { reset(); }

            @Override
            public String name() {
                return String.format("SimDQN(h=%d/%d,lr=%.4f)", hidden1, hidden2, learningRate);
            }

            @Override
            public int selectAction(double[] state) {
                step++;
                if (step <= coldStart) {
                    return rng.nextInt(numActions); // cold start = random
                }
                double epsilon = currentEpsilon();
                if (rng.nextDouble() < epsilon) {
                    return rng.nextInt(numActions);
                }
                // Exploit: argmax Q(s, a)
                double[] q = forward(state, w1, b1, w2, b2, w3, b3);
                return argmax(q);
            }

            @Override
            public void update(double[] state, int action, double reward) {
                // Add to replay buffer
                addToBuffer(state, action, reward);

                // Train every trainInterval steps
                if (step > coldStart && step % trainInterval == 0 && bufSize >= BATCH_SIZE) {
                    trainBatch();
                }

                // Update target network
                if (step > coldStart && step % targetUpdateInterval == 0) {
                    copyToTarget();
                }
            }

            @Override
            public void reset() {
                rng = new Random(seed);
                step = 0;
                // Xavier initialization
                w1 = xavierInit(stateDim, hidden1, rng);
                b1 = new double[hidden1];
                w2 = xavierInit(hidden1, hidden2, rng);
                b2 = new double[hidden2];
                w3 = xavierInit(hidden2, numActions, rng);
                b3 = new double[numActions];
                copyToTarget();
                bufStates = new double[BUF_CAPACITY][stateDim];
                bufActions = new int[BUF_CAPACITY];
                bufRewards = new double[BUF_CAPACITY];
                bufSize = 0;
                bufIdx = 0;
            }

            private double currentEpsilon() {
                int effective = Math.max(0, step - coldStart);
                if (effective >= epsilonDecaySteps) return epsilonEnd;
                return epsilonStart - (epsilonStart - epsilonEnd) * effective / epsilonDecaySteps;
            }

            private double[] forward(double[] state, double[][] pw1, double[] pb1,
                                     double[][] pw2, double[] pb2,
                                     double[][] pw3, double[] pb3) {
                double[] h1 = new double[hidden1];
                for (int j = 0; j < hidden1; j++) {
                    double sum = pb1[j];
                    for (int i = 0; i < stateDim; i++) sum += state[i] * pw1[i][j];
                    h1[j] = Math.max(0, sum); // ReLU
                }
                double[] h2 = new double[hidden2];
                for (int j = 0; j < hidden2; j++) {
                    double sum = pb2[j];
                    for (int i = 0; i < hidden1; i++) sum += h1[i] * pw2[i][j];
                    h2[j] = Math.max(0, sum);
                }
                double[] out = new double[numActions];
                for (int j = 0; j < numActions; j++) {
                    double sum = pb3[j];
                    for (int i = 0; i < hidden2; i++) sum += h2[i] * pw3[i][j];
                    out[j] = sum;
                }
                return out;
            }

            private void trainBatch() {
                // Sample mini-batch
                for (int b = 0; b < BATCH_SIZE; b++) {
                    int idx = rng.nextInt(bufSize);
                    double[] state = bufStates[idx];
                    int action = bufActions[idx];
                    double reward = bufRewards[idx];

                    // Target: r + gamma * max_a' Q_target(s, a') [self-transition]
                    double[] qTarget = forward(state, tw1, tb1, tw2, tb2, tw3, tb3);
                    double target = reward + 0.99 * max(qTarget);

                    // Forward pass with intermediates for backprop
                    double[] z1 = new double[hidden1], h1 = new double[hidden1];
                    for (int j = 0; j < hidden1; j++) {
                        double sum = b1[j];
                        for (int i = 0; i < stateDim; i++) sum += state[i] * w1[i][j];
                        z1[j] = sum;
                        h1[j] = Math.max(0, sum);
                    }
                    double[] z2 = new double[hidden2], h2 = new double[hidden2];
                    for (int j = 0; j < hidden2; j++) {
                        double sum = b2[j];
                        for (int i = 0; i < hidden1; i++) sum += h1[i] * w2[i][j];
                        z2[j] = sum;
                        h2[j] = Math.max(0, sum);
                    }
                    double[] qPred = new double[numActions];
                    for (int j = 0; j < numActions; j++) {
                        double sum = b3[j];
                        for (int i = 0; i < hidden2; i++) sum += h2[i] * w3[i][j];
                        qPred[j] = sum;
                    }

                    // Gradient only on the taken action
                    double dQ = 2.0 * (qPred[action] - target);

                    // Backprop layer 3
                    for (int i = 0; i < hidden2; i++) {
                        w3[i][action] -= learningRate * dQ * h2[i];
                    }
                    b3[action] -= learningRate * dQ;

                    // Backprop layer 2
                    double[] dh2 = new double[hidden2];
                    for (int i = 0; i < hidden2; i++) {
                        dh2[i] = dQ * w3[i][action] * (z2[i] > 0 ? 1 : 0);
                    }
                    for (int i = 0; i < hidden1; i++) {
                        for (int j = 0; j < hidden2; j++) {
                            w2[i][j] -= learningRate * dh2[j] * h1[i];
                        }
                    }
                    for (int j = 0; j < hidden2; j++) b2[j] -= learningRate * dh2[j];

                    // Backprop layer 1
                    double[] dh1 = new double[hidden1];
                    for (int i = 0; i < hidden1; i++) {
                        double sum = 0;
                        for (int j = 0; j < hidden2; j++) sum += dh2[j] * w2[i][j];
                        dh1[i] = sum * (z1[i] > 0 ? 1 : 0);
                    }
                    for (int i = 0; i < stateDim; i++) {
                        for (int j = 0; j < hidden1; j++) {
                            w1[i][j] -= learningRate * dh1[j] * state[i];
                        }
                    }
                    for (int j = 0; j < hidden1; j++) b1[j] -= learningRate * dh1[j];
                }
            }

            private void addToBuffer(double[] state, int action, double reward) {
                System.arraycopy(state, 0, bufStates[bufIdx], 0, stateDim);
                bufActions[bufIdx] = action;
                bufRewards[bufIdx] = reward;
                bufIdx = (bufIdx + 1) % BUF_CAPACITY;
                if (bufSize < BUF_CAPACITY) bufSize++;
            }

            private void copyToTarget() {
                tw1 = deepCopy(w1); tb1 = b1.clone();
                tw2 = deepCopy(w2); tb2 = b2.clone();
                tw3 = deepCopy(w3); tb3 = b3.clone();
            }

            private double[][] xavierInit(int in, int out, Random r) {
                double scale = Math.sqrt(2.0 / (in + out));
                double[][] m = new double[in][out];
                for (int i = 0; i < in; i++)
                    for (int j = 0; j < out; j++)
                        m[i][j] = r.nextGaussian() * scale;
                return m;
            }

            private double[][] deepCopy(double[][] m) {
                double[][] c = new double[m.length][];
                for (int i = 0; i < m.length; i++) c[i] = m[i].clone();
                return c;
            }

            private int argmax(double[] arr) {
                int best = 0;
                for (int i = 1; i < arr.length; i++) if (arr[i] > arr[best]) best = i;
                return best;
            }

            private double max(double[] arr) {
                double m = arr[0];
                for (int i = 1; i < arr.length; i++) if (arr[i] > m) m = arr[i];
                return m;
            }
        };
    }

    // ======================================================================
    // NeuralLinUCB: Neural feature extraction + LinUCB exploration
    // ======================================================================

    /**
     * Neural Linear UCB (NeuralLinUCB / NeuralUCB).
     *
     * Uses a neural network to learn a feature representation phi(x) from the raw state,
     * then applies LinUCB on the learned features. This combines:
     * - Neural network's ability to learn non-linear representations
     * - LinUCB's principled UCB exploration (instead of epsilon-greedy)
     *
     * Architecture:
     * 1. Feature network: state [d] -> hidden [h] -> features [k] (ReLU activations)
     * 2. LinUCB layer: per-action A_a [k x k] and b_a [k] on phi(x)
     * 3. Action selection: argmax_a(phi(x)^T theta_a + alpha * sqrt(phi(x)^T A_a^{-1} phi(x)))
     * 4. Network training: minimize prediction error on replay buffer via SGD
     *
     * Reference: Riquelme et al. (2018), Zhou et al. (2020) "Neural Contextual Bandits"
     */
    public static BanditAlgorithm neuralLinUCB(int numActions, int stateDim,
                                                int hiddenDim, int featureDim,
                                                double alpha, float learningRate,
                                                int trainInterval, int coldStart,
                                                long seed) {
        return new BanditAlgorithm() {
            // Feature network: state -> hidden -> features
            private double[][] w1, w2;
            private double[] b1, b2;

            // LinUCB layer on top of learned features
            private double[][][] aMatrices; // [action][featureDim][featureDim]
            private double[][] bVectors;    // [action][featureDim]

            // Replay buffer for network training
            private double[][] bufStates;
            private int[] bufActions;
            private double[] bufRewards;
            private int bufSize, bufIdx;
            private static final int BUF_CAPACITY = 5000;
            private static final int BATCH_SIZE = 32;

            private Random rng;
            private int step;

            { reset(); }

            @Override
            public String name() {
                return String.format("NeuralLinUCB(h=%d,f=%d,a=%.1f)", hiddenDim, featureDim, alpha);
            }

            @Override
            public int selectAction(double[] state) {
                step++;

                // Cold start: round-robin exploration
                if (step <= coldStart) {
                    return (step - 1) % numActions;
                }

                // Extract features through neural network
                double[] phi = extractFeatures(state);

                // LinUCB on learned features
                int bestAction = 0;
                double bestUCB = Double.NEGATIVE_INFINITY;

                for (int a = 0; a < numActions; a++) {
                    double[][] aInv = choleskyInvert(aMatrices[a]);
                    if (aInv == null) {
                        // Fallback: use raw exploitation
                        double[] theta = new double[featureDim];
                        for (int i = 0; i < featureDim; i++) theta[i] = bVectors[a][i];
                        double val = dot(phi, theta);
                        if (val > bestUCB) { bestUCB = val; bestAction = a; }
                        continue;
                    }

                    // theta_a = A_a^{-1} * b_a
                    double[] theta = matVecMul(aInv, bVectors[a]);
                    double exploitation = dot(phi, theta);

                    // Exploration: alpha * sqrt(phi^T A^{-1} phi)
                    double[] aInvPhi = matVecMul(aInv, phi);
                    double exploration = alpha * Math.sqrt(Math.max(0, dot(phi, aInvPhi)));

                    double ucb = exploitation + exploration;
                    if (ucb > bestUCB) { bestUCB = ucb; bestAction = a; }
                }

                return bestAction;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                // Extract features
                double[] phi = extractFeatures(state);

                // Update LinUCB matrices on learned features
                for (int i = 0; i < featureDim; i++) {
                    for (int j = 0; j < featureDim; j++) {
                        aMatrices[action][i][j] += phi[i] * phi[j];
                    }
                    bVectors[action][i] += reward * phi[i];
                }

                // Store in replay buffer
                addToBuffer(state, action, reward);

                // Train feature network periodically
                if (step > coldStart && step % trainInterval == 0 && bufSize >= BATCH_SIZE) {
                    trainFeatureNetwork();
                    // After retraining features, rebuild LinUCB matrices from buffer
                    if (step % (trainInterval * 5) == 0) {
                        rebuildLinUCBFromBuffer();
                    }
                }
            }

            @Override
            public void reset() {
                rng = new Random(seed);
                step = 0;

                // Initialize feature network (Xavier)
                w1 = xavierInit(stateDim, hiddenDim, rng);
                b1 = new double[hiddenDim];
                w2 = xavierInit(hiddenDim, featureDim, rng);
                b2 = new double[featureDim];

                // Initialize LinUCB matrices to identity
                aMatrices = new double[numActions][featureDim][featureDim];
                bVectors = new double[numActions][featureDim];
                for (int a = 0; a < numActions; a++) {
                    for (int i = 0; i < featureDim; i++) {
                        aMatrices[a][i][i] = 1.0;
                    }
                }

                // Replay buffer
                bufStates = new double[BUF_CAPACITY][stateDim];
                bufActions = new int[BUF_CAPACITY];
                bufRewards = new double[BUF_CAPACITY];
                bufSize = 0;
                bufIdx = 0;
            }

            private double[] extractFeatures(double[] state) {
                // Layer 1: ReLU(state @ w1 + b1)
                double[] h = new double[hiddenDim];
                for (int j = 0; j < hiddenDim; j++) {
                    double sum = b1[j];
                    for (int i = 0; i < stateDim; i++) sum += state[i] * w1[i][j];
                    h[j] = Math.max(0, sum); // ReLU
                }
                // Layer 2: ReLU(h @ w2 + b2) — features should be non-negative for stable LinUCB
                double[] phi = new double[featureDim];
                for (int j = 0; j < featureDim; j++) {
                    double sum = b2[j];
                    for (int i = 0; i < hiddenDim; i++) sum += h[i] * w2[i][j];
                    phi[j] = Math.max(0, sum); // ReLU ensures non-negative features
                }
                // Normalize to unit length for stable UCB computation
                double norm = 0;
                for (double v : phi) norm += v * v;
                norm = Math.sqrt(norm);
                if (norm > 1e-8) {
                    for (int j = 0; j < featureDim; j++) phi[j] /= norm;
                }
                return phi;
            }

            private void trainFeatureNetwork() {
                // Train on prediction error: minimize ||r - phi(s)^T theta_a||^2
                for (int b = 0; b < BATCH_SIZE; b++) {
                    int idx = rng.nextInt(bufSize);
                    double[] state = bufStates[idx];
                    int action = bufActions[idx];
                    double reward = bufRewards[idx];

                    // Forward pass with intermediates
                    double[] z1 = new double[hiddenDim], h1 = new double[hiddenDim];
                    for (int j = 0; j < hiddenDim; j++) {
                        double sum = b1[j];
                        for (int i = 0; i < stateDim; i++) sum += state[i] * w1[i][j];
                        z1[j] = sum;
                        h1[j] = Math.max(0, sum);
                    }
                    double[] z2 = new double[featureDim], phi = new double[featureDim];
                    for (int j = 0; j < featureDim; j++) {
                        double sum = b2[j];
                        for (int i = 0; i < hiddenDim; i++) sum += h1[i] * w2[i][j];
                        z2[j] = sum;
                        phi[j] = Math.max(0, sum);
                    }

                    // Compute theta_a from current LinUCB (approximate)
                    double[][] aInv = choleskyInvert(aMatrices[action]);
                    if (aInv == null) continue;
                    double[] theta = matVecMul(aInv, bVectors[action]);

                    // Prediction error
                    double predicted = dot(phi, theta);
                    double error = predicted - reward;

                    // Gradient: d(error^2)/d(phi) = 2 * error * theta
                    double[] dPhi = new double[featureDim];
                    for (int j = 0; j < featureDim; j++) {
                        dPhi[j] = 2.0 * error * theta[j] * (z2[j] > 0 ? 1 : 0); // ReLU grad
                    }

                    // Backprop to w2, b2
                    for (int i = 0; i < hiddenDim; i++) {
                        for (int j = 0; j < featureDim; j++) {
                            w2[i][j] -= learningRate * dPhi[j] * h1[i];
                        }
                    }
                    for (int j = 0; j < featureDim; j++) b2[j] -= learningRate * dPhi[j];

                    // Backprop to w1, b1
                    double[] dH1 = new double[hiddenDim];
                    for (int i = 0; i < hiddenDim; i++) {
                        double sum = 0;
                        for (int j = 0; j < featureDim; j++) sum += dPhi[j] * w2[i][j];
                        dH1[i] = sum * (z1[i] > 0 ? 1 : 0);
                    }
                    for (int i = 0; i < stateDim; i++) {
                        for (int j = 0; j < hiddenDim; j++) {
                            w1[i][j] -= learningRate * dH1[j] * state[i];
                        }
                    }
                    for (int j = 0; j < hiddenDim; j++) b1[j] -= learningRate * dH1[j];
                }
            }

            private void rebuildLinUCBFromBuffer() {
                // Re-extract features for all buffered experiences and rebuild A/b matrices
                aMatrices = new double[numActions][featureDim][featureDim];
                bVectors = new double[numActions][featureDim];
                for (int a = 0; a < numActions; a++) {
                    for (int i = 0; i < featureDim; i++) aMatrices[a][i][i] = 1.0;
                }
                for (int idx = 0; idx < bufSize; idx++) {
                    double[] phi = extractFeatures(bufStates[idx]);
                    int a = bufActions[idx];
                    double r = bufRewards[idx];
                    for (int i = 0; i < featureDim; i++) {
                        for (int j = 0; j < featureDim; j++) {
                            aMatrices[a][i][j] += phi[i] * phi[j];
                        }
                        bVectors[a][i] += r * phi[i];
                    }
                }
            }

            private void addToBuffer(double[] state, int action, double reward) {
                System.arraycopy(state, 0, bufStates[bufIdx], 0, stateDim);
                bufActions[bufIdx] = action;
                bufRewards[bufIdx] = reward;
                bufIdx = (bufIdx + 1) % BUF_CAPACITY;
                if (bufSize < BUF_CAPACITY) bufSize++;
            }

            private double[][] xavierInit(int in, int out, Random r) {
                double scale = Math.sqrt(2.0 / (in + out));
                double[][] m = new double[in][out];
                for (int i = 0; i < in; i++)
                    for (int j = 0; j < out; j++)
                        m[i][j] = r.nextGaussian() * scale;
                return m;
            }

            private double dot(double[] a, double[] b) {
                double sum = 0;
                for (int i = 0; i < Math.min(a.length, b.length); i++) sum += a[i] * b[i];
                return sum;
            }

            private double[] matVecMul(double[][] mat, double[] vec) {
                double[] result = new double[mat.length];
                for (int i = 0; i < mat.length; i++)
                    for (int j = 0; j < vec.length; j++)
                        result[i] += mat[i][j] * vec[j];
                return result;
            }
        };
    }

    // ======================================================================
    // Baseline: Monte Carlo Tree Search (Flat — single-step lookahead)
    // ======================================================================

    /**
     * Flat MCTS: simulates N rollouts per action using the observed reward model,
     * selects the action with the highest average simulated return.
     */
    public static BanditAlgorithm flatMCTS(int numActions, int numSimulations, long seed) {
        return new BanditAlgorithm() {
            private double[] totalReward;
            private int[] counts;
            private double[] rewardVariance;
            private Random rng;

            { reset(); }

            @Override public String name() {
                return String.format("FlatMCTS(sims=%d)", numSimulations);
            }

            @Override
            public int selectAction(double[] state) {
                // For each action, simulate using the learned reward model
                double bestMean = Double.NEGATIVE_INFINITY;
                int best = 0;

                for (int a = 0; a < numActions; a++) {
                    if (counts[a] == 0) return a; // explore unvisited

                    double mean = totalReward[a] / counts[a];
                    double std = Math.sqrt(rewardVariance[a] / counts[a]);

                    // Run simulations
                    double simTotal = 0;
                    for (int s = 0; s < numSimulations; s++) {
                        simTotal += mean + rng.nextGaussian() * std;
                    }
                    double simMean = simTotal / numSimulations;

                    if (simMean > bestMean) { bestMean = simMean; best = a; }
                }
                return best;
            }

            @Override
            public void update(double[] state, int action, double reward) {
                double oldMean = counts[action] == 0 ? 0 : totalReward[action] / counts[action];
                totalReward[action] += reward;
                counts[action]++;
                double newMean = totalReward[action] / counts[action];
                // Welford's online variance
                rewardVariance[action] += (reward - oldMean) * (reward - newMean);
            }

            @Override
            public void reset() {
                totalReward = new double[numActions];
                counts = new int[numActions];
                rewardVariance = new double[numActions];
                rng = new Random(seed);
            }
        };
    }
}
