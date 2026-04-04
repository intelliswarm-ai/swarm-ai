package ai.intelliswarm.swarmai.enterprise.rl.deep;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.intelliswarm.swarmai.rl.ConvergenceContext;
import ai.intelliswarm.swarmai.rl.SkillGenerationContext;

/**
 * Converts RL context records into DJL NDArray tensors for neural network input.
 */
public final class StateEncoder {

    private StateEncoder() {}

    /**
     * Encodes a SkillGenerationContext into a 1-D NDArray [8] as FLOAT32.
     */
    public static NDArray encode(NDManager manager, SkillGenerationContext context) {
        return manager.create(toFloat(context.toFeatureVector()),
                new Shape(1, SkillGenerationContext.featureDimension()));
    }

    /**
     * Encodes a ConvergenceContext into a 1-D NDArray [6] as FLOAT32.
     */
    public static NDArray encode(NDManager manager, ConvergenceContext context) {
        return manager.create(toFloat(context.toFeatureVector()),
                new Shape(1, ConvergenceContext.featureDimension()));
    }

    private static float[] toFloat(double[] doubles) {
        float[] floats = new float[doubles.length];
        for (int i = 0; i < doubles.length; i++) {
            floats[i] = (float) doubles[i];
        }
        return floats;
    }

    /**
     * Encodes a batch of state vectors into a 2-D NDArray [batchSize, dim].
     */
    public static NDArray encodeBatch(NDManager manager, double[][] states) {
        int batchSize = states.length;
        int dim = states[0].length;
        float[] flat = new float[batchSize * dim];
        for (int i = 0; i < batchSize; i++) {
            for (int j = 0; j < dim; j++) {
                flat[i * dim + j] = (float) states[i][j];
            }
        }
        return manager.create(flat, new Shape(batchSize, dim));
    }
}
