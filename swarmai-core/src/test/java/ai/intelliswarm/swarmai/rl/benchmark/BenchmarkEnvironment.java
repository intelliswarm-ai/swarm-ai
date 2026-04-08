package ai.intelliswarm.swarmai.rl.benchmark;

import java.util.Random;

/**
 * Synthetic reward environments for benchmarking bandit algorithms.
 * Each environment defines a reward function over (state, action) pairs.
 */
public abstract class BenchmarkEnvironment {

    protected final String name;
    protected final int stateDim;
    protected final int numActions;
    protected final Random random;
    protected int timestep = 0;

    protected BenchmarkEnvironment(String name, int stateDim, int numActions, Random random) {
        this.name = name;
        this.stateDim = stateDim;
        this.numActions = numActions;
        this.random = random;
    }

    public String getName() { return name; }
    public int getStateDim() { return stateDim; }
    public int getNumActions() { return numActions; }
    public int getTimestep() { return timestep; }

    /** Generates a random state vector from this environment's distribution. */
    public double[] sampleState() {
        double[] state = new double[stateDim];
        for (int i = 0; i < stateDim; i++) {
            state[i] = random.nextDouble();
        }
        return state;
    }

    /** Returns the reward for taking action a in state s. Advances timestep. */
    public double reward(double[] state, int action) {
        timestep++;
        return computeReward(state, action);
    }

    /** Returns the optimal action for state s. */
    public abstract int optimalAction(double[] state);

    /** Returns the reward of the optimal action for state s (without advancing timestep). */
    public double optimalReward(double[] state) {
        return computeReward(state, optimalAction(state));
    }

    protected abstract double computeReward(double[] state, int action);

    public void reset() { timestep = 0; }

    // ======================================================================
    // Factory methods for standard environments
    // ======================================================================

