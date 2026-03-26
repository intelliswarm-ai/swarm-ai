package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.skill.*;
import ai.intelliswarm.swarmai.swarm.SwarmOutput;
import ai.intelliswarm.swarmai.task.Task;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Self-Improving Process — extends the iterative review loop with dynamic skill generation.
 *
 * The loop:
 * 1. Execute tasks with current tools
 * 2. Reviewer evaluates → ReviewResult with QUALITY_ISSUES and CAPABILITY_GAPS
 * 3. If CAPABILITY_GAPS found: generate new skills → validate → register → rebuild agents
 * 4. If only QUALITY_ISSUES: inject feedback (standard iterative behavior)
 * 5. Re-execute with expanded toolkit. Repeat until APPROVED or max iterations.
 */
public class SelfImprovingProcess implements Process {

    private static final Logger logger = LoggerFactory.getLogger(SelfImprovingProcess.class);

    private final List<Agent> originalAgents;
    private final Agent reviewerAgent;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxIterations;
    private final String qualityCriteria;

    private static final Path DEFAULT_SKILLS_DIR = Paths.get("output", "skills");

    private final SkillRegistry skillRegistry;
    private final SkillValidator skillValidator;
    private List<Agent> currentAgents;

    public SelfImprovingProcess(List<Agent> agents, Agent reviewerAgent,
                                 ApplicationEventPublisher eventPublisher,
                                 int maxIterations, String qualityCriteria) {
        this.originalAgents = new ArrayList<>(agents);
        this.reviewerAgent = reviewerAgent;
        this.eventPublisher = eventPublisher;
        this.maxIterations = maxIterations;
        this.qualityCriteria = qualityCriteria;
        this.skillRegistry = new SkillRegistry();
        this.skillValidator = new SkillValidator();
        this.currentAgents = new ArrayList<>(agents);

        // Load previously generated skills from disk
        loadPersistedSkills();
    }

    /**
     * Load previously generated and validated skills from disk.
     * Skills are saved in output/skills/ as JSON files.
     * Wires up cross-skill references so composed skills can call each other.
     */
    private void loadPersistedSkills() {
        int loaded = skillRegistry.load(DEFAULT_SKILLS_DIR);
        if (loaded > 0) {
            logger.info("Loaded {} persisted skills (deduplicated to {})",
                loaded, skillRegistry.size());

            // Build complete tools map: agent tools + all loaded skills
            Map<String, BaseTool> allToolsMap = currentAgents.stream()
                .flatMap(a -> a.getTools().stream())
                .collect(Collectors.toMap(BaseTool::getFunctionName, t -> t, (a, b) -> a));

            // Add loaded skills to the tools map so they can reference each other
            for (GeneratedSkill skill : skillRegistry.getActiveSkills()) {
                allToolsMap.put(skill.getFunctionName(), skill);
            }

            // Inject the complete tools map into each skill and rebuild agents
            for (GeneratedSkill skill : skillRegistry.getActiveSkills()) {
                skill.setAvailableTools(allToolsMap);
                rebuildAgentsWithSkill(skill);
            }
        }
    }

