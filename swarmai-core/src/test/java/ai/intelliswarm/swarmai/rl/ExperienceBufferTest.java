package ai.intelliswarm.swarmai.rl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperienceBufferTest {

    @Test
    void addAndRetrieve() {
        ExperienceBuffer buffer = new ExperienceBuffer(100);
        buffer.add("skill_generation", new double[]{0.5, 0.3}, 1, 0.8);
        buffer.add("convergence", new double[]{1.2, 0.0}, 0, 1.0);

        assertEquals(2, buffer.size());
        assertEquals(2, buffer.getAll().size());
    }

    @Test
    void evictsOldestAtCapacity() {
        ExperienceBuffer buffer = new ExperienceBuffer(3);
        buffer.add("type", new double[]{1.0}, 0, 0.1);
        buffer.add("type", new double[]{2.0}, 1, 0.2);
        buffer.add("type", new double[]{3.0}, 0, 0.3);
        buffer.add("type", new double[]{4.0}, 1, 0.4); // evicts first

        assertEquals(3, buffer.size());
        var all = buffer.getAll();
        assertEquals(0.2, all.get(0).reward(), 0.001); // oldest remaining
        assertEquals(0.4, all.get(2).reward(), 0.001); // newest
    }

    @Test
    void filterByType() {
        ExperienceBuffer buffer = new ExperienceBuffer(100);
        buffer.add("skill_generation", new double[]{1.0}, 0, 0.5);
        buffer.add("convergence", new double[]{2.0}, 1, 0.8);
        buffer.add("skill_generation", new double[]{3.0}, 2, 0.9);

        List<ExperienceBuffer.Experience> skillExps = buffer.getByType("skill_generation");
        assertEquals(2, skillExps.size());

        List<ExperienceBuffer.Experience> convExps = buffer.getByType("convergence");
        assertEquals(1, convExps.size());
    }

    @Test
    void sampleReturnsBatchOrAll() {
        ExperienceBuffer buffer = new ExperienceBuffer(100);
        for (int i = 0; i < 10; i++) {
            buffer.add("type", new double[]{i}, 0, i * 0.1);
        }

        // Sample fewer than available
        List<ExperienceBuffer.Experience> batch = buffer.sample(5);
        assertEquals(5, batch.size());

        // Sample more than available
        List<ExperienceBuffer.Experience> all = buffer.sample(20);
        assertEquals(10, all.size());
    }

    @Test
    void clearEmptiesBuffer() {
        ExperienceBuffer buffer = new ExperienceBuffer(100);
        buffer.add("type", new double[]{1.0}, 0, 0.5);
        buffer.add("type", new double[]{2.0}, 1, 0.8);
        buffer.clear();
        assertEquals(0, buffer.size());
    }

    @Test
    void jsonSerializationRoundTrip() {
        ExperienceBuffer buffer = new ExperienceBuffer(100);
        buffer.add("skill_generation", new double[]{0.5, 0.3, 0.8}, 2, 0.75);
        buffer.add("convergence", new double[]{1.1, 0.9}, 0, 1.0);

        String json = buffer.toJson();
        assertNotNull(json);
        assertFalse(json.isEmpty());

        ExperienceBuffer restored = ExperienceBuffer.fromJson(json);
        assertEquals(2, restored.size());
        assertEquals(100, restored.getCapacity());

        var all = restored.getAll();
        assertEquals("skill_generation", all.get(0).decisionType());
        assertEquals(2, all.get(0).action());
        assertEquals(0.75, all.get(0).reward(), 0.001);
        assertArrayEquals(new double[]{0.5, 0.3, 0.8}, all.get(0).state(), 0.001);
    }

    @Test
    void fromJsonHandlesCorruptData() {
        ExperienceBuffer buffer = ExperienceBuffer.fromJson("not valid json");
        assertNotNull(buffer);
        assertEquals(0, buffer.size());
    }

    @Test
    void fromJsonHandlesEmptyJson() {
        ExperienceBuffer buffer = ExperienceBuffer.fromJson("{}");
        assertNotNull(buffer);
        assertEquals(0, buffer.size());
    }

    @Test
    void threadSafety() throws InterruptedException {
        ExperienceBuffer buffer = new ExperienceBuffer(1000);

        // Concurrent writes
        Thread[] threads = new Thread[10];
        for (int t = 0; t < 10; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    buffer.add("type_" + threadId, new double[]{i}, threadId, i * 0.01);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1000, buffer.size());
    }
}
