package ai.intelliswarm.swarmai.enterprise.rl.deep;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DQNNetworkTest {

    private NDManager manager;

    @BeforeEach
    void setUp() {
        manager = NDManager.newBaseManager();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void forwardProducesCorrectShape() {
        DQNNetwork net = new DQNNetwork(manager, 8, 64, 32, 4);
        NDArray state = manager.create(new float[]{0.5f, 0.3f, 0.8f, 1.0f, 0.6f, 0.25f, 0.3f, 0.1f},
                new Shape(1, 8));
        NDArray qValues = net.forward(state);
        assertEquals(new Shape(1, 4), qValues.getShape());
    }

    @Test
    void forwardBatchProducesCorrectShape() {
        DQNNetwork net = new DQNNetwork(manager, 8, 64, 32, 4);
        NDArray states = manager.randomNormal(new Shape(5, 8));
        NDArray qValues = net.forwardBatch(states);
        assertEquals(new Shape(5, 4), qValues.getShape());
    }

    @Test
    void convergenceNetworkSmaller() {
        DQNNetwork net = new DQNNetwork(manager, 6, 16, 8, 2);
        NDArray state = manager.create(new float[]{1.2f, 0.3f, 0.5f, 0.8f, 3.0f, 0.9f},
                new Shape(1, 6));
        NDArray qValues = net.forward(state);
        assertEquals(new Shape(1, 2), qValues.getShape());
    }

    @Test
    void trainStepReducesLoss() {
        DQNNetwork net = new DQNNetwork(manager, 4, 16, 8, 2);

        // Create a simple training set: state [1,0,0,0] → action 0 should have Q=1.0
        NDArray states = manager.create(new float[]{
                1, 0, 0, 0,
                0, 1, 0, 0,
                1, 0, 0, 0,
                0, 1, 0, 0
        }, new Shape(4, 4));
        NDArray actions = manager.create(new float[]{0, 1, 0, 1});
        NDArray targets = manager.create(new float[]{1.0f, 0.5f, 1.0f, 0.5f});

        float loss1 = net.trainStep(states, actions, targets, 0.01f);

        // Train more
        for (int i = 0; i < 50; i++) {
            net.trainStep(states, actions, targets, 0.01f);
        }

        float loss2 = net.trainStep(states, actions, targets, 0.01f);
        assertTrue(loss2 < loss1, "Loss should decrease: " + loss1 + " → " + loss2);
    }

    @Test
    void copyFromReproducesOutput() {
        DQNNetwork net1 = new DQNNetwork(manager, 4, 16, 8, 2);
        DQNNetwork net2 = new DQNNetwork(manager, 4, 16, 8, 2);

        NDArray state = manager.create(new float[]{0.5f, 0.3f, 0.8f, 0.1f}, new Shape(1, 4));

        // Before copy, outputs differ
        NDArray q1 = net1.forward(state);
        NDArray q2 = net2.forward(state);
        // They might be different (random init)

        // After copy, outputs should match
        net2.copyFrom(net1);
        NDArray q1After = net1.forward(state);
        NDArray q2After = net2.forward(state);

        assertArrayEquals(q1After.toFloatArray(), q2After.toFloatArray(), 1e-5f);
    }
}
