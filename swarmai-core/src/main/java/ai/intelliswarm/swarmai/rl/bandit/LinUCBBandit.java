package ai.intelliswarm.swarmai.rl.bandit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Linear Upper Confidence Bound (LinUCB) contextual bandit.
 *
 * <p>Maintains per-action weight matrices to learn which actions work best
 * for which state vectors. Used for skill generation decisions (4 actions, 8-dim state).
 *
 * <p>Algorithm:
 * <ul>
 *   <li>Per action a: matrix A_a (d x d) and vector b_a (d)</li>
 *   <li>Decision: argmax_a(x^T * theta_a + alpha * sqrt(x^T * A_a^{-1} * x))</li>
 *   <li>Update: A_a += x * x^T, b_a += reward * x</li>
 * </ul>
 *
 * <p>Thread-safe: synchronized on updates, decisions read consistent snapshots.
 */
public class LinUCBBandit {

    private static final Logger logger = LoggerFactory.getLogger(LinUCBBandit.class);

    private final int numActions;
    private final int stateDim;
    private final double alpha;

    // Per-action: A matrices (stateDim x stateDim) and b vectors (stateDim)
    private final double[][][] aMatrices;  // [action][i][j]
    private final double[][] bVectors;     // [action][i]
    private final int[] actionCounts;

    /**
     * Creates a LinUCB bandit.
     *
     * @param numActions number of discrete actions
     * @param stateDim   dimensionality of the state/context vector
     * @param alpha      exploration parameter (higher = more exploration, default 1.0)
     */
    public LinUCBBandit(int numActions, int stateDim, double alpha) {
        this.numActions = numActions;
        this.stateDim = stateDim;
        this.alpha = alpha;
        this.aMatrices = new double[numActions][stateDim][stateDim];
        this.bVectors = new double[numActions][stateDim];
        this.actionCounts = new int[numActions];

        // Initialize A matrices to identity (regularization)
        for (int a = 0; a < numActions; a++) {
            for (int i = 0; i < stateDim; i++) {
                aMatrices[a][i][i] = 1.0;
            }
        }
    }

    public LinUCBBandit(int numActions, int stateDim) {
        this(numActions, stateDim, 1.0);
    }

    /**
     * Selects the action with the highest upper confidence bound for the given state.
     *
     * @param state the context/feature vector (length == stateDim)
     * @return the selected action index
     */
    public int selectAction(double[] state) {
        if (state.length != stateDim) {
            throw new IllegalArgumentException("State dimension mismatch: expected " + stateDim + ", got " + state.length);
        }

        int bestAction = 0;
        double bestUCB = Double.NEGATIVE_INFINITY;

        for (int a = 0; a < numActions; a++) {
            double ucb = computeUCB(a, state);
            if (ucb > bestUCB) {
                bestUCB = ucb;
                bestAction = a;
            }
        }

        return bestAction;
    }

    /**
     * Returns the UCB scores for all actions (useful for confidence reporting).
     */
    public double[] getUCBScores(double[] state) {
        double[] scores = new double[numActions];
        for (int a = 0; a < numActions; a++) {
            scores[a] = computeUCB(a, state);
        }
        return scores;
    }

    /**
     * Updates the model after observing a reward for the given (state, action) pair.
     *
     * @param state  the context vector at decision time
     * @param action the action that was taken
     * @param reward the observed reward
     */
    public synchronized void update(double[] state, int action, double reward) {
        if (action < 0 || action >= numActions) return;

        // A_a += x * x^T
        for (int i = 0; i < stateDim; i++) {
            for (int j = 0; j < stateDim; j++) {
                aMatrices[action][i][j] += state[i] * state[j];
            }
        }

        // b_a += reward * x
        for (int i = 0; i < stateDim; i++) {
            bVectors[action][i] += reward * state[i];
        }

        actionCounts[action]++;
    }

    private double computeUCB(int action, double[] state) {
        // theta_a = A_a^{-1} * b_a
        double[][] aInv = invert(aMatrices[action]);
        if (aInv == null) {
            return 0.0; // fallback on numerical issues
        }

        double[] theta = matVecMul(aInv, bVectors[action]);

        // exploitation term: x^T * theta
        double exploitation = dotProduct(state, theta);

        // exploration term: alpha * sqrt(x^T * A^{-1} * x)
        double[] aInvX = matVecMul(aInv, state);
        double exploration = alpha * Math.sqrt(Math.max(0, dotProduct(state, aInvX)));

        return exploitation + exploration;
    }

    /**
     * Returns the total number of updates across all actions.
     */
    public int getTotalUpdates() {
        int total = 0;
        for (int c : actionCounts) total += c;
        return total;
    }

    /**
     * Returns the update count for a specific action.
     */
    public int getActionCount(int action) {
        return actionCounts[action];
    }

    // ========================================
    // Linear Algebra Utilities
    // ========================================

    /**
     * Inverts a symmetric positive-definite matrix using Cholesky decomposition.
     * Returns null if the matrix is singular or not positive-definite.
     */
    static double[][] invert(double[][] matrix) {
        int n = matrix.length;
        double[][] L = choleskyDecompose(matrix);
        if (L == null) return null;

        // Invert L (lower triangular)
        double[][] Linv = new double[n][n];
        for (int i = 0; i < n; i++) {
            Linv[i][i] = 1.0 / L[i][i];
            for (int j = i + 1; j < n; j++) {
                double sum = 0;
                for (int k = i; k < j; k++) {
                    sum += L[j][k] * Linv[k][i];
                }
                Linv[j][i] = -sum / L[j][j];
            }
        }

        // A^{-1} = L^{-T} * L^{-1}
        double[][] result = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (int k = i; k < n; k++) {
                    sum += Linv[k][i] * Linv[k][j];
                }
                result[i][j] = sum;
                result[j][i] = sum;
            }
        }
        return result;
    }

    /**
     * Cholesky decomposition: A = L * L^T.
     * Returns L (lower triangular) or null if A is not positive-definite.
     */
    static double[][] choleskyDecompose(double[][] matrix) {
        int n = matrix.length;
        double[][] L = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0;
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }
                if (i == j) {
                    double val = matrix[i][i] - sum;
                    if (val <= 0) return null; // not positive-definite
                    L[i][j] = Math.sqrt(val);
                } else {
                    L[i][j] = (matrix[i][j] - sum) / L[j][j];
                }
            }
        }
        return L;
    }

    static double[] matVecMul(double[][] matrix, double[] vec) {
        int n = matrix.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result[i] += matrix[i][j] * vec[j];
            }
        }
        return result;
    }

    static double dotProduct(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
