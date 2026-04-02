package ai.intelliswarm.swarmai.rl.bandit;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BayesianWeightOptimizerTest {

    @Test
    void initialWeightsSumToOne() {
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 10, 0.1);
        double[] weights = opt.getBestWeights();
        assertEquals(3, weights.length);
        double sum = weights[0] + weights[1] + weights[2];
        assertEquals(1.0, sum, 0.001);
    }

    @Test
    void defaultWeightsMatchHeuristic() {
        // First candidate is seeded with [0.5, 0.3, 0.2]
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 10, 0.1);
        double[] weights = opt.getBestWeights();
        assertEquals(0.5, weights[0], 0.001);
        assertEquals(0.3, weights[1], 0.001);
        assertEquals(0.2, weights[2], 0.001);
    }

    @Test
    void getNextWeightsReturnsValidVector() {
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 10, 0.1);
        double[] weights = opt.getNextWeights();
        assertEquals(3, weights.length);
        double sum = weights[0] + weights[1] + weights[2];
        assertEquals(1.0, sum, 0.01);
        for (double w : weights) {
            assertTrue(w > 0, "Weights should be positive");
        }
    }

    @Test
    void recordFitnessUpdatesBest() {
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 10, 0.1, new Random(42));

        // Record high fitness for a specific weight vector
        double[] w = opt.getNextWeights();
        opt.recordFitness(w, 0.95);

        assertTrue(opt.getBestFitness() >= 0.95);
        assertEquals(1, opt.getEvaluatedCount());
    }

    @Test
    void evolveKeepsBestAndMutates() {
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 10, 0.1, new Random(42));

        // Record fitness for several candidates
        for (int i = 0; i < 5; i++) {
            double[] w = opt.getNextWeights();
            opt.recordFitness(w, 0.5 + i * 0.1);
        }

        double bestBefore = opt.getBestFitness();
        opt.evolve();

        // Best fitness should be preserved
        assertTrue(opt.getBestFitness() >= bestBefore);

        // Weights should still be valid
        double[] weights = opt.getBestWeights();
        double sum = weights[0] + weights[1] + weights[2];
        assertEquals(1.0, sum, 0.01);
    }

    @Test
    void softmaxNormalizationSumsToOne() {
        double[] raw = {3.0, 1.0, 1.0};
        double[] normalized = BayesianWeightOptimizer.softmaxNormalize(raw);
        double sum = normalized[0] + normalized[1] + normalized[2];
        assertEquals(1.0, sum, 0.001);
        assertEquals(0.6, normalized[0], 0.001);
        assertEquals(0.2, normalized[1], 0.001);
        assertEquals(0.2, normalized[2], 0.001);
    }

    @Test
    void softmaxHandlesZeros() {
        double[] raw = {0.0, 0.0, 0.0};
        double[] normalized = BayesianWeightOptimizer.softmaxNormalize(raw);
        assertEquals(1.0 / 3, normalized[0], 0.001);
        assertEquals(1.0 / 3, normalized[1], 0.001);
        assertEquals(1.0 / 3, normalized[2], 0.001);
    }

    @Test
    void convergesOnKnownOptimal() {
        // Simulate: fitness function peaks at [0.7, 0.2, 0.1]
        BayesianWeightOptimizer opt = new BayesianWeightOptimizer(3, 20, 0.05, new Random(42));

        for (int gen = 0; gen < 30; gen++) {
            for (int i = 0; i < 20; i++) {
                double[] w = opt.getNextWeights();
                // Fitness is higher when w[0] is close to 0.7
                double fitness = 1.0 - Math.abs(w[0] - 0.7) - Math.abs(w[1] - 0.2) - Math.abs(w[2] - 0.1);
                opt.recordFitness(w, fitness);
            }
            opt.evolve();
        }

        double[] best = opt.getBestWeights();
        assertTrue(best[0] > 0.5, "Should converge toward w[0] ~ 0.7, got " + best[0]);
        assertTrue(opt.getBestFitness() > 0.5, "Fitness should improve, got " + opt.getBestFitness());
    }
}
