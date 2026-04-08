package ai.intelliswarm.swarmai.rl.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Metrics collection and statistical analysis for RL benchmarks.
 * Tracks per-timestep rewards, regret, and computes summary statistics.
 */
public class BenchmarkMetrics {

    private final String algorithmName;
    private final String environmentName;
    private final long seed;

    private final List<StepRecord> steps = new ArrayList<>();
    private double cumulativeRegret = 0;
    private int optimalActions = 0;
    private long totalDecisionNanos = 0;
    private long totalUpdateNanos = 0;
    private int decisionCount = 0;

    public BenchmarkMetrics(String algorithmName, String environmentName, long seed) {
        this.algorithmName = algorithmName;
        this.environmentName = environmentName;
        this.seed = seed;
    }

    public void recordStep(int timestep, int action, double reward,
                           int optimalAction, double optimalReward) {
        double instantRegret = optimalReward - reward;
        cumulativeRegret += Math.max(0, instantRegret);
        if (action == optimalAction) optimalActions++;

        steps.add(new StepRecord(timestep, action, reward, optimalAction,
                optimalReward, cumulativeRegret,
                optimalActions / (double) (timestep)));
    }

    public void recordDecisionLatency(long nanos) {
        totalDecisionNanos += nanos;
        decisionCount++;
    }

    public void recordUpdateLatency(long nanos) {
        totalUpdateNanos += nanos;
    }

    // ======================================================================
    // Summary statistics
    // ======================================================================

    public double getCumulativeRegret() { return cumulativeRegret; }

    public double getSimpleRegret() {
        if (steps.isEmpty()) return Double.NaN;
        // Average regret over last 100 steps
        int window = Math.min(100, steps.size());
        double sum = 0;
        for (int i = steps.size() - window; i < steps.size(); i++) {
            sum += (steps.get(i).optimalReward - steps.get(i).reward);
        }
        return sum / window;
    }

    public double getOptimalActionRate() {
        return steps.isEmpty() ? 0 : optimalActions / (double) steps.size();
    }

    public double getTotalReward() {
        return steps.stream().mapToDouble(s -> s.reward).sum();
    }

    /**
     * First timestep where the algorithm selects the optimal action
     * for at least 95% of the next windowSize steps.
     * Returns -1 if never converged.
     */
    public int getConvergenceStep(int windowSize) {
        if (steps.size() < windowSize) return -1;

        for (int i = 0; i <= steps.size() - windowSize; i++) {
            int correct = 0;
            for (int j = i; j < i + windowSize; j++) {
                if (steps.get(j).action == steps.get(j).optimalAction) correct++;
            }
            if (correct >= windowSize * 0.95) return steps.get(i).timestep;
        }
        return -1;
    }

    public double getAvgDecisionLatencyMicros() {
        return decisionCount == 0 ? 0 : (totalDecisionNanos / (double) decisionCount) / 1000.0;
    }

    public double getAvgUpdateLatencyMicros() {
        return decisionCount == 0 ? 0 : (totalUpdateNanos / (double) decisionCount) / 1000.0;
    }

    public double getThroughputPerSecond() {
        long totalNanos = totalDecisionNanos + totalUpdateNanos;
        return totalNanos == 0 ? 0 : decisionCount / (totalNanos / 1_000_000_000.0);
    }

    /**
     * Adaptation delay: after a regime change at step changeStep,
     * how many steps until optimal action rate recovers to 90% in a window of 50.
     */
    public int getAdaptationDelay(int changeStep) {
        int window = 50;
        for (int i = changeStep; i <= steps.size() - window; i++) {
            int correct = 0;
            for (int j = i; j < i + window; j++) {
                if (steps.get(j).action == steps.get(j).optimalAction) correct++;
            }
            if (correct >= window * 0.90) return i - changeStep;
        }
        return -1; // never recovered
    }

    // ======================================================================
    // CSV generation
    // ======================================================================

    public static String csvHeader() {
        return "algorithm,environment,seed,timestep,action,reward,optimal_action," +
                "optimal_reward,cumulative_regret,optimal_action_rate";
    }

    public List<String> toCsvRows() {
        return steps.stream()
                .map(s -> String.format("%s,%s,%d,%d,%d,%.6f,%d,%.6f,%.6f,%.4f",
                        algorithmName, environmentName, seed,
                        s.timestep, s.action, s.reward, s.optimalAction,
                        s.optimalReward, s.cumulativeRegret, s.optimalActionRate))
                .collect(Collectors.toList());
    }

