package ai.intelliswarm.swarmai.rl.deep.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Deep RL (DQN-based neural network policy).
 *
 * <pre>{@code
 * swarmai:
 *   deep-rl:
 *     enabled: false
 *     learning-rate: 0.001
 *     batch-size: 32
 *     epsilon-start: 1.0
 *     epsilon-end: 0.05
 *     epsilon-decay-steps: 500
 *     target-update-interval: 50
 *     train-interval: 10
 *     hidden-size: 64
 *     buffer-capacity: 10000
 *     cold-start-decisions: 50
 * }</pre>
 */
@ConfigurationProperties(prefix = "swarmai.deep-rl")
public class DeepRLProperties {

    private boolean enabled = false;
    private float learningRate = 0.001f;
    private int batchSize = 32;
    private double epsilonStart = 1.0;
    private double epsilonEnd = 0.05;
    private int epsilonDecaySteps = 500;
    private int targetUpdateInterval = 50;
    private int trainInterval = 10;
    private int hiddenSize = 64;
    private int bufferCapacity = 10000;
    private int coldStartDecisions = 50;

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public float getLearningRate() { return learningRate; }
    public void setLearningRate(float learningRate) { this.learningRate = learningRate; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public double getEpsilonStart() { return epsilonStart; }
    public void setEpsilonStart(double epsilonStart) { this.epsilonStart = epsilonStart; }

    public double getEpsilonEnd() { return epsilonEnd; }
    public void setEpsilonEnd(double epsilonEnd) { this.epsilonEnd = epsilonEnd; }

    public int getEpsilonDecaySteps() { return epsilonDecaySteps; }
    public void setEpsilonDecaySteps(int epsilonDecaySteps) { this.epsilonDecaySteps = epsilonDecaySteps; }

    public int getTargetUpdateInterval() { return targetUpdateInterval; }
    public void setTargetUpdateInterval(int targetUpdateInterval) { this.targetUpdateInterval = targetUpdateInterval; }

    public int getTrainInterval() { return trainInterval; }
    public void setTrainInterval(int trainInterval) { this.trainInterval = trainInterval; }

    public int getHiddenSize() { return hiddenSize; }
    public void setHiddenSize(int hiddenSize) { this.hiddenSize = hiddenSize; }

    public int getBufferCapacity() { return bufferCapacity; }
    public void setBufferCapacity(int bufferCapacity) { this.bufferCapacity = bufferCapacity; }

    public int getColdStartDecisions() { return coldStartDecisions; }
    public void setColdStartDecisions(int coldStartDecisions) { this.coldStartDecisions = coldStartDecisions; }
}