    /**
     * Stationary Contextual Bandit: r(s, a) = s^T w_a + N(0, noiseStd).
     * Optimal action is argmax_a(s^T w_a).
     */
    public static BenchmarkEnvironment stationaryContextual(int stateDim, int numActions,
                                                             double noiseStd, long seed) {
        Random rng = new Random(seed);
        // Generate fixed weight vectors per action
        double[][] weights = new double[numActions][stateDim];
        for (int a = 0; a < numActions; a++) {
            for (int d = 0; d < stateDim; d++) {
                weights[a][d] = rng.nextGaussian();
            }
        }

        String envName = String.format("SCB_d%d_a%d_n%.1f", stateDim, numActions, noiseStd);
        return new BenchmarkEnvironment(envName, stateDim, numActions, new Random(seed + 1)) {
            @Override
            public int optimalAction(double[] state) {
                int best = 0;
                double bestVal = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < numActions; a++) {
                    double val = dot(state, weights[a]);
                    if (val > bestVal) { bestVal = val; best = a; }
                }
                return best;
            }

            @Override
            protected double computeReward(double[] state, int action) {
                return dot(state, weights[action]) + random.nextGaussian() * noiseStd;
            }
        };
    }

    /**
     * Non-Stationary Bandit: optimal action changes every regimeLength steps.
     * Abrupt shift — reward vectors rotate.
     */
    public static BenchmarkEnvironment nonStationary(int stateDim, int numActions,
                                                      double noiseStd, int regimeLength, long seed) {
        Random rng = new Random(seed);
        // Generate multiple regimes (rotate weight assignments)
        int numRegimes = 10;
        double[][][] regimeWeights = new double[numRegimes][numActions][stateDim];
        for (int r = 0; r < numRegimes; r++) {
            for (int a = 0; a < numActions; a++) {
                for (int d = 0; d < stateDim; d++) {
                    regimeWeights[r][a][d] = rng.nextGaussian();
                }
            }
        }

        String envName = String.format("NSB_d%d_a%d_regime%d", stateDim, numActions, regimeLength);
        return new BenchmarkEnvironment(envName, stateDim, numActions, new Random(seed + 1)) {
            @Override
            public int optimalAction(double[] state) {
                int regime = Math.min(timestep / regimeLength, numRegimes - 1);
                int best = 0;
                double bestVal = Double.NEGATIVE_INFINITY;
                for (int a = 0; a < numActions; a++) {
                    double val = dot(state, regimeWeights[regime][a]);
                    if (val > bestVal) { bestVal = val; best = a; }
                }
                return best;
            }

            @Override
            protected double computeReward(double[] state, int action) {
                int regime = Math.min(timestep / regimeLength, numRegimes - 1);
                return dot(state, regimeWeights[regime][action]) + random.nextGaussian() * noiseStd;
            }
        };
    }

    /**
     * Adversarial Bandit: adversary gives 0 reward to chosen action, 1 to all others.
     * Uses 8-dim state (matching SkillGenerationContext) for compatibility with contextual algorithms.
     */
    public static BenchmarkEnvironment adversarial(int numActions, double adversaryProb, long seed) {
        int sDim = 8; // match contextual algorithms' expected state dim
        String envName = String.format("ADV_a%d_p%.1f", numActions, adversaryProb);
        return new BenchmarkEnvironment(envName, sDim, numActions, new Random(seed)) {
            private final double[] stationaryMeans = initMeans(numActions, seed);

            @Override
            public double[] sampleState() {
                // Random state — context is irrelevant for adversarial rewards
                double[] state = new double[sDim];
                for (int i = 0; i < sDim; i++) state[i] = random.nextDouble();
                return state;
            }

            @Override
            public int optimalAction(double[] state) {
                int best = 0;
                for (int a = 1; a < numActions; a++) {
                    if (stationaryMeans[a] > stationaryMeans[best]) best = a;
                }
                return best;
            }

            @Override
            protected double computeReward(double[] state, int action) {
                if (random.nextDouble() < adversaryProb) {
                    return 0.0; // adversary punishes chosen action
                }
                return stationaryMeans[action] + random.nextGaussian() * 0.1;
            }
        };
    }

    /**
     * Binary decision environment for convergence (CONTINUE/STOP) benchmarking.
     */
    public static BenchmarkEnvironment binary(double pContinueSuccess, double pStopSuccess, long seed) {
        String envName = String.format("BIN_c%.2f_s%.2f", pContinueSuccess, pStopSuccess);
        return new BenchmarkEnvironment(envName, 1, 2, new Random(seed)) {
            @Override
            public double[] sampleState() {
                return new double[]{1.0};
            }

            @Override
            public int optimalAction(double[] state) {
                return pContinueSuccess >= pStopSuccess ? 0 : 1;
            }

            @Override
            protected double computeReward(double[] state, int action) {
                double p = (action == 0) ? pContinueSuccess : pStopSuccess;
                return random.nextDouble() < p ? 1.0 : 0.0;
            }
        };
    }

    /**
     * Weight optimization surface for Bayesian Weight Optimizer benchmarking.
     */
    public static WeightOptimizationSurface weightSurface(double[] optimalWeights,
                                                           double noiseStd,
                                                           boolean multimodal, long seed) {
        return new WeightOptimizationSurface(optimalWeights, noiseStd, multimodal, seed);
    }

    // Helper
    protected static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) sum += a[i] * b[i];
        return sum;
    }

    private static double[] initMeans(int numActions, long seed) {
        Random r = new Random(seed + 99);
        double[] means = new double[numActions];
        for (int a = 0; a < numActions; a++) means[a] = r.nextDouble();
        return means;
    }

    /**
     * Fitness surface for weight optimization benchmarking.
     */
    public static class WeightOptimizationSurface {
        private final double[] optimalWeights;
        private final double noiseStd;
        private final boolean multimodal;
        private final Random random;

        WeightOptimizationSurface(double[] optimalWeights, double noiseStd,
                                   boolean multimodal, long seed) {
            this.optimalWeights = optimalWeights;
            this.noiseStd = noiseStd;
            this.multimodal = multimodal;
            this.random = new Random(seed);
        }

        public String getName() {
            return String.format("WOS_%s_n%.1f%s",
                    formatWeights(optimalWeights), noiseStd, multimodal ? "_multi" : "");
        }

        public double[] getOptimalWeights() { return optimalWeights.clone(); }

        /** Evaluate fitness of a weight vector. Higher is better. */
        public double evaluate(double[] weights) {
            double dist = 0;
            for (int i = 0; i < optimalWeights.length; i++) {
                dist += (weights[i] - optimalWeights[i]) * (weights[i] - optimalWeights[i]);
            }
            double fitness = 1.0 - dist; // peak at optimal

            if (multimodal) {
                // Add a local optimum at [0.33, 0.33, 0.34]
                double localDist = 0;
                double localVal = 1.0 / optimalWeights.length;
                for (int i = 0; i < optimalWeights.length; i++) {
                    localDist += (weights[i] - localVal) * (weights[i] - localVal);
                }
                double localFitness = 0.7 - localDist; // lower peak
                fitness = Math.max(fitness, localFitness);
            }

            return fitness + random.nextGaussian() * noiseStd;
        }

        private static String formatWeights(double[] w) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < w.length; i++) {
                if (i > 0) sb.append("_");
                sb.append(String.format("%.1f", w[i]));
            }
            return sb.toString();
        }
    }
}
