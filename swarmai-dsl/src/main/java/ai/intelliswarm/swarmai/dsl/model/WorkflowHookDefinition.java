package ai.intelliswarm.swarmai.dsl.model;

/**
 * YAML definition for a workflow-level lifecycle hook.
 *
 * <pre>{@code
 * hooks:
 *   - point: BEFORE_WORKFLOW
 *     type: log
 *     message: "Starting workflow"
 *   - point: AFTER_TASK
 *     type: checkpoint
 *   - point: ON_ERROR
 *     type: log
 *     message: "Workflow error occurred"
 *   - point: BEFORE_TASK
 *     type: custom
 *     class: "com.example.MySwarmHook"
 * }</pre>
 *
 * <p>Supported hook points: BEFORE_WORKFLOW, AFTER_WORKFLOW, BEFORE_TASK,
 * AFTER_TASK, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_CHECKPOINT
 *
 * <p>Supported types: log, checkpoint, custom
 */
public class WorkflowHookDefinition {

    private String point;
    private String type;
    private String message;
    private String hookClass;

    // --- Getters & Setters ---

    public String getPoint() { return point; }
    public void setPoint(String point) { this.point = point; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getHookClass() { return hookClass; }
    public void setHookClass(String hookClass) { this.hookClass = hookClass; }
}
