package ai.intelliswarm.swarmai.rl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the RL policy engine.
 *
 * <pre>{@code
 * swarmai:
 *   rl:
 *     enabled: false              # opt-in (default: disabled)
 *     linucb-alpha: 1.0           # exploration parameter for LinUCB
 *     experience-buffer-capacity: 10000
 *     cold-start-decisions: 50    # delegate to heuristic for first N decisions
 * }</pre>
 */
@ConfigurationProperties(prefix = "swarmai.rl")
public class RLProperties {

    private boolean enabled = false;
    private double linucbAlpha = 1.0;
    private int experienceBufferCapacity = 10000;
    private int coldStartDecisions = 50;
    private boolean neuralLinucbEnabled = false;
    private int neuralLinucbHidden = 32;
    private int neuralLinucbFeatures = 8;
    private float neuralLinucbLearningRate = 0.001f;
    private int neuralLinucbTrainInterval = 20;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getLinucbAlpha() { return linucbAlpha; }
    public void setLinucbAlpha(double linucbAlpha) { this.linucbAlpha = linucbAlpha; }

    public int getExperienceBufferCapacity() { return experienceBufferCapacity; }
    public void setExperienceBufferCapacity(int experienceBufferCapacity) { this.experienceBufferCapacity = experienceBufferCapacity; }

    public int getColdStartDecisions() { return coldStartDecisions; }
    public void setColdStartDecisions(int coldStartDecisions) { this.coldStartDecisions = coldStartDecisions; }

    public boolean isNeuralLinucbEnabled() { return neuralLinucbEnabled; }
    public void setNeuralLinucbEnabled(boolean v) { this.neuralLinucbEnabled = v; }

    public int getNeuralLinucbHidden() { return neuralLinucbHidden; }
    public void setNeuralLinucbHidden(int v) { this.neuralLinucbHidden = v; }

    public int getNeuralLinucbFeatures() { return neuralLinucbFeatures; }
    public void setNeuralLinucbFeatures(int v) { this.neuralLinucbFeatures = v; }

    public float getNeuralLinucbLearningRate() { return neuralLinucbLearningRate; }
    public void setNeuralLinucbLearningRate(float v) { this.neuralLinucbLearningRate = v; }

    public int getNeuralLinucbTrainInterval() { return neuralLinucbTrainInterval; }
    public void setNeuralLinucbTrainInterval(int v) { this.neuralLinucbTrainInterval = v; }
}
