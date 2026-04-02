package ai.intelliswarm.swarmai.rl.bandit;

import java.util.Random;

/**
 * Thompson Sampling for binary decisions (CONTINUE vs STOP).
 *
 * <p>Each action maintains a Beta(alpha, beta) distribution.
 * Decisions sample from each distribution and pick the highest.
 * Updates: success → alpha += 1, failure → beta += 1.
 *
 * <p>The Beta distribution naturally balances exploration (uncertain actions
 * get wide distributions) and exploitation (successful actions get peaked distributions).
 */
public class ThompsonSampling {

    private final int numActions;
    private final double[] alphas;
    private final double[] betas;
    private final Random random;

    /**
     * Creates a Thompson Sampling bandit.
     *
     * @param numActions number of actions (typically 2: CONTINUE, STOP)
     */
    public ThompsonSampling(int numActions) {
        this(numActions, new Random());
    }

    public ThompsonSampling(int numActions, Random random) {
        this.numActions = numActions;
        this.alphas = new double[numActions];
        this.betas = new double[numActions];
        this.random = random;

        // Uniform prior: Beta(1, 1)
        for (int a = 0; a < numActions; a++) {
            alphas[a] = 1.0;
            betas[a] = 1.0;
        }
    }

    /**
     * Selects an action by sampling from each Beta distribution.
     *
     * @return the action index with the highest sampled value
     */
    public int selectAction() {
        int bestAction = 0;
        double bestSample = Double.NEGATIVE_INFINITY;

        for (int a = 0; a < numActions; a++) {
            double sample = sampleBeta(alphas[a], betas[a]);
            if (sample > bestSample) {
                bestSample = sample;
                bestAction = a;
            }
        }

        return bestAction;
    }

    /**
     * Updates the distribution after observing a reward.
     *
     * @param action  the action that was taken
     * @param success true if the outcome was positive (reward > 0)
     */
    public synchronized void update(int action, boolean success) {
        if (action < 0 || action >= numActions) return;

        if (success) {
            alphas[action] += 1.0;
        } else {
            betas[action] += 1.0;
        }
    }

    /**
     * Updates with a continuous reward (0.0-1.0).
     * Treats reward as a probability of success.
     */
    public synchronized void update(int action, double reward) {
        if (action < 0 || action >= numActions) return;

        // Proportional update: reward contributes to alpha, (1-reward) to beta
        alphas[action] += Math.max(0, Math.min(1, reward));
        betas[action] += Math.max(0, Math.min(1, 1.0 - reward));
    }

    /**
     * Returns the estimated success probability for an action.
     */
    public double getMean(int action) {
        return alphas[action] / (alphas[action] + betas[action]);
    }

    /**
     * Returns the total number of updates for an action.
     */
    public double getCount(int action) {
        return (alphas[action] - 1) + (betas[action] - 1); // subtract prior
    }

    /**
     * Returns alpha and beta parameters for an action.
     */
    public double[] getParameters(int action) {
        return new double[]{alphas[action], betas[action]};
    }

    /**
     * Samples from a Beta(alpha, beta) distribution using the gamma distribution trick.
     * X ~ Gamma(alpha, 1), Y ~ Gamma(beta, 1), then X/(X+Y) ~ Beta(alpha, beta).
     */
    double sampleBeta(double alpha, double beta) {
        double x = sampleGamma(alpha);
        double y = sampleGamma(beta);
        if (x + y == 0) return 0.5; // edge case
        return x / (x + y);
    }

    /**
     * Samples from a Gamma(shape, 1) distribution using Marsaglia and Tsang's method.
     */
    private double sampleGamma(double shape) {
        if (shape < 1.0) {
            // For shape < 1: use Gamma(shape+1) * U^(1/shape)
            double u = random.nextDouble();
            return sampleGamma(shape + 1.0) * Math.pow(u, 1.0 / shape);
        }

        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double x, v;
            do {
                x = random.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);

            v = v * v * v;
            double u = random.nextDouble();

            if (u < 1.0 - 0.0331 * (x * x) * (x * x)) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v;
        }
    }
}
