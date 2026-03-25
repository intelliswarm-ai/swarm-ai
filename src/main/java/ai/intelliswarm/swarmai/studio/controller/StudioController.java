package ai.intelliswarm.swarmai.studio.controller;

import ai.intelliswarm.swarmai.observability.decision.DecisionTracer;
import ai.intelliswarm.swarmai.observability.decision.DecisionTree;
import ai.intelliswarm.swarmai.studio.event.StudioEventBroadcaster;
import ai.intelliswarm.swarmai.studio.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for the SwarmAI Studio.
 * Exposes endpoints for workflow inspection, live event streaming,
 * metrics, and decision tracing.
 */
@RestController
@RequestMapping("/api/studio")
public class StudioController {

    private final WorkflowService workflowService;
    private final StudioEventBroadcaster studioEventBroadcaster;
    private final DecisionTracer decisionTracer;

    @Autowired
    public StudioController(
            WorkflowService workflowService,
            StudioEventBroadcaster studioEventBroadcaster,
            @Autowired(required = false) DecisionTracer decisionTracer) {
        this.workflowService = workflowService;
        this.studioEventBroadcaster = studioEventBroadcaster;
        this.decisionTracer = decisionTracer;
    }

    // ---- Workflow endpoints ----

    /**
     * Lists all workflows with summary information.
     */
    @GetMapping("/workflows")
    public ResponseEntity<List<Map<String, Object>>> listWorkflows() {
        return ResponseEntity.ok(workflowService.listWorkflows());
    }

    /**
     * Returns the full recording for a specific workflow.
     */
    @GetMapping("/workflows/{id}")
    public ResponseEntity<Map<String, Object>> getWorkflow(@PathVariable("id") String correlationId) {
        Map<String, Object> workflow = workflowService.getWorkflow(correlationId);
        if (workflow.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(workflow);
    }

    /**
     * Returns the visual graph representation of a workflow.
     */
    @GetMapping("/workflows/{id}/graph")
    public ResponseEntity<Map<String, Object>> getWorkflowGraph(@PathVariable("id") String correlationId) {
        return ResponseEntity.ok(workflowService.getWorkflowGraph(correlationId));
    }

    /**
     * Returns events for a workflow, optionally filtered by event type.
     */
    @GetMapping("/workflows/{id}/events")
    public ResponseEntity<List<Map<String, Object>>> getWorkflowEvents(
            @PathVariable("id") String correlationId,
            @RequestParam(value = "eventType", required = false) String eventType) {
        return ResponseEntity.ok(workflowService.getWorkflowEvents(correlationId, eventType));
    }

    /**
     * Returns detailed information for a specific task within a workflow.
     */
    @GetMapping("/workflows/{id}/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskDetail(
            @PathVariable("id") String correlationId,
            @PathVariable("taskId") String taskId) {
        Map<String, Object> detail = workflowService.getTaskDetail(correlationId, taskId);
        if (detail.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    // ---- Live event streaming ----

    /**
     * SSE endpoint for live event streaming.
     * Optionally filtered by swarmId query parameter.
     */
    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @RequestParam(value = "swarmId", required = false) String swarmId) {
        if (swarmId != null && !swarmId.isBlank()) {
            return studioEventBroadcaster.subscribe(swarmId);
        }
        return studioEventBroadcaster.subscribe();
    }

    // ---- Metrics ----

    /**
     * Returns aggregated metrics across all workflows.
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetricsOverview() {
        return ResponseEntity.ok(workflowService.getMetricsOverview());
    }

    // ---- Decision tracing ----

    /**
     * Returns the decision tree for a workflow.
     */
    @GetMapping("/decisions/{correlationId}")
    public ResponseEntity<Map<String, Object>> getDecisionTree(
            @PathVariable("correlationId") String correlationId) {
        if (decisionTracer == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Decision tracing is not enabled. Set swarmai.observability.decision-tracing-enabled=true"
            ));
        }

        Optional<DecisionTree> treeOpt = decisionTracer.getDecisionTree(correlationId);
        if (treeOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "No decision tree found for this workflow"
            ));
        }

        return ResponseEntity.ok(treeOpt.get().toMap());
    }

    /**
     * Returns a human-readable explanation of a workflow's decision trace.
     */
    @GetMapping("/decisions/{correlationId}/explain")
    public ResponseEntity<Map<String, Object>> explainWorkflow(
            @PathVariable("correlationId") String correlationId) {
        if (decisionTracer == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Decision tracing is not enabled. Set swarmai.observability.decision-tracing-enabled=true"
            ));
        }

        String explanation = decisionTracer.explainWorkflow(correlationId);
        return ResponseEntity.ok(Map.of(
                "correlationId", correlationId,
                "explanation", explanation
        ));
    }
}
