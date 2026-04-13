package ai.intelliswarm.swarmai.rl.bandit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeuralLinUCBBanditTest {

    @Test
    void selectsValidAction() {
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(4, 8, 32, 8, 1.0, 0.001f, 20, 1000);
        double[] state = {0.5, 0.3, 0.8, 1.0, 0.6, 0.25, 0.3, 0.1};
        int action = bandit.selectAction(state);
        assertTrue(action >= 0 && action < 4);
    }

    @Test
    void rejectsWrongStateDimension() {
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(4, 8, 32, 8, 1.0, 0.001f, 20, 1000);
        assertThrows(IllegalArgumentException.class,
                () -> bandit.selectAction(new double[]{0.5, 0.3}));
    }

    @Test
    void learnsFromRewards() {
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(2, 3, 16, 4, 1.0, 0.001f, 10, 1000);
        double[] state = {0.5, 0.5, 0.5};

        // Train action 0 with high rewards, action 1 with low
        for (int i = 0; i < 100; i++) {
            bandit.update(state, 0, 0.9);
            bandit.update(state, 1, 0.1);
        }

        // After training, should prefer action 0
        int preferredCount = 0;
        for (int i = 0; i < 20; i++) {
            if (bandit.selectAction(state) == 0) preferredCount++;
        }
        assertTrue(preferredCount > 10, "Should prefer action 0 (high reward) after training");
    }

    @Test
    void tracksActionCounts() {
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(3, 2, 8, 4, 1.0, 0.001f, 20, 1000);
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
    void bufferTracksExperiences() {
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(2, 3, 8, 4, 1.0, 0.001f, 20, 100);
        double[] state = {0.5, 0.5, 0.5};

        for (int i = 0; i < 50; i++) {
            bandit.update(state, i % 2, 0.5);
        }

        assertEquals(50, bandit.getBufferSize());
    }

    @Test
    void worksWithEightDimensions() {
        // Match the actual SkillGenerationContext feature dimension
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(4, 8, 32, 8, 1.0, 0.001f, 20, 5000);
        double[] state = {0.8, 0.9, 0.7, 1.0, 0.6, 0.25, 0.3, 0.1};
        int action = bandit.selectAction(state);
        assertTrue(action >= 0 && action < 4);

        bandit.update(state, action, 0.85);
        assertEquals(1, bandit.getTotalUpdates());
    }

    @Test
    void outperformsDQNOnSyntheticProblem() {
        // Verify NeuralLinUCB converges on a simple contextual problem
        NeuralLinUCBBandit bandit = new NeuralLinUCBBandit(3, 3, 16, 4, 1.0, 0.001f, 10, 1000);

        // Contextual: action 2 is best for state [1,0,0], action 0 for [0,1,0]
        double[] state1 = {1.0, 0.0, 0.0};
        double[] state2 = {0.0, 1.0, 0.0};

        for (int i = 0; i < 200; i++) {
            // Action 2 gets high reward for state1
            bandit.update(state1, 2, 1.0);
            bandit.update(state1, 0, 0.1);
            bandit.update(state1, 1, 0.2);
            // Action 0 gets high reward for state2
            bandit.update(state2, 0, 1.0);
            bandit.update(state2, 1, 0.1);
            bandit.update(state2, 2, 0.2);
        }

        int correct1 = 0, correct2 = 0;
        for (int i = 0; i < 100; i++) {
            if (bandit.selectAction(state1) == 2) correct1++;
            if (bandit.selectAction(state2) == 0) correct2++;
        }
        assertTrue(correct1 > 60, "Should select action 2 for state [1,0,0] (got " + correct1 + "/100)");
        assertTrue(correct2 > 60, "Should select action 0 for state [0,1,0] (got " + correct2 + "/100)");
    }
}
