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
 * Full benchmark suite runner: orchestrates all algorithms across all environments
 * and produces a comprehensive comparison report.
 *
 * This is the single entry point for "which algorithm should we use and with what config?"
 *
 * Run: mvn test -pl swarmai-core -Dgroups=benchmark -Dtest=FullBenchmarkSuite
 */
@Tag("benchmark")
class FullBenchmarkSuite {

    private static final int HORIZON = 2000;
    private static final int NUM_SEEDS = 30;
    private static final int STATE_DIM = 8;
    private static final int NUM_ACTIONS = 4;

    @Test
    void runFullComparison(@TempDir Path outputDir) throws IOException {
        Map<String, List<BenchmarkMetrics>> allResults = new LinkedHashMap<>();

        // Create all algorithms
        List<BanditAlgorithm> algorithms = Arrays.asList(
                AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0),
                AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 0.5),
                AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 2.0),
                AlgorithmBaselines.contextualThompsonSampling(NUM_ACTIONS, STATE_DIM, 0.5, 42),
                AlgorithmBaselines.contextualThompsonSampling(NUM_ACTIONS, STATE_DIM, 1.0, 42),
                AlgorithmBaselines.ucb1(NUM_ACTIONS, Math.sqrt(2)),
                AlgorithmBaselines.monteCarlo(NUM_ACTIONS, 0.1, 42),
                AlgorithmBaselines.monteCarlo(NUM_ACTIONS, 0.05, 42),
                AlgorithmBaselines.monteCarloDecaying(NUM_ACTIONS, 0.1, 0.1, 42),
                AlgorithmBaselines.softmax(NUM_ACTIONS, 0.1, 42),
                AlgorithmBaselines.softmax(NUM_ACTIONS, 0.5, 42),
                AlgorithmBaselines.exp3(NUM_ACTIONS, 0.1, 42),
                AlgorithmBaselines.flatMCTS(NUM_ACTIONS, 50, 42),
                AlgorithmBaselines.random(NUM_ACTIONS, 42)
        );

        // Create environments
        List<BenchmarkEnvironment> environments = Arrays.asList(
                BenchmarkEnvironment.stationaryContextual(STATE_DIM, NUM_ACTIONS, 0.0, 99),
                BenchmarkEnvironment.stationaryContextual(STATE_DIM, NUM_ACTIONS, 0.1, 99),
                BenchmarkEnvironment.stationaryContextual(STATE_DIM, NUM_ACTIONS, 0.3, 99),
                BenchmarkEnvironment.stationaryContextual(STATE_DIM, NUM_ACTIONS, 0.5, 99),
                BenchmarkEnvironment.nonStationary(STATE_DIM, NUM_ACTIONS, 0.1, 250, 99),
                BenchmarkEnvironment.nonStationary(STATE_DIM, NUM_ACTIONS, 0.1, 500, 99),
                BenchmarkEnvironment.adversarial(NUM_ACTIONS, 0.5, 99)
        );

        // Run all combinations
        for (BanditAlgorithm algo : algorithms) {
            List<BenchmarkMetrics> algoResults = new ArrayList<>();

            for (BenchmarkEnvironment env : environments) {
                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    env.reset();

                    // Use a fresh env instance per seed for proper randomization
                    BenchmarkEnvironment freshEnv = recreateEnvironment(env, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, freshEnv, seed, HORIZON);
                    algoResults.add(metrics);
                }
            }

            allResults.put(algo.name(), algoResults);
        }

        // Generate reports
        BenchmarkReportGenerator.writeReport(outputDir, allResults);

        // Print summary to console
        System.out.println("\n=== FULL BENCHMARK COMPARISON ===\n");
        System.out.println(BenchmarkReportGenerator.generateMarkdownTable(allResults));

        // Verify output
        assertTrue(Files.exists(outputDir.resolve("summary_report.json")));
        assertTrue(Files.exists(outputDir.resolve("algorithm_comparison_summary.csv")));
        assertTrue(Files.exists(outputDir.resolve("comparison_table.md")));
    }

    @Test
    void monteCarloVsCurrentAlgorithms(@TempDir Path outputDir) throws IOException {
        // Focused comparison answering: "Are Monte Carlo methods more powerful?"
        List<String> rows = new ArrayList<>();
        rows.add("comparison,environment,mc_regret,mc_ci,current_regret,current_ci," +
                "p_value,cohens_d,mc_wins");

        // MC vs LinUCB on contextual problems
        double[] noises = {0.0, 0.1, 0.3, 0.5};
        for (double noise : noises) {
            BanditAlgorithm mc = AlgorithmBaselines.monteCarlo(NUM_ACTIONS, 0.1, 42);
            BanditAlgorithm linucb = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0);

            double[] mcRegrets = new double[NUM_SEEDS];
            double[] linRegrets = new double[NUM_SEEDS];

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                mc.reset(); linucb.reset();

                BenchmarkEnvironment env1 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);
                BenchmarkEnvironment env2 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);

                mcRegrets[s] = runEpisode(mc, env1, seed, HORIZON).getCumulativeRegret();
                linRegrets[s] = runEpisode(linucb, env2, seed, HORIZON).getCumulativeRegret();
            }

            double pVal = BenchmarkMetrics.wilcoxonSignedRank(mcRegrets, linRegrets);
            double d = BenchmarkMetrics.cohensD(mcRegrets, linRegrets);
            double mcMean = Arrays.stream(mcRegrets).average().orElse(0);
            double linMean = Arrays.stream(linRegrets).average().orElse(0);
            boolean mcWins = mcMean < linMean;

            rows.add(String.format("MC_vs_LinUCB,SCB_noise%.1f,%.4f,%.4f,%.4f,%.4f,%.6f,%.4f,%b",
                    noise, mcMean, BenchmarkMetrics.bootstrapCI(mcRegrets, 1000),
                    linMean, BenchmarkMetrics.bootstrapCI(linRegrets, 1000),
                    pVal, d, mcWins));

            System.out.printf("[MC vs LinUCB, noise=%.1f] MC=%.1f, LinUCB=%.1f, p=%.4f, d=%.2f → %s%n",
                    noise, mcMean, linMean, pVal, d, mcWins ? "MC WINS" : "LinUCB WINS");
        }

        // MC vs Thompson Sampling on binary problems
        double[][] binaryScenarios = {{0.7, 0.3}, {0.6, 0.4}, {0.55, 0.45}};
        for (double[] scenario : binaryScenarios) {
            BanditAlgorithm mc = AlgorithmBaselines.monteCarlo(2, 0.1, 42);
            BanditAlgorithm ts = AlgorithmBaselines.thompsonSampling(2, 42);

            double[] mcRegrets = new double[NUM_SEEDS];
            double[] tsRegrets = new double[NUM_SEEDS];

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                mc.reset(); ts.reset();

                BenchmarkEnvironment env1 = BenchmarkEnvironment.binary(
                        scenario[0], scenario[1], seed);
                BenchmarkEnvironment env2 = BenchmarkEnvironment.binary(
                        scenario[0], scenario[1], seed);

                mcRegrets[s] = runEpisode(mc, env1, seed, HORIZON).getCumulativeRegret();
                tsRegrets[s] = runEpisode(ts, env2, seed, HORIZON).getCumulativeRegret();
            }

            double pVal = BenchmarkMetrics.wilcoxonSignedRank(mcRegrets, tsRegrets);
            double d = BenchmarkMetrics.cohensD(mcRegrets, tsRegrets);
            double mcMean = Arrays.stream(mcRegrets).average().orElse(0);
            double tsMean = Arrays.stream(tsRegrets).average().orElse(0);
            boolean mcWins = mcMean < tsMean;

            rows.add(String.format("MC_vs_TS,BIN_%.2f_%.2f,%.4f,%.4f,%.4f,%.4f,%.6f,%.4f,%b",
                    scenario[0], scenario[1],
                    mcMean, BenchmarkMetrics.bootstrapCI(mcRegrets, 1000),
                    tsMean, BenchmarkMetrics.bootstrapCI(tsRegrets, 1000),
                    pVal, d, mcWins));

            System.out.printf("[MC vs TS, p_c=%.2f] MC=%.1f, TS=%.1f, p=%.4f → %s%n",
                    scenario[0], mcMean, tsMean, pVal, mcWins ? "MC WINS" : "TS WINS");
        }

        // MCTS vs LinUCB
        for (double noise : noises) {
            BanditAlgorithm mcts = AlgorithmBaselines.flatMCTS(NUM_ACTIONS, 100, 42);
            BanditAlgorithm linucb = AlgorithmBaselines.linUCB(NUM_ACTIONS, STATE_DIM, 1.0);

            double[] mctsRegrets = new double[NUM_SEEDS];
            double[] linRegrets = new double[NUM_SEEDS];

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                mcts.reset(); linucb.reset();

                BenchmarkEnvironment env1 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);
                BenchmarkEnvironment env2 = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, noise, seed);

                mctsRegrets[s] = runEpisode(mcts, env1, seed, HORIZON).getCumulativeRegret();
                linRegrets[s] = runEpisode(linucb, env2, seed, HORIZON).getCumulativeRegret();
            }

            double pVal = BenchmarkMetrics.wilcoxonSignedRank(mctsRegrets, linRegrets);
            double d = BenchmarkMetrics.cohensD(mctsRegrets, linRegrets);
            double mctsMean = Arrays.stream(mctsRegrets).average().orElse(0);
            double linMean = Arrays.stream(linRegrets).average().orElse(0);
            boolean mctsWins = mctsMean < linMean;

            rows.add(String.format("MCTS_vs_LinUCB,SCB_noise%.1f,%.4f,%.4f,%.4f,%.4f,%.6f,%.4f,%b",
                    noise, mctsMean, BenchmarkMetrics.bootstrapCI(mctsRegrets, 1000),
                    linMean, BenchmarkMetrics.bootstrapCI(linRegrets, 1000),
                    pVal, d, mctsWins));
        }

        Path outFile = outputDir.resolve("monte_carlo_comparison.csv");
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

    private BenchmarkEnvironment recreateEnvironment(BenchmarkEnvironment template, long seed) {
        String name = template.getName();
        int sDim = template.getStateDim();
        int nAct = template.getNumActions();

        if (name.startsWith("SCB")) {
            // Parse noise from name
            double noise = Double.parseDouble(name.substring(name.lastIndexOf("n") + 1));
            return BenchmarkEnvironment.stationaryContextual(sDim, nAct, noise, seed);
        } else if (name.startsWith("NSB")) {
            String regimePart = name.substring(name.lastIndexOf("regime") + 6);
            int regime = Integer.parseInt(regimePart);
            return BenchmarkEnvironment.nonStationary(sDim, nAct, 0.1, regime, seed);
        } else if (name.startsWith("ADV")) {
            String probPart = name.substring(name.lastIndexOf("p") + 1);
            double prob = Double.parseDouble(probPart);
            return BenchmarkEnvironment.adversarial(nAct, prob, seed);
        }
        return template;
    }
}