    @Override
    public ProcessType getType() {
        return ProcessType.SELF_IMPROVING;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public void validateTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("At least one task is required");
        }
        if (reviewerAgent == null) {
            throw new IllegalArgumentException("Reviewer agent is required for self-improving process");
        }
    }

    @Override
    public SwarmOutput execute(List<Task> tasks, Map<String, Object> inputs, String swarmId) {
        validateTasks(tasks);

        logger.info("Self-Improving Process: Starting (max {} iterations)", maxIterations);
        publishEvent(SwarmEvent.Type.PROCESS_STARTED,
            "Self-improving process execution started", swarmId, Map.of());

        // Order tasks by dependencies
        List<Task> orderedTasks = orderTasks(tasks);

        // Interpolate inputs
        for (Task task : orderedTasks) {
            if (inputs != null) {
                String interpolated = interpolateInputs(task.getDescription(), inputs);
                // Task descriptions are already set via builder
            }
        }

        List<TaskOutput> allOutputs = new ArrayList<>();
        int iteration = 0;
        boolean approved = false;
        String reviewFeedback = null;
        int skillsGenerated = 0;

        while (iteration < maxIterations && !approved) {
            iteration++;
            logger.info("Self-Improving Process: Iteration {}/{}", iteration, maxIterations);
            publishEvent(SwarmEvent.Type.ITERATION_STARTED,
                "Iteration " + iteration + "/" + maxIterations + " started", swarmId,
                Map.of("iteration", iteration, "skillsAvailable", skillRegistry.size()));

            // Reset tasks for re-execution (except first iteration)
            if (iteration > 1) {
                for (Task task : orderedTasks) {
                    task.reset();
                }
            }

            // 1. Execute all tasks with current agents
            List<TaskOutput> iterationOutputs = new ArrayList<>();
            List<TaskOutput> contextOutputs = new ArrayList<>();

            // Inject reviewer feedback from previous iteration
            if (reviewFeedback != null) {
                TaskOutput feedbackContext = TaskOutput.builder()
                    .taskId("reviewer-feedback")
                    .rawOutput("REVIEWER FEEDBACK FROM PREVIOUS ITERATION:\n" + reviewFeedback)
                    .build();
                contextOutputs.add(feedbackContext);
            }

            for (Task task : orderedTasks) {
                // Find the current agent for this task (may have been rebuilt with new tools)
                Agent taskAgent = findCurrentAgent(task.getAgent());
                // Execute with context from prior tasks
                try {
                    publishEvent(SwarmEvent.Type.TASK_STARTED,
                        "Starting task: " + task.getId() + " (iteration " + iteration + ")", swarmId, Map.of());

                    TaskOutput output = taskAgent.executeTask(task, contextOutputs);
                    iterationOutputs.add(output);
                    contextOutputs.add(output);

                    // Save to file if task has outputFile configured
                    if (task.getOutputFile() != null) {
                        try {
                            java.nio.file.Path path = java.nio.file.Path.of(task.getOutputFile());
                            if (path.getParent() != null) {
                                java.nio.file.Files.createDirectories(path.getParent());
                            }
                            java.nio.file.Files.writeString(path, output.getRawOutput() != null ? output.getRawOutput() : "",
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            logger.info("Task output saved to: {}", task.getOutputFile());
                        } catch (Exception fileErr) {
                            logger.warn("Failed to save output to {}: {}", task.getOutputFile(), fileErr.getMessage());
                        }
                    }

                    logger.info("Task completed (iteration {}): {} ({} chars, {} ms)",
                        iteration, truncate(task.getDescription(), 50),
                        output.getRawOutput().length(), output.getExecutionTimeMs());

                    publishEvent(SwarmEvent.Type.TASK_COMPLETED,
                        "Completed task: " + task.getId() + " (iteration " + iteration + ")", swarmId, Map.of());

                } catch (Exception e) {
                    logger.error("Task failed (iteration {}): {}", iteration, e.getMessage());
                    TaskOutput errorOutput = TaskOutput.builder()
                        .taskId(task.getId())
                        .rawOutput("Error: " + e.getMessage())
                        .build();
                    iterationOutputs.add(errorOutput);
                    contextOutputs.add(errorOutput);
                }
            }

            allOutputs = new ArrayList<>(iterationOutputs);

            // 2. Reviewer evaluates
            String outputToReview = iterationOutputs.stream()
                .map(TaskOutput::getRawOutput)
                .collect(Collectors.joining("\n\n---\n\n"));

            Task reviewTask = createReviewTask(outputToReview, iteration, tasks);
            TaskOutput reviewOutput = reviewerAgent.executeTask(reviewTask, Collections.emptyList());
            String reviewText = reviewOutput.getRawOutput();

            // 3. Parse structured review
            ReviewResult review = ReviewResult.parse(reviewText);

            if (review.approved()) {
                approved = true;
                logger.info("Iteration {}: APPROVED by reviewer", iteration);
                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_PASSED,
                    "Iteration " + iteration + " approved", swarmId, Map.of());

            } else if (review.hasCapabilityGaps()) {
                // 4. CAPABILITY GAPS — generate new skills
                logger.info("Iteration {}: {} capability gaps detected", iteration, review.capabilityGaps().size());

                for (String gap : review.capabilityGaps()) {
                    logger.info("Generating skill for gap: {}", gap);

                    // Get existing tool names to avoid duplicates
                    List<String> existingNames = currentAgents.stream()
                        .flatMap(a -> a.getTools().stream())
                        .map(BaseTool::getFunctionName)
                        .distinct()
                        .collect(Collectors.toList());

                    // Collect existing tools as a map so generated skills can compose them
                    Map<String, BaseTool> existingToolsMap = currentAgents.stream()
                        .flatMap(a -> a.getTools().stream())
                        .collect(Collectors.toMap(BaseTool::getFunctionName, t -> t, (a, b) -> a));

                    // Generate skill using the reviewer's ChatClient
                    // Dynamic tool discovery: probe tools to learn their output formats
                    SkillGenerator generator = new SkillGenerator(reviewerAgent.getChatClient());
                    generator.discoverToolFormats(existingToolsMap);
                    GeneratedSkill skill = generator.generate(gap, existingNames);

                    if (skill != null) {
                        // Inject existing tools so validation can succeed
                        skill.setAvailableTools(existingToolsMap);

                        // Validate
                        SkillValidator.ValidationResult validation = skillValidator.validate(skill);

                        if (validation.passed()) {
                            skill.setStatus(SkillStatus.VALIDATED);
                            skill.setAvailableTools(existingToolsMap); // Allow composing existing tools
                            skillRegistry.register(skill);
                            skillsGenerated++;

                            logger.info("New skill validated and registered: {} ({})",
                                skill.getName(), skill.getId());

                            // Rebuild agents with new skill
                            rebuildAgentsWithSkill(skill);

                        } else {
                            logger.warn("Skill '{}' failed validation: {}",
                                skill.getName(), validation.errorsAsString());

                            // Try to refine once
                            GeneratedSkill refined = generator.refine(skill, validation.errorsAsString());
                            if (refined != null) {
                                refined.setAvailableTools(existingToolsMap);
                                SkillValidator.ValidationResult retryValidation = skillValidator.validate(refined);
                                if (retryValidation.passed()) {
                                    refined.setStatus(SkillStatus.VALIDATED);
                                    refined.setAvailableTools(existingToolsMap);
                                    skillRegistry.register(refined);
                                    skillsGenerated++;
                                    logger.info("Refined skill validated: {}", refined.getName());
                                    rebuildAgentsWithSkill(refined);
                                } else {
                                    logger.warn("Refined skill also failed. Skipping gap: {}", gap);
                                }
                            }
                        }
                    }
                }

                // Also pass quality feedback
                reviewFeedback = reviewText;

                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                    "Iteration " + iteration + " needs refinement (capability gaps)", swarmId,
                    Map.of("gaps", review.capabilityGaps().size(), "skillsGenerated", skillsGenerated));

            } else {
                // 5. QUALITY ISSUES only — standard feedback
                reviewFeedback = reviewText;
                logger.info("Iteration {}: NEEDS_REFINEMENT (quality issues only)", iteration);

                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                    "Iteration " + iteration + " needs refinement", swarmId, Map.of());
            }

            publishEvent(SwarmEvent.Type.ITERATION_COMPLETED,
                "Iteration " + iteration + " completed (approved: " + approved + ")", swarmId,
                Map.of("approved", approved, "iteration", iteration));
        }

        if (!approved) {
            logger.warn("Self-Improving Process: Max iterations ({}) reached without approval", maxIterations);
        }

        // Build final output with metadata set via builder (getMetadata() returns a defensive copy)
        SwarmOutput.Builder outputBuilder = SwarmOutput.builder()
            .swarmId(swarmId)
            .successful(approved || !allOutputs.isEmpty())
            .taskOutputs(allOutputs)
            .metadata("skillsGenerated", skillsGenerated)
            .metadata("totalIterations", iteration)
            .metadata("registryStats", skillRegistry.getStats());

        if (!allOutputs.isEmpty()) {
            outputBuilder.finalOutput(allOutputs.get(allOutputs.size() - 1).getRawOutput());
        }

        SwarmOutput output = outputBuilder.build();

        // Persist generated skills to disk for reuse in future runs
        if (skillsGenerated > 0) {
            try {
                skillRegistry.save(DEFAULT_SKILLS_DIR);
                logger.info("Persisted {} skills to {}", skillRegistry.size(), DEFAULT_SKILLS_DIR);
            } catch (Exception e) {
                logger.warn("Failed to persist skills: {}", e.getMessage());
            }
        }

        logger.info("Self-Improving Process complete: {} iterations, {} skills generated, approved={}",
            iteration, skillsGenerated, approved);

        return output;
    }

    /**
     * Rebuild all current agents with a new skill added to their toolkit.
     */
    private void rebuildAgentsWithSkill(GeneratedSkill skill) {
        skill.setStatus(SkillStatus.ACTIVE);
        List<Agent> rebuilt = new ArrayList<>();
        for (Agent agent : currentAgents) {
            rebuilt.add(agent.withAdditionalTools(List.of(skill)));
        }
        currentAgents = rebuilt;
        logger.info("Rebuilt {} agents with new skill: {}", rebuilt.size(), skill.getName());
    }

    /**
     * Find the current version of an agent (may have been rebuilt with new tools).
     */
    private Agent findCurrentAgent(Agent originalAgent) {
        for (Agent current : currentAgents) {
            if (current.getRole().equals(originalAgent.getRole())) {
                return current;
            }
        }
        return originalAgent;
    }

    /**
     * Create the review task with enhanced gap-detection prompting.
     */
    private Task createReviewTask(String outputToReview, int iteration, List<Task> tasks) {
        String taskObjectives = tasks.stream()
            .map(t -> "- " + truncate(t.getDescription(), 100))
            .collect(Collectors.joining("\n"));

        String description = String.format(
            "You are reviewing the output of iteration %d.\n\n" +
            "TASK OBJECTIVES:\n%s\n\n" +
            "%s\n\n" +
            "OUTPUT TO REVIEW:\n%s\n\n" +
            "RESPOND IN THIS EXACT FORMAT:\n\n" +
            "VERDICT: APPROVED or NEEDS_REFINEMENT\n\n" +
            "QUALITY_ISSUES:\n" +
            "- [list any quality problems with the output]\n\n" +
            "CAPABILITY_GAPS:\n" +
            "- NO_TOOL: [describe a missing tool capability that would improve the output]\n" +
            "- INSUFFICIENT_TOOL: [describe a tool that exists but doesn't work well enough]\n\n" +
            "If the output fully meets all objectives, use VERDICT: APPROVED and omit the other sections.\n" +
            "If there are quality issues but no missing tools, include QUALITY_ISSUES but omit CAPABILITY_GAPS.\n" +
            "Only include CAPABILITY_GAPS if a NEW TOOL would genuinely help — not just prompt improvements.",
            iteration, taskObjectives,
            qualityCriteria != null ? "QUALITY CRITERIA:\n" + qualityCriteria : "",
            outputToReview.length() > 8000 ? outputToReview.substring(0, 8000) + "\n[... truncated ...]" : outputToReview
        );

        return Task.builder()
            .id("review-iteration-" + iteration)
            .description(description)
            .expectedOutput("Structured review with VERDICT, QUALITY_ISSUES, and optional CAPABILITY_GAPS")
            .agent(reviewerAgent)
            .maxExecutionTime(120000)
            .build();
    }

    private List<Task> orderTasks(List<Task> tasks) {
        // Simple dependency ordering (same as SequentialProcess)
        List<Task> ordered = new ArrayList<>();
        Set<String> completed = new HashSet<>();
        List<Task> remaining = new ArrayList<>(tasks);

        while (!remaining.isEmpty()) {
            boolean progress = false;
            Iterator<Task> it = remaining.iterator();
            while (it.hasNext()) {
                Task task = it.next();
                if (task.isReady(completed)) {
                    ordered.add(task);
                    completed.add(task.getId());
                    it.remove();
                    progress = true;
                }
            }
            if (!progress) {
                ordered.addAll(remaining);
                break;
            }
        }

        return ordered;
    }

    private void publishEvent(SwarmEvent.Type type, String message, String swarmId, Map<String, Object> metadata) {
        if (eventPublisher != null) {
            Map<String, Object> meta = new HashMap<>(metadata);
            meta.put("processType", "SELF_IMPROVING");
            eventPublisher.publishEvent(new SwarmEvent(this, type, message, swarmId, meta));
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    // Expose registry for external access
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }
}
