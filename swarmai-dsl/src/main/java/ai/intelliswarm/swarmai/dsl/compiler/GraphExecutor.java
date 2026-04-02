package ai.intelliswarm.swarmai.dsl.compiler;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.dsl.model.ConditionalEdgeDefinition;
import ai.intelliswarm.swarmai.dsl.model.EdgeDefinition;
import ai.intelliswarm.swarmai.dsl.model.GraphNodeDefinition;
import ai.intelliswarm.swarmai.state.*;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Executes a graph-based workflow by traversing nodes and edges.
 * Evaluates conditional edges using {@link ConditionEvaluator}.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Start at START node</li>
 *   <li>Follow edge from START to first node</li>
 *   <li>Execute node's agent task</li>
 *   <li>Store output in state under {@code nodeId_output}</li>
 *   <li>Resolve next node via static or conditional edges</li>
 *   <li>Repeat until END is reached</li>
 * </ol>
 */
public class GraphExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GraphExecutor.class);
    private static final String START = "START";
    private static final String END = "END";
    private static final int MAX_STEPS = 100; // safety cap to prevent infinite loops

    private final String swarmId;
    private final LinkedHashMap<String, GraphNodeDefinition> nodeDefs;
    private final List<EdgeDefinition> edges;
    private final Map<String, Agent> agentMap;
    private final StateSchema stateSchema;
    private final Map<HookPoint, List<SwarmHook<AgentState>>> hooks;

    public GraphExecutor(String swarmId,
                         LinkedHashMap<String, GraphNodeDefinition> nodeDefs,
                         List<EdgeDefinition> edges,
                         Map<String, Agent> agentMap,
                         StateSchema stateSchema) {
        this(swarmId, nodeDefs, edges, agentMap, stateSchema, Map.of());
    }

    public GraphExecutor(String swarmId,
                         LinkedHashMap<String, GraphNodeDefinition> nodeDefs,
                         List<EdgeDefinition> edges,
                         Map<String, Agent> agentMap,
                         StateSchema stateSchema,
                         Map<HookPoint, List<SwarmHook<AgentState>>> hooks) {
        this.swarmId = swarmId;
        this.nodeDefs = nodeDefs;
        this.edges = edges;
        this.agentMap = agentMap;
        this.stateSchema = stateSchema;
        this.hooks = hooks != null ? hooks : Map.of();
    }

    public SwarmOutput execute(Map<String, Object> inputs) {
        LocalDateTime startTime = LocalDateTime.now();
        List<TaskOutput> allOutputs = new ArrayList<>();

        // Initialize state from inputs
        AgentState state = stateSchema != null
                ? AgentState.of(stateSchema, inputs != null ? inputs : Map.of())
                : AgentState.of(inputs != null ? inputs : Map.of());

        // Fire BEFORE_WORKFLOW hooks
        state = fireHooks(HookPoint.BEFORE_WORKFLOW, state, null);

        // Find first node from START
        String currentNode = resolveNextNode(START, state);
        int stepCount = 0;

        while (!END.equals(currentNode) && stepCount < MAX_STEPS) {
            stepCount++;
            logger.info("[GRAPH] Step {} — executing node '{}'", stepCount, currentNode);

            GraphNodeDefinition nodeDef = nodeDefs.get(currentNode);
            if (nodeDef == null) {
                throw new SwarmCompileException("Graph execution reached unknown node: '" + currentNode + "'");
            }

            // Fire BEFORE_TASK hooks
            state = fireHooks(HookPoint.BEFORE_TASK, state, currentNode);

            // Execute the node's agent task
            Agent agent = agentMap.get(nodeDef.getAgent());
            Task task = Task.builder()
                    .id(currentNode)
                    .description(interpolateState(nodeDef.getTask(), state))
                    .expectedOutput(nodeDef.getExpectedOutput() != null
                            ? nodeDef.getExpectedOutput() : "Task output")
                    .agent(agent)
                    .build();

            // Provide previous outputs as context
            TaskOutput output = agent.executeTask(task, allOutputs);
            allOutputs.add(output);

            // Merge output into state
            Map<String, Object> updates = new HashMap<>();
            updates.put(currentNode + "_output", output.getRawOutput());
            state = state.withUpdate(updates);

            // Fire AFTER_TASK hooks
            state = fireHooks(HookPoint.AFTER_TASK, state, currentNode);

            logger.info("[GRAPH] Node '{}' completed — output length: {} chars",
                    currentNode, output.getRawOutput() != null ? output.getRawOutput().length() : 0);

            // Resolve next node
            currentNode = resolveNextNode(currentNode, state);
        }

        if (stepCount >= MAX_STEPS) {
            logger.warn("[GRAPH] Reached max steps ({}) — possible infinite loop", MAX_STEPS);
        }

        // Fire AFTER_WORKFLOW hooks
        state = fireHooks(HookPoint.AFTER_WORKFLOW, state, null);

        LocalDateTime endTime = LocalDateTime.now();

        String finalOutput = allOutputs.isEmpty() ? null :
                allOutputs.get(allOutputs.size() - 1).getRawOutput();

        return SwarmOutput.builder()
                .swarmId(swarmId)
                .rawOutput(finalOutput)
                .finalOutput(finalOutput)
                .taskOutputs(allOutputs)
                .startTime(startTime)
                .endTime(endTime)
                .executionTime(Duration.between(startTime, endTime))
                .successful(true)
                .metadata("graphSteps", stepCount)
                .build();
    }

    private AgentState fireHooks(HookPoint point, AgentState state, String taskId) {
        List<SwarmHook<AgentState>> hookList = hooks.get(point);
        if (hookList == null || hookList.isEmpty()) return state;

        AgentState current = state;
        for (SwarmHook<AgentState> hook : hookList) {
            try {
                HookContext<AgentState> ctx = taskId != null
                        ? HookContext.forTask(point, current, swarmId, taskId)
                        : HookContext.forWorkflow(point, current, swarmId);
                current = hook.apply(ctx);
            } catch (Exception e) {
                logger.warn("[GRAPH] Hook at {} failed: {}", point, e.getMessage());
            }
        }
        return current;
    }

    private String resolveNextNode(String fromNode, AgentState state) {
        for (EdgeDefinition edge : edges) {
            if (!edge.getFrom().equals(fromNode)) continue;

            if (edge.isConditional()) {
                // Evaluate conditional branches
                for (ConditionalEdgeDefinition cond : edge.getConditional()) {
                    if (cond.isDefault()) {
                        return cond.target();
                    }
                    if (ConditionEvaluator.evaluate(cond.getWhen(), state)) {
                        logger.info("[GRAPH] Condition '{}' from '{}' → '{}'",
                                cond.getWhen(), fromNode, cond.target());
                        return cond.target();
                    }
                }
                // No condition matched and no default — go to END
                logger.warn("[GRAPH] No condition matched for edge from '{}', going to END", fromNode);
                return END;
            } else {
                // Static edge
                return edge.getTo();
            }
        }

        // No edge found from this node — go to END
        return END;
    }

    /**
     * Simple state interpolation: replaces {key} placeholders with state values.
     */
    private String interpolateState(String template, AgentState state) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> entry : state.data().entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}