    public String toSummaryRow() {
        return String.format("%s,%s,%d,%.4f,%.4f,%.4f,%.4f,%d,%.2f,%.2f,%.0f",
                algorithmName, environmentName, seed,
                cumulativeRegret, getSimpleRegret(), getTotalReward(),
                getOptimalActionRate(), getConvergenceStep(100),
                getAvgDecisionLatencyMicros(), getAvgUpdateLatencyMicros(),
                getThroughputPerSecond());
    }

    public static String summaryHeader() {
        return "algorithm,environment,seed,cumulative_regret,simple_regret,total_reward," +
                "optimal_action_rate,convergence_step,avg_decision_us,avg_update_us,throughput_per_sec";
    }

    public String getAlgorithmName() { return algorithmName; }
    public String getEnvironmentName() { return environmentName; }
    public long getSeed() { return seed; }
    public List<StepRecord> getSteps() { return steps; }

    // ======================================================================
    // Statistical utilities (for cross-run analysis)
    // ======================================================================

    /** Computes 95% confidence interval half-width using bootstrap. */
    public static double bootstrapCI(double[] values, int numBootstrap) {
        if (values.length <= 1) return 0;
        java.util.Random rng = new java.util.Random(42);
        double[] bootstrapMeans = new double[numBootstrap];

        for (int b = 0; b < numBootstrap; b++) {
            double sum = 0;
            for (int i = 0; i < values.length; i++) {
                sum += values[rng.nextInt(values.length)];
            }
            bootstrapMeans[b] = sum / values.length;
        }

        Arrays.sort(bootstrapMeans);
        double lower = bootstrapMeans[(int) (0.025 * numBootstrap)];
        double upper = bootstrapMeans[(int) (0.975 * numBootstrap)];
        return (upper - lower) / 2.0;
    }

    /** Wilcoxon signed-rank test approximate p-value for paired samples. */
    public static double wilcoxonSignedRank(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        if (n < 5) return 1.0; // too few samples

        double[] diffs = new double[n];
        double[] absDiffs = new double[n];
        int nonZero = 0;

        for (int i = 0; i < n; i++) {
            diffs[i] = a[i] - b[i];
            if (Math.abs(diffs[i]) > 1e-10) {
                absDiffs[nonZero++] = Math.abs(diffs[i]);
            }
        }

        if (nonZero == 0) return 1.0;

        // Rank the absolute differences
        Integer[] indices = new Integer[nonZero];
        for (int i = 0; i < nonZero; i++) indices[i] = i;
        Arrays.sort(indices, (x, y) -> Double.compare(absDiffs[x], absDiffs[y]));

        double[] ranks = new double[nonZero];
        for (int i = 0; i < nonZero; i++) ranks[indices[i]] = i + 1.0;

        // Compute W+ (sum of ranks where diff > 0)
        double wPlus = 0;
        int idx = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(diffs[i]) > 1e-10) {
                if (diffs[i] > 0) wPlus += ranks[idx];
                idx++;
            }
        }

        // Normal approximation for large n
        double meanW = nonZero * (nonZero + 1.0) / 4.0;
        double stdW = Math.sqrt(nonZero * (nonZero + 1.0) * (2.0 * nonZero + 1.0) / 24.0);
        double z = (wPlus - meanW) / stdW;

        // Two-tailed p-value via standard normal approximation
        return 2.0 * (1.0 - normalCDF(Math.abs(z)));
    }

    /** Cohen's d effect size for paired samples. */
    public static double cohensD(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double[] diffs = new double[n];
        double meanDiff = 0;
        for (int i = 0; i < n; i++) {
            diffs[i] = a[i] - b[i];
            meanDiff += diffs[i];
        }
        meanDiff /= n;

        double var = 0;
        for (int i = 0; i < n; i++) {
            var += (diffs[i] - meanDiff) * (diffs[i] - meanDiff);
        }
        var /= (n - 1);

        return var == 0 ? 0 : meanDiff / Math.sqrt(var);
    }

    private static double normalCDF(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        // Horner form approximation (Abramowitz & Stegun 7.1.26)
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741
                + t * (-1.453152027 + t * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? result : -result;
    }

    public record StepRecord(
            int timestep, int action, double reward,
            int optimalAction, double optimalReward,
            double cumulativeRegret, double optimalActionRate
    ) {}
}
