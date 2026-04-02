package ai.intelliswarm.swarmai.rl.deep;

import ai.intelliswarm.swarmai.rl.ExperienceBuffer;

import java.util.*;

/**
 * Prioritized experience replay buffer for DQN training.
 * Higher-error experiences are sampled more frequently, improving training efficiency.
 *
 * <p>Wraps the core {@link ExperienceBuffer} and adds priority-weighted sampling.
 */
public class ReplayBuffer {

    private final int capacity;
    private final List<PrioritizedExperience> buffer;
    private final Random random;

    public ReplayBuffer(int capacity) {
        this(capacity, new Random());
    }

    public ReplayBuffer(int capacity, Random random) {
        this.capacity = capacity;
        this.buffer = new ArrayList<>(Math.min(capacity, 1024));
        this.random = random;
    }

    /**
     * Adds an experience with default priority (max priority in buffer, or 1.0).
     */
    public synchronized void add(double[] state, int action, double reward, double[] nextState) {
        double priority = buffer.isEmpty() ? 1.0 :
                buffer.stream().mapToDouble(e -> e.priority).max().orElse(1.0);
        add(state, action, reward, nextState, priority);
    }

    /**
     * Adds an experience with explicit priority.
     */
    public synchronized void add(double[] state, int action, double reward, double[] nextState, double priority) {
        if (buffer.size() >= capacity) {
            buffer.remove(0); // evict oldest
        }
        buffer.add(new PrioritizedExperience(state, action, reward, nextState, Math.max(0.01, priority)));
    }

    /**
     * Samples a mini-batch using priority-weighted probability.
     */
    public synchronized List<PrioritizedExperience> sample(int batchSize) {
        if (buffer.size() <= batchSize) {
            return new ArrayList<>(buffer);
        }

        // Compute sampling probabilities from priorities
        double totalPriority = buffer.stream().mapToDouble(e -> e.priority).sum();
        List<PrioritizedExperience> batch = new ArrayList<>(batchSize);
        Set<Integer> sampled = new HashSet<>();

        while (batch.size() < batchSize) {
            double r = random.nextDouble() * totalPriority;
            double cumulative = 0;
            for (int i = 0; i < buffer.size(); i++) {
                cumulative += buffer.get(i).priority;
                if (cumulative >= r && !sampled.contains(i)) {
                    batch.add(buffer.get(i));
                    sampled.add(i);
                    break;
                }
            }
            // Safety: if we couldn't find a unique sample, pick random
            if (batch.size() < sampled.size()) {
                int idx = random.nextInt(buffer.size());
                if (!sampled.contains(idx)) {
                    batch.add(buffer.get(idx));
                    sampled.add(idx);
                }
            }
        }

        return batch;
    }

    /**
     * Updates the priority of an experience (used after computing TD error).
     */
    public synchronized void updatePriority(int bufferIndex, double newPriority) {
        if (bufferIndex >= 0 && bufferIndex < buffer.size()) {
            PrioritizedExperience old = buffer.get(bufferIndex);
            buffer.set(bufferIndex, new PrioritizedExperience(
                    old.state, old.action, old.reward, old.nextState, Math.max(0.01, newPriority)));
        }
    }

    public synchronized int size() {
        return buffer.size();
    }

    /**
     * A single experience with priority weight.
     */
    public record PrioritizedExperience(
            double[] state,
            int action,
            double reward,
            double[] nextState,
            double priority
    ) {}
}
