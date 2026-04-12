package ai.intelliswarm.swarmai.observability.reporter;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.observability.decision.DecisionNode;
import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.observability.event.EnrichedSwarmEvent;
import ai.intelliswarm.swarmai.observability.replay.EventStore;
import ai.intelliswarm.swarmai.observability.replay.WorkflowRecording;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Post-hoc observability reporter — bridges {@link SwarmOutput} into the
 * {@link EventStore} and {@link DecisionTracer} after {@code Swarm.kickoff()}
 * returns, then logs the 📊 OBSERVABILITY SUMMARY (workflow recording + event
 * timeline + decision trace + workflow explanation).
 *
 * <p><b>Why this exists.</b> The core framework does not yet auto-emit
 * {@link SwarmEvent}s or {@link DecisionNode}s from {@code Agent.execute()}
 * (see roadmap item "auto-emit observability events"). Until that work lands,
 * every workflow that wants a populated Observability Summary has to synthesize
 * events and decisions itself from {@link TaskOutput}. This class encapsulates
 * that synthesis so each workflow stays short.
 *
 * <p><b>Usage pattern</b> (right after {@code swarm.kickoff()} returns):
 * <pre>{@code
 * WorkflowObservabilityReporter obs = new WorkflowObservabilityReporter(
 *         "stock-analysis", eventStore, decisionTracer);
 * obs.recordPostHoc(correlationId, result, startMs, endMs,
 *         WorkflowObservabilityReporter.agents(agent1, agent2, agent3));
 * obs.displaySummary(correlationId);
 * }</pre>
 *
 * <p>Both {@code eventStore} and {@code decisionTracer} are optional — pass
 * {@code null} to disable that half of the reporter. The class intentionally
 * has no Spring annotations so each workflow constructs it with its own
 * {@code workflowId}.
 *
 * <p>Emitted events: 1 × {@code SWARM_STARTED}, N × {@code TASK_COMPLETED}
 * (one per {@link TaskOutput}), 1 × {@code SWARM_COMPLETED}. Per-task events
 * carry {@code durationMs}, {@code elapsedSinceStartMs} (monotonically
 * increasing), and token usage.
 */
