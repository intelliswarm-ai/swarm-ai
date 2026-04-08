package ai.intelliswarm.swarmai.rl.benchmark;

/**
 * Common interface for all bandit algorithms under benchmark.
 * Wraps both our production algorithms and alternative baselines.
 */
public interface BanditAlgorithm {

    /** Human-readable name for reports. */
    String name();

    /** Select an action given the state vector. */
    int selectAction(double[] state);

    /** Update the algorithm after observing a reward. */
    void update(double[] state, int action, double reward);

    /** Reset internal state for a new run. */
    void reset();
}
