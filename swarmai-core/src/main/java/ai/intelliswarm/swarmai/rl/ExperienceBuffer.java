package ai.intelliswarm.swarmai.rl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Ring buffer that stores (state, action, reward) tuples for RL policy training.
 * Thread-safe. Evicts oldest entries when capacity is reached.
 *
 * <p>Serializable to JSON for cross-session persistence via the Memory interface.
 *
 * <pre>{@code
 * ExperienceBuffer buffer = new ExperienceBuffer(10000);
 * buffer.add("skill_generation", stateVector, actionIndex, reward);
 *
 * List<Experience> batch = buffer.sample(32);  // random mini-batch
 * String json = buffer.toJson();                // persist
 * buffer = ExperienceBuffer.fromJson(json);     // restore
 * }</pre>
 */
public class ExperienceBuffer {

    private static final Logger logger = LoggerFactory.getLogger(ExperienceBuffer.class);

    private final int capacity;
    private final ArrayDeque<Experience> buffer;

    public ExperienceBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    /**
     * Adds an experience tuple. Evicts oldest if at capacity.
     */
    public synchronized void add(String decisionType, double[] state, int action, double reward) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(new Experience(decisionType, state, action, reward, Instant.now()));
    }

    /**
     * Adds a pre-constructed experience.
     */
    public synchronized void add(Experience experience) {
        if (buffer.size() >= capacity) {
            buffer.pollFirst();
        }
        buffer.addLast(experience);
    }

    /**
     * Returns all experiences of the given type.
     */
    public synchronized List<Experience> getByType(String decisionType) {
        return buffer.stream()
                .filter(e -> e.decisionType().equals(decisionType))
                .toList();
    }

    /**
     * Returns a random mini-batch of experiences for training.
     */
    public synchronized List<Experience> sample(int batchSize) {
        List<Experience> all = new ArrayList<>(buffer);
        if (all.size() <= batchSize) return all;
        Collections.shuffle(all);
        return all.subList(0, batchSize);
    }

    /**
     * Returns all experiences in insertion order.
     */
    public synchronized List<Experience> getAll() {
        return new ArrayList<>(buffer);
    }

    public synchronized int size() {
        return buffer.size();
    }

    public synchronized void clear() {
        buffer.clear();
    }

    public int getCapacity() {
        return capacity;
    }

    /**
     * Serializes the buffer to JSON for persistence.
     */
    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("capacity", capacity);
            data.put("experiences", new ArrayList<>(buffer));
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize ExperienceBuffer: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Deserializes a buffer from JSON.
     */
    @SuppressWarnings("unchecked")
    public static ExperienceBuffer fromJson(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            Map<String, Object> data = mapper.readValue(json, Map.class);
            int capacity = (int) data.getOrDefault("capacity", 10000);
            ExperienceBuffer buffer = new ExperienceBuffer(capacity);

            List<Map<String, Object>> experiences = (List<Map<String, Object>>) data.get("experiences");
            if (experiences != null) {
                for (Map<String, Object> exp : experiences) {
                    String type = (String) exp.get("decisionType");
                    List<Number> stateList = (List<Number>) exp.get("state");
                    double[] state = stateList != null
                            ? stateList.stream().mapToDouble(Number::doubleValue).toArray()
                            : new double[0];
                    int action = ((Number) exp.get("action")).intValue();
                    double reward = ((Number) exp.get("reward")).doubleValue();
                    buffer.add(new Experience(type, state, action, reward, Instant.now()));
                }
            }
            return buffer;
        } catch (Exception e) {
            logger.warn("Failed to deserialize ExperienceBuffer: {}", e.getMessage());
            return new ExperienceBuffer(10000);
        }
    }

    /**
     * A single experience tuple.
     *
     * @param decisionType the type of decision (skill_generation, convergence, selection)
     * @param state        the feature vector at decision time
     * @param action       the action index taken
     * @param reward       the observed reward
     * @param timestamp    when this experience was recorded
     */
    public record Experience(
            String decisionType,
            double[] state,
            int action,
            double reward,
            Instant timestamp
    ) {}
}