public final class WorkflowObservabilityReporter {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowObservabilityReporter.class);

    private final String workflowId;
    private final EventStore eventStore;
    private final DecisionTracer decisionTracer;

    public WorkflowObservabilityReporter(String workflowId,
                                         EventStore eventStore,
                                         DecisionTracer decisionTracer) {
        this.workflowId = workflowId;
        this.eventStore = eventStore;
        this.decisionTracer = decisionTracer;
    }

    /**
     * Records a SWARM_STARTED + one TASK_COMPLETED per output + SWARM_COMPLETED
     * into the {@link EventStore}, and one {@link DecisionNode} per output into
     * the {@link DecisionTracer}. Safe to call when either backing store is null.
     *
     * @param correlationId correlation id passed earlier to {@code startTrace()}
     * @param result        result of {@code swarm.kickoff()}
     * @param startMs       epoch-millis timestamp taken just before kickoff
     * @param endMs         epoch-millis timestamp taken just after kickoff
     * @param agents        the agents used in the swarm — looked up by id so
     *                      events / decisions carry the human-readable role
     *                      instead of {@code null}.
     */
    public void recordPostHoc(String correlationId,
                              SwarmOutput result,
                              long startMs,
                              long endMs,
                              Collection<Agent> agents) {
        Map<String, String> agentRoleById = buildAgentRoleMap(agents);
        recordDecisions(correlationId, result, agentRoleById);
        recordEvents(correlationId, result, startMs, endMs, agentRoleById);
    }

    /**
     * Logs the 📊 OBSERVABILITY SUMMARY block — workflow recording fields +
     * event timeline + decision trace + workflow explanation. Falls back to
     * friendly messages when either store is empty so readers can see exactly
     * what observability plumbing is or isn't wired.
     */
    public void displaySummary(String correlationId) {
        logger.info("\n" + "=".repeat(80));
        logger.info("📊 OBSERVABILITY SUMMARY");
        logger.info("=".repeat(80));
        displayWorkflowRecording(correlationId);
        displayDecisionTrace(correlationId);
        logger.info("=".repeat(80));
    }

    // ---------- internals ----------

    private Map<String, String> buildAgentRoleMap(Collection<Agent> agents) {
        Map<String, String> map = new HashMap<>();
        if (agents != null) {
            for (Agent a : agents) {
                if (a != null && a.getId() != null) {
                    map.put(a.getId(), a.getRole());
                }
            }
        }
        return map;
    }

    private void recordDecisions(String correlationId,
                                 SwarmOutput result,
                                 Map<String, String> agentRoleById) {
        if (decisionTracer == null || !decisionTracer.isEnabled()) return;

        for (TaskOutput out : result.getTaskOutputs()) {
            String raw = out.getRawOutput();
            String preview = raw == null ? ""
                    : (raw.length() > 280 ? raw.substring(0, 280) + "…" : raw);
            String agentId = out.getAgentId() != null ? out.getAgentId() : "unknown-agent";
            DecisionNode node = DecisionNode.builder()
                    .correlationId(correlationId)
                    .agentId(agentId)
                    .agentRole(agentRoleById.getOrDefault(agentId, "unknown-role"))
                    .taskId(out.getTaskId())
                    .taskDescription(out.getDescription())
                    .decision(preview)
                    .latencyMs(out.getExecutionTimeMs() != null ? out.getExecutionTimeMs() : 0)
                    .build();
            decisionTracer.recordDecision(node);
        }
    }

    private void recordEvents(String correlationId,
                              SwarmOutput result,
                              long startMs,
                              long endMs,
                              Map<String, String> agentRoleById) {
        if (eventStore == null) return;

        Instant startInstant = Instant.ofEpochMilli(startMs);
        Instant endInstant = Instant.ofEpochMilli(endMs);

        eventStore.store(EnrichedSwarmEvent
                .builder(this, SwarmEvent.Type.SWARM_STARTED, workflowId + " swarm started")
                .correlationId(correlationId)
                .swarmId(workflowId)
                .eventInstant(startInstant)
                .elapsedSinceStartMs(0L)
                .success()
                .build());

        // Accumulate per-task durations so each TASK_COMPLETED event carries a
        // monotonically-increasing elapsedSinceStartMs relative to SWARM_STARTED.
        // Without this, the Event Timeline shows every task at [0 ms].
        long runningElapsed = 0L;
        for (TaskOutput out : result.getTaskOutputs()) {
            long taskMs = out.getExecutionTimeMs() != null ? out.getExecutionTimeMs() : 0L;
            runningElapsed += taskMs;
            String agentId = out.getAgentId() != null ? out.getAgentId() : "unknown-agent";
            eventStore.store(EnrichedSwarmEvent
                    .builder(this, SwarmEvent.Type.TASK_COMPLETED,
                            "task " + out.getTaskId() + " completed")
                    .correlationId(correlationId)
                    .swarmId(workflowId)
                    .agentId(agentId)
                    .agentRole(agentRoleById.getOrDefault(agentId, "unknown-role"))
                    .taskId(out.getTaskId())
                    .durationMs(taskMs)
                    .elapsedSinceStartMs(runningElapsed)
                    .tokenUsage(out.getPromptTokens(), out.getCompletionTokens())
                    .success()
                    .build());
        }

        eventStore.store(EnrichedSwarmEvent
                .builder(this, SwarmEvent.Type.SWARM_COMPLETED, workflowId + " swarm completed")
                .correlationId(correlationId)
                .swarmId(workflowId)
                .eventInstant(endInstant)
                .elapsedSinceStartMs(endMs - startMs)
                .durationMs(endMs - startMs)
                .success()
                .build());
    }

    private void displayWorkflowRecording(String correlationId) {
        if (eventStore == null) {
            logger.info("   Event store not configured");
            return;
        }
        Optional<WorkflowRecording> recordingOpt = eventStore.createRecording(correlationId);
        if (recordingOpt.isEmpty()) {
            logger.info("   No workflow recording available");
            return;
        }
        WorkflowRecording recording = recordingOpt.get();
        WorkflowRecording.WorkflowSummary summary = recording.getSummary();

        logger.info("📋 Workflow Recording:");
        logger.info("   Correlation ID: {}", recording.getCorrelationId());
        logger.info("   Status: {}", recording.getStatus());
        logger.info("   Duration: {} ms", recording.getDurationMs());
        logger.info("   Total Events: {}", summary.getTotalEvents());
        logger.info("   Unique Agents: {}", summary.getUniqueAgents());
        logger.info("   Unique Tasks: {}", summary.getUniqueTasks());
        logger.info("   Unique Tools: {}", summary.getUniqueTools());
        logger.info("   Error Count: {}", summary.getErrorCount());

        logger.info("\n📅 Event Timeline:");
        for (WorkflowRecording.EventRecord event : recording.getTimeline()) {
            logger.info("   [{} ms] {} - {} (agent: {}, task: {}, tool: {})",
                    event.getElapsedMs() != null ? event.getElapsedMs() : 0,
                    event.getEventType(),
                    truncate(event.getMessage(), 50),
                    event.getAgentId() != null ? truncate(event.getAgentId(), 20) : "-",
                    event.getTaskId() != null ? truncate(event.getTaskId(), 20) : "-",
                    event.getToolName() != null ? event.getToolName() : "-");
        }
    }

    private void displayDecisionTrace(String correlationId) {
        if (decisionTracer == null || !decisionTracer.isEnabled()) {
            logger.info("   Decision tracing not enabled");
            return;
        }
        Optional<DecisionTree> treeOpt = decisionTracer.getDecisionTree(correlationId);
        if (treeOpt.isEmpty()) {
            logger.info("   No decision trace available (enable decision-tracing-enabled in config)");
            return;
        }
        DecisionTree tree = treeOpt.get();
        logger.info("\n🧠 Decision Trace:");
        logger.info("   Total Decisions: {}", tree.getNodeCount());
        logger.info("   Unique Agents: {}", tree.getUniqueAgentIds().size());
        logger.info("   Unique Tasks: {}", tree.getUniqueTaskIds().size());

        String explanation = decisionTracer.explainWorkflow(correlationId);
        logger.info("\n📝 Workflow Explanation:\n{}", explanation);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /** Convenience for passing agents as a varargs list. */
    public static Collection<Agent> agents(Agent... agents) {
        return List.of(agents);
    }
}
