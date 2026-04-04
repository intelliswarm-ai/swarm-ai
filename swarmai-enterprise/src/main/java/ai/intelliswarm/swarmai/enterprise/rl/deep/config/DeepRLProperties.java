package ai.intelliswarm.swarmai.enterprise.rl.deep.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Enterprise configuration properties for Deep RL (DQN-based neural network policy).
 */
@ConfigurationProperties(prefix = "swarmai.enterprise.deep-rl")
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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public float getLearningRate() { return learningRate; }
    public void setLearningRate(float learningRate) { this.learningRate = learningRate; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public double getEpsilonStart() { return epsilonStart; }
    public void setEpsilonStart(double v) { this.epsilonStart = v; }
    public double getEpsilonEnd() { return epsilonEnd; }
    public void setEpsilonEnd(double v) { this.epsilonEnd = v; }
    public int getEpsilonDecaySteps() { return epsilonDecaySteps; }
    public void setEpsilonDecaySteps(int v) { this.epsilonDecaySteps = v; }
    public int getTargetUpdateInterval() { return targetUpdateInterval; }
    public void setTargetUpdateInterval(int v) { this.targetUpdateInterval = v; }
    public int getTrainInterval() { return trainInterval; }
    public void setTrainInterval(int v) { this.trainInterval = v; }
    public int getHiddenSize() { return hiddenSize; }
    public void setHiddenSize(int v) { this.hiddenSize = v; }
    public int getBufferCapacity() { return bufferCapacity; }
    public void setBufferCapacity(int v) { this.bufferCapacity = v; }
    public int getColdStartDecisions() { return coldStartDecisions; }
    public void setColdStartDecisions(int v) { this.coldStartDecisions = v; }
}
