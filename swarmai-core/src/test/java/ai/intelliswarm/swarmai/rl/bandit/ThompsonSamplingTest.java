package ai.intelliswarm.swarmai.rl.bandit;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ThompsonSamplingTest {

    @Test
    void selectsValidAction() {
        ThompsonSampling ts = new ThompsonSampling(2);
        int action = ts.selectAction();
        assertTrue(action == 0 || action == 1);
    }

    @Test
    void updatesBetaParametersOnSuccess() {
        ThompsonSampling ts = new ThompsonSampling(2);
        double[] before = ts.getParameters(0);
        assertEquals(1.0, before[0]); // alpha
        assertEquals(1.0, before[1]); // beta

        ts.update(0, true);
        double[] after = ts.getParameters(0);
        assertEquals(2.0, after[0]); // alpha increased
        assertEquals(1.0, after[1]); // beta unchanged
    }

    @Test
    void updatesBetaParametersOnFailure() {
        ThompsonSampling ts = new ThompsonSampling(2);
        ts.update(0, false);
        double[] after = ts.getParameters(0);
        assertEquals(1.0, after[0]); // alpha unchanged
        assertEquals(2.0, after[1]); // beta increased
    }

    @Test
    void continuousRewardUpdate() {
        ThompsonSampling ts = new ThompsonSampling(2);
        ts.update(0, 0.8); // 80% success
        double[] after = ts.getParameters(0);
        assertEquals(1.8, after[0], 0.001); // alpha += 0.8
        assertEquals(1.2, after[1], 0.001); // beta += 0.2
    }

    @Test
    void meanConvergesWithData() {
        ThompsonSampling ts = new ThompsonSampling(2);

        // Feed action 0 with 80% success rate
        for (int i = 0; i < 100; i++) {
            ts.update(0, i % 5 != 0); // 80% true
        }

        // Mean should be close to 0.8
        double mean = ts.getMean(0);
        assertTrue(mean > 0.7 && mean < 0.9,
                "Mean should converge to ~0.8, got " + mean);
    }

    @Test
    void prefersBetterAction() {
        ThompsonSampling ts = new ThompsonSampling(2, new Random(42));

        // Action 0: 90% success, Action 1: 10% success
        for (int i = 0; i < 50; i++) {
            ts.update(0, true);
            ts.update(1, false);
        }

        // Sample many times and count
        int action0Count = 0;
        for (int i = 0; i < 100; i++) {
            if (ts.selectAction() == 0) action0Count++;
        }

        assertTrue(action0Count > 80,
                "Should strongly prefer action 0, selected " + action0Count + "/100 times");
    }

    @Test
    void betaSamplingProducesValidValues() {
        ThompsonSampling ts = new ThompsonSampling(2, new Random(42));
        for (int i = 0; i < 100; i++) {
            double sample = ts.sampleBeta(2.0, 3.0);
            assertTrue(sample >= 0 && sample <= 1,
                    "Beta sample should be in [0,1], got " + sample);
        }
    }

    @Test
    void countsUpdates() {
        ThompsonSampling ts = new ThompsonSampling(3);
        ts.update(0, true);
        ts.update(0, false);
        ts.update(1, true);

        assertEquals(2.0, ts.getCount(0), 0.001);
        assertEquals(1.0, ts.getCount(1), 0.001);
        assertEquals(0.0, ts.getCount(2), 0.001);
    }
}
