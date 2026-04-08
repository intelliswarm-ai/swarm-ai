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
 * Convergence speed benchmark: measures how quickly each algorithm
 * identifies the optimal action across different environments.
 *
 * Key questions answered:
 * - Which algorithm converges fastest on stationary problems?
 * - Does context help? (LinUCB/ContextualTS vs UCB1/MonteCarlo)
 * - How does noise level affect convergence?
 */
@Tag("benchmark")
class ConvergenceBenchmark {

    private static final int HORIZON = 2000;
    private static final int NUM_SEEDS = 30;
    private static final int CONVERGENCE_WINDOW = 100;
    private static final int STATE_DIM = 8;
    private static final int NUM_ACTIONS = 4;

    @Test
    void stationaryConvergence(@TempDir Path outputDir) throws IOException {
        double[] noiseLevels = {0.0, 0.1, 0.3, 0.5};
        List<String> summaryRows = new ArrayList<>();
        summaryRows.add(BenchmarkMetrics.summaryHeader());

        for (double noise : noiseLevels) {
            List<BanditAlgorithm> algorithms = createAlgorithms();

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];
                int[] convergenceSteps = new int[NUM_SEEDS];
                double[] optimalRates = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                            STATE_DIM, NUM_ACTIONS, noise, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                    regrets[s] = metrics.getCumulativeRegret();
                    convergenceSteps[s] = metrics.getConvergenceStep(CONVERGENCE_WINDOW);
                    optimalRates[s] = metrics.getOptimalActionRate();
                    summaryRows.add(metrics.toSummaryRow());
                }

                double meanRegret = Arrays.stream(regrets).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
                double meanConvergence = Arrays.stream(convergenceSteps)
                        .filter(c -> c > 0).average().orElse(-1);
                double meanOptRate = Arrays.stream(optimalRates).average().orElse(0);

                System.out.printf("[SCB noise=%.1f] %25s: regret=%.1f +/- %.1f, " +
                                "convergence=%.0f, optRate=%.3f%n",
                        noise, algo.name(), meanRegret, ci, meanConvergence, meanOptRate);
            }
        }

        // Write results
        Path outFile = outputDir.resolve("convergence_stationary.csv");
        Files.write(outFile, summaryRows);
        assertTrue(Files.size(outFile) > 0, "Output file should contain data");
    }

    @Test
    void binaryConvergence(@TempDir Path outputDir) throws IOException {
        // Test convergence for CONTINUE/STOP decisions with different signal strengths
        double[][] scenarios = {
                {0.8, 0.2},  // clear: CONTINUE is best
                {0.2, 0.8},  // clear: STOP is best
                {0.6, 0.4},  // moderate signal
                {0.55, 0.45}, // weak signal — hardest
                {0.5, 0.5},  // ambiguous — no correct answer
        };

        List<String> summaryRows = new ArrayList<>();
        summaryRows.add(BenchmarkMetrics.summaryHeader());

        for (double[] scenario : scenarios) {
            double pContinue = scenario[0];
            double pStop = scenario[1];

            List<BanditAlgorithm> algorithms = createBinaryAlgorithms();

            for (BanditAlgorithm algo : algorithms) {
                double[] convergenceSteps = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.binary(pContinue, pStop, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);

                    convergenceSteps[s] = metrics.getConvergenceStep(CONVERGENCE_WINDOW);
                    summaryRows.add(metrics.toSummaryRow());
                }

                double meanConv = Arrays.stream(convergenceSteps)
                        .filter(c -> c > 0).average().orElse(-1);

                System.out.printf("[BIN p_c=%.2f p_s=%.2f] %25s: convergence=%.0f%n",
                        pContinue, pStop, algo.name(), meanConv);
            }
        }

        Path outFile = outputDir.resolve("convergence_binary.csv");
        Files.write(outFile, summaryRows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void contextValueTest(@TempDir Path outputDir) throws IOException {
        // Compare contextual (LinUCB, ContextualTS) vs context-free (UCB1, MC) algorithms
        // on the same contextual problem to measure the value of state features
        List<String> rows = new ArrayList<>();
        rows.add("algorithm,is_contextual,noise,mean_regret,ci_regret,mean_opt_rate");

        double[] noiseLevels = {0.0, 0.1, 0.3, 0.5, 1.0};

        for (double noise : noiseLevels) {
            Map<String, Boolean> algoContextual = new LinkedHashMap<>();
            List<BanditAlgorithm> algorithms = new ArrayList<>();

            BanditAlgorithm linucb = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0);
            algorithms.add(linucb); algoContextual.put(linucb.name(), true);

            BanditAlgorithm cts = AlgorithmBaselines.contextualThompsonSampling(NUM_ACTIONS, STATE_DIM, 0.5, 42);
            algorithms.add(cts); algoContextual.put(cts.name(), true);

            BanditAlgorithm ucb1 = AlgorithmBaselines.ucb1(NUM_ACTIONS, Math.sqrt(2));
            algorithms.add(ucb1); algoContextual.put(ucb1.name(), false);

            BanditAlgorithm mc = AlgorithmBaselines.monteCarlo(NUM_ACTIONS, 0.1, 42);
            algorithms.add(mc); algoContextual.put(mc.name(), false);

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];
                double[] optRates = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                            STATE_DIM, NUM_ACTIONS, noise, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, HORIZON);
                    regrets[s] = metrics.getCumulativeRegret();
                    optRates[s] = metrics.getOptimalActionRate();
                }

                double meanRegret = Arrays.stream(regrets).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
                double meanOptRate = Arrays.stream(optRates).average().orElse(0);

                rows.add(String.format("%s,%b,%.1f,%.4f,%.4f,%.4f",
                        algo.name(), algoContextual.get(algo.name()),
                        noise, meanRegret, ci, meanOptRate));
            }
        }

        Path outFile = outputDir.resolve("context_value_analysis.csv");
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

    private List<BanditAlgorithm> createAlgorithms() {
        List<BanditAlgorithm> algos = new ArrayList<>();
        algos.add(AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0));
        algos.add(AlgorithmBaselines.contextualThompsonSampling(NUM_ACTIONS, STATE_DIM, 0.5, 42));
        algos.add(AlgorithmBaselines.ucb1(NUM_ACTIONS, Math.sqrt(2)));
        algos.add(AlgorithmBaselines.monteCarlo(NUM_ACTIONS, 0.1, 42));
        algos.add(AlgorithmBaselines.monteCarloDecaying(NUM_ACTIONS, 0.1, 0.1, 42));
        algos.add(AlgorithmBaselines.softmax(NUM_ACTIONS, 0.1, 42));
        algos.add(AlgorithmBaselines.exp3(NUM_ACTIONS, 0.1, 42));
        algos.add(AlgorithmBaselines.flatMCTS(NUM_ACTIONS, 50, 42));
        algos.add(AlgorithmBaselines.random(NUM_ACTIONS, 42));
        return algos;
    }

    private List<BanditAlgorithm> createBinaryAlgorithms() {
        List<BanditAlgorithm> algos = new ArrayList<>();
        algos.add(AlgorithmBaselines.thompsonSampling(2, 42));
        algos.add(AlgorithmBaselines.ucb1(2, Math.sqrt(2)));
        algos.add(AlgorithmBaselines.monteCarlo(2, 0.1, 42));
        algos.add(AlgorithmBaselines.monteCarloDecaying(2, 0.1, 0.1, 42));
        algos.add(AlgorithmBaselines.softmax(2, 0.1, 42));
        algos.add(AlgorithmBaselines.exp3(2, 0.1, 42));
        algos.add(AlgorithmBaselines.random(2, 42));
        return algos;
    }
}
