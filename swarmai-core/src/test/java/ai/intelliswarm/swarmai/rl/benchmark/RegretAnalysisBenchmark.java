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
 * Regret analysis benchmark: measures cumulative and simple regret
 * across all algorithms and environments.
 *
 * Key questions answered:
 * - Which algorithm minimizes cumulative regret (best for deployment)?
 * - Which algorithm minimizes simple regret (best for final policy quality)?
 * - Are current algorithms better than Monte Carlo baselines?
 * - How does regret scale with horizon length?
 */
@Tag("benchmark")
class RegretAnalysisBenchmark {

    private static final int NUM_SEEDS = 30;
    private static final int STATE_DIM = 8;
    private static final int NUM_ACTIONS = 4;

    @Test
    void cumulativeRegretComparison(@TempDir Path outputDir) throws IOException {
        int[] horizons = {500, 1000, 2000, 5000};
        List<String> rows = new ArrayList<>();
        rows.add("algorithm,environment,horizon,seed,cumulative_regret,simple_regret,total_reward,optimal_rate");

        for (int horizon : horizons) {
            List<BanditAlgorithm> algorithms = createAllAlgorithms();

            for (BanditAlgorithm algo : algorithms) {
                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                            STATE_DIM, NUM_ACTIONS, 0.1, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);

                    rows.add(String.format("%s,%s,%d,%d,%.6f,%.6f,%.6f,%.4f",
                            algo.name(), env.getName(), horizon, seed,
                            metrics.getCumulativeRegret(), metrics.getSimpleRegret(),
                            metrics.getTotalReward(), metrics.getOptimalActionRate()));
                }
            }
        }

        Path outFile = outputDir.resolve("cumulative_regret_by_horizon.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void regretGrowthRate(@TempDir Path outputDir) throws IOException {
        // Measure regret at regular intervals to characterize growth rate
        // Theoretical: UCB1 = O(sqrt(T*ln(T))), EXP3 = O(sqrt(T*K*ln(K)))
        int horizon = 5000;
        int sampleInterval = 50;
        List<String> rows = new ArrayList<>();
        rows.add("algorithm,timestep,mean_cumulative_regret,ci_regret");

        List<BanditAlgorithm> algorithms = createAllAlgorithms();

        for (BanditAlgorithm algo : algorithms) {
            // Collect regret trajectories across seeds
            double[][] regretTrajectories = new double[NUM_SEEDS][];

            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);

                List<BenchmarkMetrics.StepRecord> steps = metrics.getSteps();
                int numSamples = horizon / sampleInterval;
                regretTrajectories[s] = new double[numSamples];
                for (int i = 0; i < numSamples; i++) {
                    int idx = Math.min((i + 1) * sampleInterval - 1, steps.size() - 1);
                    regretTrajectories[s][i] = steps.get(idx).cumulativeRegret();
                }
            }

            // Compute mean and CI at each sample point
            int numSamples = horizon / sampleInterval;
            for (int i = 0; i < numSamples; i++) {
                double[] values = new double[NUM_SEEDS];
                for (int s = 0; s < NUM_SEEDS; s++) {
                    values[s] = regretTrajectories[s][i];
                }
                double mean = Arrays.stream(values).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(values, 1000);
                int timestep = (i + 1) * sampleInterval;
                rows.add(String.format("%s,%d,%.4f,%.4f", algo.name(), timestep, mean, ci));
            }
        }

        Path outFile = outputDir.resolve("regret_growth_rate.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void nonStationaryRegret(@TempDir Path outputDir) throws IOException {
        int horizon = 3000;
        int[] regimeLengths = {100, 250, 500, 1000};
        List<String> rows = new ArrayList<>();
        rows.add("algorithm,regime_length,seed,cumulative_regret,adaptation_delays");

        for (int regimeLen : regimeLengths) {
            List<BanditAlgorithm> algorithms = createAllAlgorithms();

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.nonStationary(
                            STATE_DIM, NUM_ACTIONS, 0.1, regimeLen, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);
                    regrets[s] = metrics.getCumulativeRegret();

                    // Measure adaptation delay at each regime change
                    List<Integer> delays = new ArrayList<>();
                    for (int change = regimeLen; change < horizon; change += regimeLen) {
                        int delay = metrics.getAdaptationDelay(change);
                        delays.add(delay);
                    }

                    rows.add(String.format("%s,%d,%d,%.4f,%s",
                            algo.name(), regimeLen, seed,
                            metrics.getCumulativeRegret(),
                            delays.toString()));
                }

                double mean = Arrays.stream(regrets).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);
                System.out.printf("[NSB regime=%d] %25s: regret=%.1f +/- %.1f%n",
                        regimeLen, algo.name(), mean, ci);
            }
        }

        Path outFile = outputDir.resolve("nonstationary_regret.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void adversarialRegret(@TempDir Path outputDir) throws IOException {
        int horizon = 2000;
        double[] adversaryProbs = {0.0, 0.25, 0.5, 0.75, 1.0};
        List<String> rows = new ArrayList<>();
        rows.add("algorithm,adversary_prob,mean_regret,ci_regret,worst_case_regret");

        for (double prob : adversaryProbs) {
            List<BanditAlgorithm> algorithms = createAllAlgorithms();

            for (BanditAlgorithm algo : algorithms) {
                double[] regrets = new double[NUM_SEEDS];
                double worstCase = Double.NEGATIVE_INFINITY;

                for (int s = 0; s < NUM_SEEDS; s++) {
                    long seed = 42 + s;
                    algo.reset();
                    BenchmarkEnvironment env = BenchmarkEnvironment.adversarial(NUM_ACTIONS, prob, seed);
                    BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);
                    regrets[s] = metrics.getCumulativeRegret();
                    worstCase = Math.max(worstCase, regrets[s]);
                }

                double mean = Arrays.stream(regrets).average().orElse(0);
                double ci = BenchmarkMetrics.bootstrapCI(regrets, 1000);

                rows.add(String.format("%s,%.2f,%.4f,%.4f,%.4f",
                        algo.name(), prob, mean, ci, worstCase));

                System.out.printf("[ADV prob=%.1f] %25s: regret=%.1f +/- %.1f (worst=%.1f)%n",
                        prob, algo.name(), mean, ci, worstCase);
            }
        }

        Path outFile = outputDir.resolve("adversarial_regret.csv");
        Files.write(outFile, rows);
        assertTrue(Files.size(outFile) > 0);
    }

    @Test
    void statisticalComparison(@TempDir Path outputDir) throws IOException {
        // Pairwise statistical tests between all algorithms
        int horizon = 2000;
        List<BanditAlgorithm> algorithms = createAllAlgorithms();
        Map<String, double[]> regretsBySeed = new LinkedHashMap<>();

        for (BanditAlgorithm algo : algorithms) {
            double[] regrets = new double[NUM_SEEDS];
            for (int s = 0; s < NUM_SEEDS; s++) {
                long seed = 42 + s;
                algo.reset();
                BenchmarkEnvironment env = BenchmarkEnvironment.stationaryContextual(
                        STATE_DIM, NUM_ACTIONS, 0.1, seed);
                BenchmarkMetrics metrics = runEpisode(algo, env, seed, horizon);
                regrets[s] = metrics.getCumulativeRegret();
            }
            regretsBySeed.put(algo.name(), regrets);
        }

        // Pairwise Wilcoxon signed-rank tests
        List<String> rows = new ArrayList<>();
        rows.add("algorithm_a,algorithm_b,mean_a,mean_b,p_value,cohens_d,significant");

        List<String> names = new ArrayList<>(regretsBySeed.keySet());
        int numComparisons = names.size() * (names.size() - 1) / 2;
        double bonferroni = 0.05 / numComparisons;

        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                double[] a = regretsBySeed.get(names.get(i));
                double[] b = regretsBySeed.get(names.get(j));
                double pValue = BenchmarkMetrics.wilcoxonSignedRank(a, b);
                double d = BenchmarkMetrics.cohensD(a, b);
                boolean significant = pValue < bonferroni;

                rows.add(String.format("%s,%s,%.4f,%.4f,%.6f,%.4f,%b",
                        names.get(i), names.get(j),
                        Arrays.stream(a).average().orElse(0),
                        Arrays.stream(b).average().orElse(0),
                        pValue, d, significant));
            }
        }

        Path outFile = outputDir.resolve("statistical_tests.csv");
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

    private List<BanditAlgorithm> createAllAlgorithms() {
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
}
