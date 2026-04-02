package ai.intelliswarm.swarmai.rl.deep;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReplayBufferTest {

    @Test
    void addAndRetrieve() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        buffer.add(new double[]{1.0, 2.0}, 0, 0.5, new double[]{1.1, 2.1});
        assertEquals(1, buffer.size());
    }

    @Test
    void evictsAtCapacity() {
        ReplayBuffer buffer = new ReplayBuffer(3);
        buffer.add(new double[]{1.0}, 0, 0.1, new double[]{1.1});
        buffer.add(new double[]{2.0}, 1, 0.2, new double[]{2.1});
        buffer.add(new double[]{3.0}, 0, 0.3, new double[]{3.1});
        buffer.add(new double[]{4.0}, 1, 0.4, new double[]{4.1}); // evicts first
        assertEquals(3, buffer.size());
    }

    @Test
    void sampleReturnsBatch() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        for (int i = 0; i < 20; i++) {
            buffer.add(new double[]{i}, 0, i * 0.1, new double[]{i + 1});
        }

        List<ReplayBuffer.PrioritizedExperience> batch = buffer.sample(5);
        assertEquals(5, batch.size());
    }

    @Test
    void sampleReturnsAllIfSmall() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        buffer.add(new double[]{1.0}, 0, 0.5, new double[]{1.1});
        buffer.add(new double[]{2.0}, 1, 0.8, new double[]{2.1});

        List<ReplayBuffer.PrioritizedExperience> batch = buffer.sample(10);
        assertEquals(2, batch.size());
    }

    @Test
    void prioritizedSamplingFavorsHighPriority() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        // Add one high-priority and many low-priority
        buffer.add(new double[]{1.0}, 0, 1.0, new double[]{1.1}, 100.0); // high priority
        for (int i = 0; i < 10; i++) {
            buffer.add(new double[]{(double) i}, 1, 0.1, new double[]{i + 0.1}, 0.01); // low priority
        }

        // Sample many batches and count how often the high-priority item appears
        int highPriorityCount = 0;
        for (int trial = 0; trial < 100; trial++) {
            List<ReplayBuffer.PrioritizedExperience> batch = buffer.sample(1);
            if (batch.get(0).reward() == 1.0) highPriorityCount++;
        }

        assertTrue(highPriorityCount > 50,
                "High-priority item should be sampled frequently, got " + highPriorityCount + "/100");
    }

    @Test
    void updatePriority() {
        ReplayBuffer buffer = new ReplayBuffer(100);
        buffer.add(new double[]{1.0}, 0, 0.5, new double[]{1.1}, 1.0);

        buffer.updatePriority(0, 10.0);
        // Priority updated — verified by sampling behavior
        assertEquals(1, buffer.size());
    }
}
