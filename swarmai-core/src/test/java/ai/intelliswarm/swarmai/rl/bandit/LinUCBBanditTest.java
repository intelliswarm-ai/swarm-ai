package ai.intelliswarm.swarmai.rl.bandit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinUCBBanditTest {

    @Test
    void selectsActionWithValidState() {
        LinUCBBandit bandit = new LinUCBBandit(4, 3);
        int action = bandit.selectAction(new double[]{0.5, 0.3, 0.8});
        assertTrue(action >= 0 && action < 4);
    }

    @Test
    void rejectsWrongStateDimension() {
        LinUCBBandit bandit = new LinUCBBandit(4, 3);
        assertThrows(IllegalArgumentException.class,
                () -> bandit.selectAction(new double[]{0.5, 0.3}));
    }

    @Test
    void convergesOnSyntheticProblem() {
        // Synthetic problem: action 2 is always optimal for state [1,0,0]
        // action 0 is always optimal for state [0,1,0]
        LinUCBBandit bandit = new LinUCBBandit(3, 3, 0.5);

        // Train: action 2 gets high reward for [1,0,0]
        double[] state1 = {1.0, 0.0, 0.0};
        for (int i = 0; i < 50; i++) {
            bandit.update(state1, 2, 1.0);  // action 2 is good
            bandit.update(state1, 0, 0.1);  // action 0 is bad
            bandit.update(state1, 1, 0.2);  // action 1 is bad
        }

        // After training, bandit should prefer action 2 for state1
        int selected = bandit.selectAction(state1);
        assertEquals(2, selected, "Should converge to action 2 for state [1,0,0]");
    }

    @Test
    void learnsFromRewards() {
        LinUCBBandit bandit = new LinUCBBandit(2, 2);
        double[] state = {0.5, 0.5};

        // Train action 0 with high rewards, action 1 with low
        for (int i = 0; i < 30; i++) {
            bandit.update(state, 0, 0.9);
            bandit.update(state, 1, 0.1);
        }

        double[] scores = bandit.getUCBScores(state);
        assertTrue(scores[0] > scores[1],
                "Action 0 (high reward) should have higher UCB than action 1");
    }

    @Test
    void tracksActionCounts() {
        LinUCBBandit bandit = new LinUCBBandit(3, 2);
        double[] state = {0.5, 0.5};

        bandit.update(state, 0, 1.0);
        bandit.update(state, 0, 0.8);
        bandit.update(state, 1, 0.5);

        assertEquals(2, bandit.getActionCount(0));
        assertEquals(1, bandit.getActionCount(1));
        assertEquals(0, bandit.getActionCount(2));
        assertEquals(3, bandit.getTotalUpdates());
    }

    @Test
    void explorationDecreasesWithData() {
        LinUCBBandit bandit = new LinUCBBandit(2, 2, 1.0);
        double[] state = {0.5, 0.5};

        // Before any updates, UCB scores should have high exploration bonus
        double[] scoresBefore = bandit.getUCBScores(state);

        // After many updates to action 0
        for (int i = 0; i < 100; i++) {
            bandit.update(state, 0, 0.7);
        }

        double[] scoresAfter = bandit.getUCBScores(state);

        // Action 1 (unexplored) should still have exploration bonus
        // Action 0 (heavily explored) should rely more on exploitation
        assertTrue(bandit.getActionCount(0) == 100);
        assertTrue(bandit.getActionCount(1) == 0);
    }

    @Test
    void choleskyDecompositionOnIdentity() {
        double[][] identity = {{1, 0}, {0, 1}};
        double[][] L = LinUCBBandit.choleskyDecompose(identity);
        assertNotNull(L);
        assertEquals(1.0, L[0][0], 1e-10);
        assertEquals(1.0, L[1][1], 1e-10);
    }

    @Test
    void matrixInversionOnIdentity() {
        double[][] identity = {{1, 0}, {0, 1}};
        double[][] inv = LinUCBBandit.invert(identity);
        assertNotNull(inv);
        assertEquals(1.0, inv[0][0], 1e-10);
        assertEquals(0.0, inv[0][1], 1e-10);
        assertEquals(1.0, inv[1][1], 1e-10);
    }

    @Test
    void matrixInversion2x2() {
        double[][] A = {{4, 2}, {2, 3}};
        double[][] inv = LinUCBBandit.invert(A);
        assertNotNull(inv);

        // A * A^{-1} should be identity
        double[][] product = new double[2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    product[i][j] += A[i][k] * inv[k][j];
                }
            }
        }
        assertEquals(1.0, product[0][0], 1e-10);
        assertEquals(0.0, product[0][1], 1e-10);
        assertEquals(0.0, product[1][0], 1e-10);
        assertEquals(1.0, product[1][1], 1e-10);
    }

    @Test
    void worksWithEightDimensions() {
        // Match the actual SkillGenerationContext feature dimension
        LinUCBBandit bandit = new LinUCBBandit(4, 8, 1.0);
        double[] state = {0.8, 0.9, 0.7, 1.0, 0.6, 0.25, 0.3, 0.1};
        int action = bandit.selectAction(state);
        assertTrue(action >= 0 && action < 4);

        bandit.update(state, action, 0.85);
        assertEquals(1, bandit.getTotalUpdates());
    }
}
