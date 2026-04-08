package ai.intelliswarm.swarmai.rl.benchmark;

import ai.intelliswarm.swarmai.rl.bandit.BayesianWeightOptimizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hyperparameter sensitivity benchmark: sweeps key parameters to find
 * optimal configurations and measure sensitivity.
 *
 * Key questions answered:
 * - What is the optimal alpha for LinUCB?
 * - How sensitive is each algorithm to its hyperparameters?
 * - What is the Pareto frontier of (parameter, regret)?
 * - What parameter range stays within 10% of optimal (robustness)?
 */
@Tag("benchmark")
class HyperparameterSweepBenchmark {

    private static final int HORIZON = 2000;
    private static final int NUM_SEEDS = 30;
    private static final int STATE_DIM = 8;
    private static final int NUM_ACTIONS = 4;

    @Test
    void linucbAlphaSweep(@TempDir Path outputDir) throws IOException {
        // Log-spaced alpha from 0.01 to 5.0
        double[] alphas = logSpace(0.01, 5.0, 20);
        List<String> rows = new ArrayList<>();
        rows.add("parameter_name,parameter_value,environment,seed,cumulative_regret," +
                "convergence_step,optimal_rate,decision_latency_us");

        for (double alpha : alphas) {
            BanditAlgorithm algo = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, alpha);

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                rows.add(String.format("alpha,%.4f,%s,%d,%.4f,%d,%.4f,%.2f",
                        alpha, env.getName(), seed,
                        metrics.getCumulativeRegret(),
                        metrics.getConvergenceStep(100),
                        metrics.getOptimalActionRate(),
                        metrics.getAvgDecisionLatencyMicros()));
            }
        }

        Path outFile = outputDir.resolve("linucb_alpha_sweep.csv");
        Files.write(outFile, rows);

        // Report summary
        reportSweepSummary("LinUCB alpha", alphas, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void contextualTSSweep(@TempDir Path outputDir) throws IOException {
        double[] vs = logSpace(0.01, 5.0, 15);
        List<String> rows = new ArrayList<>();
        rows.add("parameter_name,parameter_value,environment,seed,cumulative_regret,convergence_step,optimal_rate");

        for (double v : vs) {
            BanditAlgorithm algo = AlgorithmBaselines.contextualThompsonSampling(
                    NUM_ACTIONS, STATE_DIM, v, 42);

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                rows.add(String.format("v,%.4f,%s,%d,%.4f,%d,%.4f",
                        v, env.getName(), seed,
                        metrics.getCumulativeRegret(),
                        metrics.getConvergenceStep(100),
                        metrics.getOptimalActionRate()));
            }
        }

        Path outFile = outputDir.resolve("contextual_ts_v_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void exp3GammaSweep(@TempDir Path outputDir) throws IOException {
        double[] gammas = logSpace(0.001, 0.5, 15);
        List<String> rows = new ArrayList<>();
        rows.add("parameter_name,parameter_value,environment,seed,cumulative_regret,optimal_rate");

        for (double gamma : gammas) {
            BanditAlgorithm algo = AlgorithmBaselines.exp3(NUM_ACTIONS, gamma, 42);

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                rows.add(String.format("gamma,%.4f,%s,%d,%.4f,%.4f",
                        gamma, env.getName(), seed,
                        metrics.getCumulativeRegret(),
                        metrics.getOptimalActionRate()));
            }
        }

        Path outFile = outputDir.resolve("exp3_gamma_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void softmaxTemperatureSweep(@TempDir Path outputDir) throws IOException {
        double[] temperatures = logSpace(0.01, 5.0, 15);
        List<String> rows = new ArrayList<>();
        rows.add("parameter_name,parameter_value,environment,seed,cumulative_regret,optimal_rate");

        for (double temp : temperatures) {
            BanditAlgorithm algo = AlgorithmBaselines.softmax(NUM_ACTIONS, temp, 42);

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                rows.add(String.format("temperature,%.4f,%s,%d,%.4f,%.4f",
                        temp, env.getName(), seed,
                        metrics.getCumulativeRegret(),
                        metrics.getOptimalActionRate()));
            }
        }

        Path outFile = outputDir.resolve("softmax_temperature_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void monteCarloEpsilonSweep(@TempDir Path outputDir) throws IOException {
        double[] epsilons = {0.01, 0.02, 0.05, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5};
        List<String> rows = new ArrayList<>();
        rows.add("parameter_name,parameter_value,environment,seed,cumulative_regret,optimal_rate");

        for (double epsilon : epsilons) {
            BanditAlgorithm algo = AlgorithmBaselines.monteCarlo(NUM_ACTIONS, epsilon, 42);

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                rows.add(String.format("epsilon,%.4f,%s,%d,%.4f,%.4f",
                        epsilon, env.getName(), seed,
                        metrics.getCumulativeRegret(),
                        metrics.getOptimalActionRate()));
            }
        }

        Path outFile = outputDir.resolve("montecarlo_epsilon_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void bayesianWeightOptimizerSweep(@TempDir Path outputDir) throws IOException {
        int[] popSizes = {3, 5, 10, 15, 20, 30, 50};
        double[] sigmas = {0.01, 0.05, 0.1, 0.2, 0.3, 0.5};
        double[] optimalWeights = {0.6, 0.25, 0.15};

        List<String> rows = new ArrayList<>();
        rows.add("pop_size,mutation_sigma,seed,final_distance,best_fitness,generations_to_converge");

        for (int popSize : popSizes) {
            for (double sigma : sigmas) {
                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    BenchmarkEnvironment.WeightOptimizationSurface surface =
                            BenchmarkEnvironment.weightSurface(optimalWeights, 0.05, false, seed);

                    BayesianWeightOptimizer optimizer = new BayesianWeightOptimizer(
                            3, popSize, sigma, new Random(seed));

                    int convergedAt = -1;
                    for (int gen = 0; gen < 200; gen++) {
                        double[] weights = optimizer.getNextWeights();
                        double fitness = surface.evaluate(weights);
                        optimizer.recordFitness(weights, fitness);

                        if (gen % 10 == 9) {
                            optimizer.evolve();
                        }

                        // Check convergence: distance to optimal < 0.05
                        double[] best = optimizer.getBestWeights();
                        double dist = weightDistance(best, optimalWeights);
                        if (dist < 0.05 && convergedAt < 0) {
                            convergedAt = gen;
                        }
                    }

                    double[] best = optimizer.getBestWeights();
                    double finalDist = weightDistance(best, optimalWeights);

                    rows.add(String.format("%d,%.2f,%d,%.6f,%.6f,%d",
                            popSize, sigma, seed,
                            finalDist, optimizer.getBestFitness(), convergedAt));
                }
            }
        }

        Path outFile = outputDir.resolve("bayesian_evo_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void coldStartThresholdSweep(@TempDir Path outputDir) throws IOException {
        int[] coldStarts = {0, 10, 25, 50, 75, 100, 150, 200};
        List<String> rows = new ArrayList<>();
        rows.add("cold_start,seed,total_regret,cold_start_regret,post_cold_start_regret,convergence_step");

        for (int cs : coldStarts) {
            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;

                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);

                // Simulate cold-start: use heuristic (random-ish) for first cs steps, then LinUCB
                BanditAlgorithm heuristic = AlgorithmBaselines.random(NUM_ACTIONS, seed);
                BanditAlgorithm linucb = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0);

                BenchmarkMetrics metrics = new BenchmarkMetrics(
                        String.format("ColdStart(%d)", cs), env.getName(), seed);
                double coldStartRegret = 0;

                for (int t = 1; t <= HORIZON; t++) {
                    double[] state = env.sampleState();
                    int optAction = env.optimalAction(state);
                    double optReward = env.optimalReward(state);

                    int action;
                    if (t <= cs) {
                        action = heuristic.selectAction(state);
                    } else {
                        action = linucb.selectAction(state);
                    }

                    double reward = env.reward(state, action);
                    linucb.update(state, action, reward); // always feed to learner

                    metrics.recordStep(t, action, reward, optAction, optReward);
                    if (t == cs) coldStartRegret = metrics.getCumulativeRegret();
                }

                rows.add(String.format("%d,%d,%.4f,%.4f,%.4f,%d",
                        cs, seed,
                        metrics.getCumulativeRegret(),
                        coldStartRegret,
                        metrics.getCumulativeRegret() - coldStartRegret,
                        metrics.getConvergenceStep(100)));
            }
        }

        Path outFile = outputDir.resolve("cold_start_sweep.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void scalabilityBenchmark(@TempDir Path outputDir) throws IOException {
        // Test how algorithms scale with increasing state/action dimensions
        int[][] configs = {
                {2, 2}, {4, 2}, {8, 4}, {16, 4}, {32, 8}, {64, 8}, {128, 16}
        };

        List<String> rows = new ArrayList<>();
        rows.add("algorithm,state_dim,num_actions,mean_regret,mean_decision_us,mean_update_us,throughput");

        for (int[] config : configs) {
            int sDim = config[0];
            int nAct = config[1];

            List<BanditAlgorithm> algorithms = Arrays.asList(
                    AlgorithmBaselines.linUCB(nAct, sDim, 1.0),
                    AlgorithmBaselines.contextualThompsonSampling(nAct, sDim, 0.5, 42),
                    AlgorithmBaselines.ucb1(nAct, Math.sqrt(2)),
                    AlgorithmBaselines.monteCarlo(nAct, 0.1, 42),
                    AlgorithmBaselines.exp3(nAct, 0.1, 42)
            );

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];
                double[] decisionLatencies = new double[NUM_SEEDS];
                double[] updateLatencies = new double[NUM_SEEDS];
                double[] throughputs = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                            sDim, nAct, 0.1, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, 1000);

                    regrets[s] = metrics.getCumulativeRegret();
                    decisionLatencies[s] = metrics.getAvgDecisionLatencyMicros();
                    updateLatencies[s] = metrics.getAvgUpdateLatencyMicros();
                    throughputs[s] = metrics.getThroughputPerSecond();
                }

                rows.add(String.format("%s,%d,%d,%.4f,%.2f,%.2f,%.0f",
                        algo.name(), sDim, nAct,
                        Arrays.stream(regrets).average().orElse(0),
                        Arrays.stream(decisionLatencies).average().orElse(0),
                        Arrays.stream(updateLatencies).average().orElse(0),
                        Arrays.stream(throughputs).average().orElse(0)));
            }
        }

        Path outFile = outputDir.resolve("scalability.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    // ======================================================================
    // Helpers
    // ======================================================================

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

    private double weightDistance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(sum);
    }

    private static double[] logSpace(double start, double end, int steps) {
        double[] values = new double[steps];
        double logStart = Math.log(start);
        double logEnd = Math.log(end);
        for (int i = 0; i < steps; i++) {
            values[i] = Math.exp(logStart + (logEnd - logStart) * i / (steps - 1));
        }
        return values;
    }

    private void reportSweepSummary(String paramName, double[] paramValues, List<String> rows) {
        // Find parameter value with lowest mean regret
        Map<Double, List<Double>> regretByParam = new LinkedHashMap<>();
        for (String row : rows) {
            if (row.startsWith("parameter_name")) continue;
            String[] parts = row.split(",");
            double param = Double.parseDouble(parts[1]);
            double regret = Double.parseDouble(parts[4]);
            regretByParam.computeIfAbsent(param, k -> new ArrayList<>()).add(regret);
        }

        double bestParam = 0;
        double bestMeanRegret = Double.MAX_VALUE;
        for (Map.Entry<Double, List<Double>> entry : regretByParam.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(d -> d).average().orElse(0);
            if (mean < bestMeanRegret) {
                bestMeanRegret = mean;
                bestParam = entry.getKey();
            }
        }

        // Find robustness range (within 10% of best)
        double threshold = bestMeanRegret * 1.10;
        double robustLow = Double.MAX_VALUE, robustHigh = Double.MIN_VALUE;
        for (Map.Entry<Double, List<Double>> entry : regretByParam.entrySet()) {
            double mean = entry.getValue().stream().mapToDouble(d -> d).average().orElse(0);
            if (mean <= threshold) {
                robustLow = Math.min(robustLow, entry.getKey());
                robustHigh = Math.max(robustHigh, entry.getKey());
            }
        }

        System.out.printf("[%s] Best=%.4f (regret=%.1f), Robust range=[%.4f, %.4f]%n",
                paramName, bestParam, bestMeanRegret, robustLow, robustHigh);
    }
}
