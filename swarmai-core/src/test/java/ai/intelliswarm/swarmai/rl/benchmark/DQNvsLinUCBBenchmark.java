package ai.intelliswarm.swarmai.rl.benchmark;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused benchmark: Does DQN justify enterprise-only gating over LinUCB?
 *
 * Tests the core question: is a neural network (DQN with epsilon-greedy)
 * better than a linear model (LinUCB with UCB exploration) for the
 * skill-generation contextual bandit problem?
 *
 * The enterprise DeepRLPolicy uses self-transitions (nextState = state),
 * meaning it operates as a contextual bandit — NOT a sequential MDP.
 * This benchmark tests whether DQN adds value in that regime.
 */
@Tag("benchmark")
class DQNvsLinUCBBenchmark {

    private static final int NUM_SEEDS = 30;
    private static final int STATE_DIM = 8;  // matches SkillGenerationContext
    private static final int NUM_ACTIONS = 4; // GENERATE, GENERATE_SIMPLE, USE_EXISTING, SKIP

    @Test
    void dqnVsLinucbStationary(@TempDir Path outputDir) throws IOException {
        int[] horizons = {500, 1000, 2000, 5000};
        double[] noiseLevels = {0.0, 0.1, 0.3, 0.5};

        List<String> rows = new ArrayList<>();
        rows.add("horizon,noise,algorithm,mean_regret,ci_regret,mean_opt_rate,mean_decision_us,mean_update_us");

        for (int horizon : horizons) {
            for (double noise : noiseLevels) {
                List<BanditAlgorithm> algorithms = createCompetitors();

                for (BanditAlgorithm algo : algorithms) {
                    double[] regrets = new double[NUM_SEEDS];
                    double[] optRates = new double[NUM_SEEDS];
                    double[] decisionUs = new double[NUM_SEEDS];
                    double[] updateUs = new double[NUM_SEEDS];

                    for (int s = 0; s < NUM_SEEDS; s++) {
                        long seed = 42 + s;
                        algo.reset();
                        BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                                STATE_DIM, NUM_ACTIONS, noise, seed);
                        BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);

                        regrets[s] = metrics.getCumulativeRegret();
                        optRates[s] = metrics.getOptimalActionRate();
                        decisionUs[s] = metrics.getAvgDecisionLatencyMicros();
                        updateUs[s] = metrics.getAvgUpdateLatencyMicros();
                    }

                    double meanRegret = Arrays.stream(regrets).average().orElse(0);
                    double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
                    double meanOpt = Arrays.stream(optRates).average().orElse(0);
                    double meanDecUs = Arrays.stream(decisionUs).average().orElse(0);
                    double meanUpdUs = Arrays.stream(updateUs).average().orElse(0);

                    rows.add(String.format("%d,%.1f,%s,%.4f,%.4f,%.4f,%.2f,%.2f",
                            horizon, noise, algo.name(),
                            meanRegret, ci, meanOpt, meanDecUs, meanUpdUs));

                    System.out.printf("[H=%d n=%.1f] %35s: regret=%8.1f +/- %5.1f  opt=%.3f  dec=%6.1fus  upd=%6.1fus%n",
                            horizon, noise, algo.name(), meanRegret, ci, meanOpt, meanDecUs, meanUpdUs);
                }
                System.out.println();
            }
        }

        // Statistical comparison: DQN vs LinUCB
        System.out.println("=== STATISTICAL TESTS: DQN vs LinUCB ===");
        BanditAlgorithm dqnDefault = createDQNDefault();
        BanditAlgorithm linucb = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0);

        for (double noise : noiseLevels) {
            double[] dqnRegrets = new double[NUM_SEEDS];
            double[] linRegrets = new double[NUM_SEEDS];

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                dqnDefault.reset();
                linucb.reset();

                BenchmarkEnvironment env1 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);
                BenchmarkEnvironment env2 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);

                dqnRegrets[s] = runEpisode(dqnDefault, env1, seed, 2000).getCumulativeRegret();
                linRegrets[s] = runEpisode(linucb, env2, seed, 2000).getCumulativeRegret();
            }

            double p = BenchmarkMetrics.wilcoxonSignedRank(dqnRegrets, linRegrets);
            double d = BenchmarkMetrics.cohensD(dqnRegrets, linRegrets);
            double dqnMean = Arrays.stream(dqnRegrets).average().orElse(0);
            double linMean = Arrays.stream(linRegrets).average().orElse(0);
            boolean dqnWins = dqnMean < linMean;

            System.out.printf("[noise=%.1f] DQN=%.1f vs LinUCB=%.1f  p=%.4f d=%.2f  -> %s%n",
                    noise, dqnMean, linMean, p, d, dqnWins ? "DQN WINS" : "LinUCB WINS");
        }

        Path outFile = outputDir.resolve("dqn_vs_linucb.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void dqnVsLinucbNonLinearRewards(@TempDir Path outputDir) throws IOException {
        // Test if DQN can exploit non-linear reward structure that LinUCB cannot
        int horizon = 2000;
        List<String> rows = new ArrayList<>();
        rows.add("reward_type,algorithm,mean_regret,ci_regret,mean_opt_rate");

        // Non-linear reward: r(s,a) = sin(s^T w_a) + noise
        // This is where DQN should theoretically shine over linear LinUCB
        String[] envTypes = {"linear", "nonlinear_sin", "nonlinear_quadratic"};

        for (String envType : envTypes) {
            List<BanditAlgorithm> algorithms = createCompetitors();

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];
                double[] optRates = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = createNonLinearEnv(envType, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);

                    regrets[s] = metrics.getCumulativeRegret();
                    optRates[s] = metrics.getOptimalActionRate();
                }

                double meanRegret = Arrays.stream(regrets).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
                double meanOpt = Arrays.stream(optRates).average().orElse(0);

                rows.add(String.format("%s,%s,%.4f,%.4f,%.4f",
                        envType, algo.name(), meanRegret, ci, meanOpt));

                System.out.printf("[%20s] %35s: regret=%8.1f +/- %5.1f  opt=%.3f%n",
                        envType, algo.name(), meanRegret, ci, meanOpt);
            }
            System.out.println();
        }

        Path outFile = outputDir.resolve("dqn_nonlinear.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void dqnHyperparameterSensitivity(@TempDir Path outputDir) throws IOException {
        // DQN has many more hyperparameters than LinUCB — is it harder to tune?
        int horizon = 2000;

        // Sweep learning rates
        float[] lrs = {0.0001f, 0.0005f, 0.001f, 0.005f, 0.01f, 0.05f};
        // Sweep hidden sizes
        int[][] hiddens = {{16, 8}, {32, 16}, {64, 32}, {128, 64}};
        // Sweep epsilon decay
        int[] decaySteps = {100, 250, 500, 1000, 2000};

        List<String> rows = new ArrayList<>();
        rows.add("param_name,param_value,mean_regret,ci_regret");

        // LR sweep
        for (float lr : lrs) {
            BanditAlgorithm dqn = AlgorithmBaselines.simulatedDQN(
                    NUM_ACTIONS, STATE_DIM, 64, 32, lr, 1.0, 0.05, 500, 10, 50, 50, 42);
            double[] regrets = runMultiSeed(dqn, 0.1, horizon);
            double mean = Arrays.stream(regrets).average().orElse(0);
            double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
            rows.add(String.format("learning_rate,%.4f,%.4f,%.4f", lr, mean, ci));
            System.out.printf("[DQN lr=%.4f] regret=%.1f +/- %.1f%n", lr, mean, ci);
        }

        // Hidden size sweep
        for (int[] h : hiddens) {
            BanditAlgorithm dqn = AlgorithmBaselines.simulatedDQN(
                    NUM_ACTIONS, STATE_DIM, h[0], h[1], 0.001f, 1.0, 0.05, 500, 10, 50, 50, 42);
            double[] regrets = runMultiSeed(dqn, 0.1, horizon);
            double mean = Arrays.stream(regrets).average().orElse(0);
            double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
            rows.add(String.format("hidden_size,%d/%d,%.4f,%.4f", h[0], h[1], mean, ci));
            System.out.printf("[DQN hidden=%d/%d] regret=%.1f +/- %.1f%n", h[0], h[1], mean, ci);
        }

        // Epsilon decay sweep
        for (int decay : decaySteps) {
            BanditAlgorithm dqn = AlgorithmBaselines.simulatedDQN(
                    NUM_ACTIONS, STATE_DIM, 64, 32, 0.001f, 1.0, 0.05, decay, 10, 50, 50, 42);
            double[] regrets = runMultiSeed(dqn, 0.1, horizon);
            double mean = Arrays.stream(regrets).average().orElse(0);
            double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
            rows.add(String.format("epsilon_decay,%d,%.4f,%.4f", decay, mean, ci));
            System.out.printf("[DQN decay=%d] regret=%.1f +/- %.1f%n", decay, mean, ci);
        }

        // Compare: LinUCB has 1 hyperparameter (alpha)
        System.out.println("\n--- LinUCB alpha sweep for comparison ---");
        double[] alphas = {0.1, 0.5, 1.0, 1.35, 2.0, 5.0};
        for (double alpha : alphas) {
            BanditAlgorithm lin = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, alpha);
            double[] regrets = runMultiSeed(lin, 0.1, horizon);
            double mean = Arrays.stream(regrets).average().orElse(0);
            double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
            rows.add(String.format("linucb_alpha,%.2f,%.4f,%.4f", alpha, mean, ci));
            System.out.printf("[LinUCB alpha=%.2f] regret=%.1f +/- %.1f%n", alpha, mean, ci);
        }

        Path outFile = outputDir.resolve("dqn_hyperparam_sensitivity.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    // ======================================================================
    // Helpers
    // ======================================================================

    private List<BanditAlgorithm> createCompetitors() {
        List<BanditAlgorithm> algos = new ArrayList<>();
        algos.add(AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0));
        algos.add(AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.35));
        algos.add(AlgorithmBaselines.contextualThompsonSampling(NUM_ACTIONS, STATE_DIM, 0.5, 42));
        algos.add(createDQNDefault());
        algos.add(AlgorithmBaselines.simulatedDQN( // well-tuned DQN
                NUM_ACTIONS, STATE_DIM, 64, 32, 0.001f, 1.0, 0.05, 250, 10, 50, 30, 42));
        // NeuralLinUCB variants — the proposed enterprise replacement
        algos.add(AlgorithmBaselines.neuralLinUCB(NUM_ACTIONS, STATE_DIM,
                32, 8, 1.0, 0.001f, 20, 30, 42));
        algos.add(AlgorithmBaselines.neuralLinUCB(NUM_ACTIONS, STATE_DIM,
                64, 16, 1.0, 0.001f, 20, 30, 42));
        algos.add(AlgorithmBaselines.neuralLinUCB(NUM_ACTIONS, STATE_DIM,
                32, 8, 2.0, 0.0005f, 10, 20, 42));
        return algos;
    }

    private BanditAlgorithm createDQNDefault() {
        // Matches enterprise DeepRLPolicy defaults
        return AlgorithmBaselines.simulatedDQN(
                NUM_ACTIONS, STATE_DIM, 64, 32, 0.001f, 1.0, 0.05, 500, 10, 50, 50, 42);
    }

    private double[] runMultiSeed(BanditAlgorithm algo, double noise, int horizon) {
        double[] regrets = new double[NUM_SEEDS];
        for (int s = 0; s < NUM_SEEDS; s++) {
            long seed = 42 + s;
            algo.reset();
            BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                    STATE_DIM, NUM_ACTIONS, noise, seed);
            regrets[s] = runEpisode(algo, env, seed, horizon).getCumulativeRegret();
        }
        return regrets;
    }

    private BenchmarkMetrics runEpisode(BanditAlgorithm algo, BenchmarkEnvironment env,
                                        long seed, int horizon) {
        BenchmarkMetrics metrics = new BenchmarkMetrics(algo.name(), env.getName(), seed);
        for (int t = 1; t <= horizon; t++) {
            double[] state = env.sampleState();
            int optAction = env.optimalAction(state);
            double optReward = env.optimalReward(state);

            long startNs = System.nanoTime();
            int action = algo.selectAction(state);
            metrics.recordDecisionLatency(System.nanoTime() - startNs);

            double reward = env.reward(state, action);

            long updateStart = System.nanoTime();
            algo.update(state, action, reward);
            metrics.recordUpdateLatency(System.nanoTime() - updateStart);

            metrics.recordStep(t, action, reward, optAction, optReward);
        }
        return metrics;
    }

    private BenchmarkEnvironment createNonLinearEnv(String type, long seed) {
        Random rng = new Random(seed);
        double[][] weights = new double[NUM_ACTIONS][STATE_DIM];
        for (int a = 0; a < NUM_ACTIONS; a++)
            for (int d = 0; d < STATE_DIM; d++)
                weights[a][d] = rng.nextGaussian();

        return switch (type) {
            case "linear" -> BenchmarkEnvironment.stationaryContextual(STATE_DIM, NUM_ACTIONS, 0.1, seed);
            case "nonlinear_sin" -> new BenchmarkEnvironment(
                    "NONLIN_SIN", STATE_DIM, NUM_ACTIONS, new Random(seed + 1)) {
                @Override public int optimalAction(double[] state) {
                    int best = 0; double bestVal = Double.NEGATIVE_INFINITY;
                    for (int a = 0; a < NUM_ACTIONS; a++) {
                        double val = Math.sin(dot(state, weights[a]) * 3.0);
                        if (val > bestVal) { bestVal = val; best = a; }
                    }
                    return best;
                }
                @Override protected double computeReward(double[] state, int action) {
                    return Math.sin(dot(state, weights[action]) * 3.0) + random.nextGaussian() * 0.1;
                }
            };
            case "nonlinear_quadratic" -> new BenchmarkEnvironment(
                    "NONLIN_QUAD", STATE_DIM, NUM_ACTIONS, new Random(seed + 1)) {
                @Override public int optimalAction(double[] state) {
                    int best = 0; double bestVal = Double.NEGATIVE_INFINITY;
                    for (int a = 0; a < NUM_ACTIONS; a++) {
                        double lin = dot(state, weights[a]);
                        double val = lin * lin - 0.5 * lin;
                        if (val > bestVal) { bestVal = val; best = a; }
                    }
                    return best;
                }
                @Override protected double computeReward(double[] state, int action) {
                    double lin = dot(state, weights[action]);
                    return lin * lin - 0.5 * lin + random.nextGaussian() * 0.1;
                }
            };
            default -> throw new IllegalArgumentException("Unknown env type: " + type);
        };
    }
}
