package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Iterative process that executes tasks in a loop with a reviewer agent.
 *
 * Execution flow:
 *   1. Execute all tasks sequentially (initial pass)
 *   2. Reviewer agent evaluates the final output against quality criteria
 *   3. If reviewer says APPROVED -> done
 *   4. If reviewer says NEEDS_REFINEMENT and iterations < maxIterations:
 *      - Reset tasks
 *      - Re-execute with reviewer feedback as additional context
 *      - Go to step 2
 *   5. Return final output with iteration metrics
 *
 * The reviewer agent's response must contain either "APPROVED" or "NEEDS_REFINEMENT"
 * to signal the verdict. Any feedback below the verdict is passed back as context
 * for the next iteration.
 *
 * Inspired by cyclic workflow patterns, feedback loops,
 * and AutoGen (iterative refinement with critic agents).
 */
public class IterativeProcess implements Process {

    private static final Logger logger = LoggerFactory.getLogger(IterativeProcess.class);

    private static final String APPROVED = "APPROVED";
    private static final String NEEDS_REFINEMENT = "NEEDS_REFINEMENT";
    private static final int DEFAULT_MAX_ITERATIONS = 3;

    private final List<Agent> agents;
    private final Agent reviewerAgent;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxIterations;
    private final String qualityCriteria;

    public IterativeProcess(List<Agent> agents, Agent reviewerAgent,
                            ApplicationEventPublisher eventPublisher) {
        this(agents, reviewerAgent, eventPublisher, DEFAULT_MAX_ITERATIONS, null);
    }

