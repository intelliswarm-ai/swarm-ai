package ai.intelliswarm.swarmai.state.hooks;

import ai.intelliswarm.swarmai.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in SwarmHook that saves a checkpoint after each hook invocation.
 * Requires a {@link CheckpointSaver} to be available (otherwise logs a warning and no-ops).
 */
public class CheckpointSwarmHook implements SwarmHook<AgentState> {

    private static final Logger logger = LoggerFactory.getLogger(CheckpointSwarmHook.class);

    private CheckpointSaver checkpointSaver;

    public CheckpointSwarmHook() {}

    public CheckpointSwarmHook(CheckpointSaver checkpointSaver) {
        this.checkpointSaver = checkpointSaver;
    }

    public void setCheckpointSaver(CheckpointSaver checkpointSaver) {
        this.checkpointSaver = checkpointSaver;
    }

    @Override
    public AgentState apply(HookContext<AgentState> context) {
        if (checkpointSaver == null) {
            logger.debug("[HOOK] Checkpoint hook skipped — no CheckpointSaver configured");
            return context.state();
        }

        String taskId = context.taskId() != null ? context.taskId() : "unknown";
        Checkpoint checkpoint = Checkpoint.create(
                context.workflowId(),
                taskId,
                null,
                context.state());

        checkpointSaver.save(checkpoint);
        logger.info("[HOOK] Checkpoint saved for workflow='{}' at task='{}'",
                context.workflowId(), taskId);

        return context.state();
    }
}
