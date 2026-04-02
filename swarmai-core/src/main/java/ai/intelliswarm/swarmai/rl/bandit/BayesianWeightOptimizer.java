package ai.intelliswarm.swarmai.rl.bandit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Evolutionary strategy for optimizing weight vectors.
 * Used for learning optimal skill selection weights [relevance, effectiveness, quality].
 *
 * <p>Maintains a population of weight vectors. Each generation:
 * <ol>
 *   <li>Evaluate fitness of each vector based on tracked outcomes</li>
 *   <li>Keep the top half</li>
 *   <li>Mutate survivors with Gaussian noise</li>
 *   <li>Softmax-normalize to ensure weights sum to 1</li>
 * </ol>
 *
 * <p>Converges in ~20-50 workflow runs for 3-dimensional weight vectors.
 */
public class BayesianWeightOptimizer {

    private final int dimensions;
    private final int populationSize;
    private final double mutationSigma;
    private final Random random;

    private final List<WeightCandidate> population;
    private double[] bestWeights;
    private double bestFitness = Double.NEGATIVE_INFINITY;

    /**
     * Creates a weight optimizer.
     *
     * @param dimensions     number of weight dimensions (e.g., 3 for [relevance, effectiveness, quality])
     * @param populationSize number of candidate weight vectors to maintain
     * @param mutationSigma  standard deviation of Gaussian mutation noise
     */
    public BayesianWeightOptimizer(int dimensions, int populationSize, double mutationSigma) {
        this(dimensions, populationSize, mutationSigma, new Random());
    }

    public BayesianWeightOptimizer(int dimensions, int populationSize, double mutationSigma, Random random) {
        this.dimensions = dimensions;
        this.populationSize = populationSize;
        this.mutationSigma = mutationSigma;
        this.random = random;
        this.population = new ArrayList<>();

        // Initialize population with random softmax-normalized vectors
        for (int i = 0; i < populationSize; i++) {
            double[] raw = new double[dimensions];
            for (int d = 0; d < dimensions; d++) {
                raw[d] = random.nextDouble() + 0.1; // avoid zeros
            }
            population.add(new WeightCandidate(softmaxNormalize(raw)));
        }

        // Seed first candidate with the heuristic default
        if (dimensions == 3) {
            population.set(0, new WeightCandidate(new double[]{0.5, 0.3, 0.2}));
        }

        bestWeights = population.get(0).weights.clone();
    }

    /**
     * Returns the current best weight vector.
     */
    public double[] getBestWeights() {
        return bestWeights.clone();
    }

    /**
     * Returns a weight vector from the population for the next evaluation.
     * Cycles through the population round-robin.
     */
    public synchronized double[] getNextWeights() {
        // Find the candidate with the fewest evaluations
        WeightCandidate leastEvaluated = population.stream()
                .min(Comparator.comparingInt(c -> c.evaluations))
                .orElse(population.get(0));
        return leastEvaluated.weights.clone();
    }

    /**
     * Records the fitness of a weight vector after evaluation.
     *
     * @param weights the weight vector that was used
     * @param fitness the observed fitness (e.g., task success rate)
     */
    public synchronized void recordFitness(double[] weights, double fitness) {
        // Find the matching candidate
        for (WeightCandidate candidate : population) {
            if (weightsMatch(candidate.weights, weights)) {
                candidate.totalFitness += fitness;
                candidate.evaluations++;
                candidate.avgFitness = candidate.totalFitness / candidate.evaluations;

                if (candidate.avgFitness > bestFitness) {
                    bestFitness = candidate.avgFitness;
                    bestWeights = candidate.weights.clone();
                }
                return;
            }
        }
    }

    /**
     * Evolves the population: keep top half, mutate to fill the rest.
     * Call this periodically (e.g., every 10 workflow runs).
     */
    public synchronized void evolve() {
        // Sort by average fitness (descending)
        population.sort(Comparator.comparingDouble((WeightCandidate c) -> c.avgFitness).reversed());

        int survivors = populationSize / 2;

        // Replace bottom half with mutations of top half
        for (int i = survivors; i < populationSize; i++) {
            WeightCandidate parent = population.get(i % survivors);
            double[] child = mutate(parent.weights);
            population.set(i, new WeightCandidate(child));
        }
    }

    /**
     * Returns the current population size with evaluation counts.
     */
    public int getEvaluatedCount() {
        return (int) population.stream().filter(c -> c.evaluations > 0).count();
    }

    /**
     * Returns the best fitness observed so far.
     */
    public double getBestFitness() {
        return bestFitness;
    }

    private double[] mutate(double[] weights) {
        double[] mutated = new double[dimensions];
        for (int d = 0; d < dimensions; d++) {
            mutated[d] = weights[d] + random.nextGaussian() * mutationSigma;
            mutated[d] = Math.max(0.01, mutated[d]); // keep positive
        }
        return softmaxNormalize(mutated);
    }

    static double[] softmaxNormalize(double[] raw) {
        double sum = 0;
        for (double v : raw) sum += v;
        if (sum == 0) {
            double[] uniform = new double[raw.length];
            java.util.Arrays.fill(uniform, 1.0 / raw.length);
            return uniform;
        }
        double[] normalized = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            normalized[i] = raw[i] / sum;
        }
        return normalized;
    }

    private boolean weightsMatch(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-6) return false;
        }
        return true;
    }

    private static class WeightCandidate {
        final double[] weights;
        double totalFitness = 0;
        int evaluations = 0;
        double avgFitness = 0;

        WeightCandidate(double[] weights) {
            this.weights = weights;
        }
    }
}