    public IterativeProcess(List<Agent> agents, Agent reviewerAgent,
                            ApplicationEventPublisher eventPublisher,
                            int maxIterations, String qualityCriteria) {
        this.agents = new ArrayList<>(agents);
        this.reviewerAgent = Objects.requireNonNull(reviewerAgent, "Reviewer agent is required");
        this.eventPublisher = eventPublisher;
        this.maxIterations = Math.max(1, maxIterations);
        this.qualityCriteria = qualityCriteria;

        // Remove reviewer from workers if present
        this.agents.removeIf(agent -> agent.equals(reviewerAgent));
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        LocalDateTime startTime = LocalDateTime.now();
        publishEvent(SwarmEvent.Type.PROCESS_STARTED, "Iterative process execution started", swarmId);

        try {
            validateTasks(tasks);

            // Interpolate inputs into task descriptions
            if (inputs != null && !inputs.isEmpty()) {
                for (Task task : tasks) {
                    task.interpolateDescription(inputs);
                }
            }

            // Store original descriptions for reset between iterations
            Map<String, String> originalDescriptions = new HashMap<>();
            for (Task task : tasks) {
                originalDescriptions.put(task.getId(), task.getDescription());
            }

            List<TaskOutput> allOutputs = new ArrayList<>();
            String reviewFeedback = null;
            int iteration = 0;
            boolean approved = false;

            while (iteration < maxIterations && !approved) {
                iteration++;
                logger.info("Iterative Process: Starting iteration {}/{}", iteration, maxIterations);
                publishEvent(SwarmEvent.Type.ITERATION_STARTED,
                        String.format("Iteration %d/%d started", iteration, maxIterations), swarmId,
                        Map.of("iteration", iteration, "maxIterations", maxIterations));

                // Reset tasks for re-execution (except first iteration)
                if (iteration > 1) {
                    for (Task task : tasks) {
                        task.reset();
                    }
                }

                // Execute all tasks sequentially
                List<Task> orderedTasks = orderTasks(tasks);
                List<TaskOutput> iterationOutputs = new ArrayList<>();

                for (Task task : orderedTasks) {
                    ObservabilityContext ctx = ObservabilityContext.currentOrNull();
                    if (ctx != null) {
                        ctx.withTaskId(task.getId());
                        if (task.getAgent() != null) {
                            ctx.withAgentId(task.getAgent().getId());
                        }
                    }

                    publishEvent(SwarmEvent.Type.TASK_STARTED,
                            "Starting task: " + task.getId() + " (iteration " + iteration + ")", swarmId);

                    // Build context: outputs from this iteration + reviewer feedback from previous iteration
                    List<TaskOutput> contextOutputs = getContextForTask(task, iterationOutputs);
                    if (reviewFeedback != null) {
                        // Add reviewer feedback as a synthetic context output
                        TaskOutput feedbackContext = TaskOutput.builder()
                                .taskId("reviewer-feedback")
                                .agentId(reviewerAgent.getId())
                                .rawOutput("REVIEWER FEEDBACK FROM PREVIOUS ITERATION:\n" + reviewFeedback)
                                .description("Reviewer feedback for refinement")
                                .summary("Feedback from iteration " + (iteration - 1))
                                .build();
                        List<TaskOutput> enrichedContext = new ArrayList<>(contextOutputs);
                        enrichedContext.add(0, feedbackContext);
                        contextOutputs = enrichedContext;
                    }

                    TaskOutput output = task.execute(contextOutputs);

                    logger.info("Task completed (iteration {}): {} ({} chars, {} ms)",
                            iteration,
                            truncateForLog(task.getDescription(), 50),
                            output.getRawOutput() != null ? output.getRawOutput().length() : 0,
                            output.getExecutionTimeMs());

                    iterationOutputs.add(output);

                    // Record budget usage
                    BudgetTracker bt = inputs != null && inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
                    String bsId = inputs != null && inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                    recordBudgetUsage(bt, bsId, output, task.getAgent() != null ? task.getAgent().getModelName() : null);

                    publishEvent(SwarmEvent.Type.TASK_COMPLETED,
                            "Completed task: " + task.getId() + " (iteration " + iteration + ")", swarmId);
                }

                allOutputs.addAll(iterationOutputs);

                // Review the output
                String lastOutput = iterationOutputs.isEmpty() ? "" :
                        iterationOutputs.get(iterationOutputs.size() - 1).getRawOutput();

                Task reviewTask = createReviewTask(lastOutput, iteration, tasks);

                ObservabilityContext ctx = ObservabilityContext.currentOrNull();
                if (ctx != null) {
                    ctx.withTaskId(reviewTask.getId());
                    ctx.withAgentId(reviewerAgent.getId());
                }

                TaskOutput reviewOutput = reviewerAgent.executeTask(reviewTask, Collections.emptyList());
                allOutputs.add(reviewOutput);

                // Record reviewer budget usage
                BudgetTracker rbt = inputs != null && inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
                String rbsId = inputs != null && inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                recordBudgetUsage(rbt, rbsId, reviewOutput, reviewerAgent.getModelName());

                String reviewText = reviewOutput.getRawOutput() != null ? reviewOutput.getRawOutput() : "";

                if (isApproved(reviewText)) {
                    approved = true;
                    logger.info("Iteration {}: APPROVED by reviewer", iteration);
                    publishEvent(SwarmEvent.Type.ITERATION_REVIEW_PASSED,
                            "Iteration " + iteration + " approved by reviewer", swarmId,
                            Map.of("iteration", iteration));
                } else {
                    reviewFeedback = extractFeedback(reviewText);
                    logger.info("Iteration {}: NEEDS_REFINEMENT - feedback: {}",
                            iteration, truncateForLog(reviewFeedback, 200));
                    publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                            "Iteration " + iteration + " needs refinement", swarmId,
                            Map.of("iteration", iteration, "feedback", truncateForLog(reviewFeedback, 500)));
                }

                publishEvent(SwarmEvent.Type.ITERATION_COMPLETED,
                        String.format("Iteration %d completed (approved: %s)", iteration, approved), swarmId,
                        Map.of("iteration", iteration, "approved", approved));
            }

            if (!approved) {
                logger.warn("Iterative Process: Max iterations ({}) reached without full approval", maxIterations);
            }

            LocalDateTime endTime = LocalDateTime.now();

            // Final output is the last worker task output (not the review)
            String finalOutput = findLastWorkerOutput(allOutputs);

            return SwarmOutput.builder()
                    .swarmId(swarmId)
                    .taskOutputs(allOutputs)
                    .finalOutput(finalOutput)
                    .rawOutput(finalOutput)
                    .startTime(startTime)
                    .endTime(endTime)
                    .successful(allOutputs.stream()
                            .filter(o -> !"reviewer-feedback".equals(o.getTaskId()))
                            .allMatch(TaskOutput::isSuccessful))
                    .usageMetric("totalTasks", allOutputs.size())
                    .usageMetric("iterations", iteration)
                    .usageMetric("maxIterations", maxIterations)
                    .usageMetric("approved", approved)
                    .build();

        } catch (Exception e) {
            publishEvent(SwarmEvent.Type.PROCESS_FAILED, "Iterative process failed: " + e.getMessage(), swarmId);
            throw new RuntimeException("Iterative process execution failed", e);
        }
    }

    private Task createReviewTask(String outputToReview, int iteration, List<Task> tasks) {
        StringBuilder description = new StringBuilder();
        description.append("You are reviewing the output of iteration ").append(iteration).append(".\n\n");

        description.append("TASK OBJECTIVES:\n");
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            String taskDesc = task.getDescription();
            if (taskDesc.length() > 300) {
                taskDesc = taskDesc.substring(0, 300) + "...";
            }
            description.append(i + 1).append(". ").append(taskDesc).append("\n");
            if (task.getExpectedOutput() != null) {
                description.append("   Expected: ").append(task.getExpectedOutput()).append("\n");
            }
        }

        if (qualityCriteria != null && !qualityCriteria.isEmpty()) {
            description.append("\nQUALITY CRITERIA:\n").append(qualityCriteria).append("\n");
        }

        description.append("\nOUTPUT TO REVIEW:\n");
        if (outputToReview != null && outputToReview.length() > 5000) {
            description.append(outputToReview, 0, 5000).append("\n...[truncated]\n");
        } else {
            description.append(outputToReview != null ? outputToReview : "No output").append("\n");
        }

        description.append("\nINSTRUCTIONS:\n");
        description.append("Evaluate the output against the task objectives");
        if (qualityCriteria != null) {
            description.append(" and quality criteria");
        }
        description.append(".\n\n");
        description.append("Your response MUST start with one of these verdicts:\n");
        description.append("- APPROVED - if the output meets all objectives and is of sufficient quality\n");
        description.append("- NEEDS_REFINEMENT - if the output needs improvement\n\n");
        description.append("If NEEDS_REFINEMENT, provide specific, actionable feedback:\n");
        description.append("1. What specific issues need to be fixed\n");
        description.append("2. What is missing or incomplete\n");
        description.append("3. What should be changed or improved\n");
        description.append("Do NOT rewrite the content yourself - just provide the feedback.\n");

        return Task.builder()
                .id("review-iteration-" + iteration)
                .description(description.toString())
                .expectedOutput("A verdict (APPROVED or NEEDS_REFINEMENT) with specific feedback if refinement is needed")
                .agent(reviewerAgent)
                .build();
    }

    /**
     * Determines if the reviewer approved the output.
     * Checks the first line/paragraph for the verdict to avoid false positives
     * from quoted text in the feedback body.
     */
    private boolean isApproved(String reviewText) {
        if (reviewText == null || reviewText.isEmpty()) {
            return false;
        }
        // Check the first 200 chars for the verdict — the reviewer is instructed to lead with it
        String header = reviewText.substring(0, Math.min(200, reviewText.length())).toUpperCase();
        // NEEDS_REFINEMENT takes priority if it appears before APPROVED
        int approvedIdx = header.indexOf(APPROVED);
        int refinementIdx = header.indexOf(NEEDS_REFINEMENT);
        if (approvedIdx >= 0 && (refinementIdx < 0 || approvedIdx < refinementIdx)) {
            return true;
        }
        return false;
    }

    private String extractFeedback(String reviewText) {
        if (reviewText == null || reviewText.isEmpty()) {
            return "No specific feedback provided";
        }
        // The entire review text serves as feedback, including the verdict line
        return reviewText;
    }

    private String findLastWorkerOutput(List<TaskOutput> allOutputs) {
        // Walk backwards to find the last non-review output
        for (int i = allOutputs.size() - 1; i >= 0; i--) {
            TaskOutput output = allOutputs.get(i);
            String taskId = output.getTaskId();
            if (taskId != null && !taskId.startsWith("review-iteration-") && !taskId.equals("reviewer-feedback")) {
                return output.getRawOutput();
            }
        }
        return allOutputs.isEmpty() ? "No outputs generated" :
                allOutputs.get(allOutputs.size() - 1).getRawOutput();
    }

    private List<Task> orderTasks(List<Task> tasks) {
        List<Task> ordered = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Set<String> queued = new HashSet<>();

        Queue<Task> queue = new LinkedList<>();

        for (Task task : tasks) {
            if (task.getDependencyTaskIds().isEmpty()) {
                queue.offer(task);
                queued.add(task.getId());
            }
        }

        while (!queue.isEmpty()) {
            Task current = queue.poll();
            ordered.add(current);
            processed.add(current.getId());

            for (Task task : tasks) {
                if (!queued.contains(task.getId()) &&
                        processed.containsAll(task.getDependencyTaskIds())) {
                    queue.offer(task);
                    queued.add(task.getId());
                }
            }
        }

        if (ordered.size() != tasks.size()) {
            throw new IllegalStateException("Circular dependency detected in tasks");
        }

        return ordered;
    }

    private List<TaskOutput> getContextForTask(Task task, List<TaskOutput> allOutputs) {
        if (task.getDependencyTaskIds().isEmpty()) {
            return allOutputs;
        }
        return allOutputs.stream()
                .filter(output -> task.getDependencyTaskIds().contains(output.getTaskId()))
                .collect(Collectors.toList());
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId) {
        publishEvent(type, message, swarmId, Map.of());
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId, Map<String, Object> metadata) {
        if (eventPublisher != null) {
            SwarmEvent event = new SwarmEvent(this, type, message, swarmId, metadata);
            eventPublisher.publishEvent(event);
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.ITERATIVE;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("Tasks list cannot be empty");
        }

        Set<String> allTaskIds = tasks.stream()
                .map(Task::getId)
                .collect(Collectors.toSet());

        for (Task task : tasks) {
            for (String depId : task.getDependencyTaskIds()) {
                if (!allTaskIds.contains(depId)) {
                    throw new IllegalArgumentException(
                            taskLabel(task) + " depends on non-existent task id=" + depId);
                }
            }
        }

        if (reviewerAgent == null) {
            throw new IllegalArgumentException("Reviewer agent is required for iterative process");
        }
    }

    private String taskLabel(Task task) {
        String description = task.getDescription();
        String descriptionLabel = (description == null || description.isBlank())
                ? ""
                : " \"" + truncateForLog(description, 60) + "\"";
        String agentRoleLabel = task.getAgent() == null || task.getAgent().getRole() == null
                ? ""
                : " [agentRole=" + task.getAgent().getRole() + "]";
        return "Task" + descriptionLabel + " (id=" + task.getId() + ")" + agentRoleLabel;
    }

    private String truncateForLog(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
}
