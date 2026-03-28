package ai.intelliswarm.swarmai.process;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.budget.BudgetSnapshot;
import ai.intelliswarm.swarmai.budget.BudgetTracker;
import ai.intelliswarm.swarmai.event.SwarmEvent;
import ai.intelliswarm.swarmai.memory.Memory;
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

    /** Hard safety cap — never iterate more than this regardless of convergence. */
    private static final int ABSOLUTE_MAX_ITERATIONS = 10;

    private final List<Agent> originalAgents;
    private final Agent reviewerAgent;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxIterations;
    private final String qualityCriteria;

    private static final Path DEFAULT_SKILLS_DIR = Paths.get("output", "skills");

    /** Similarity threshold for skill deduplication (0.0 - 1.0). */
    private static final double SKILL_SIMILARITY_THRESHOLD = 0.35;

    /** Maximum generated skills to load per agent to prevent context bloat. */
    private static final int MAX_SKILLS_PER_AGENT = 20;

    private final SkillRegistry skillRegistry;
    private final SkillValidator skillValidator;
    private final SkillGapAnalyzer gapAnalyzer;
    private final Memory memory;
    private List<Agent> currentAgents;
    private int skillsReused = 0;

    public SelfImprovingProcess(List<Agent> agents, Agent reviewerAgent,
                                 ApplicationEventPublisher eventPublisher,
                                 int maxIterations, String qualityCriteria) {
        this(agents, reviewerAgent, eventPublisher, maxIterations, qualityCriteria, null);
    }

    public SelfImprovingProcess(List<Agent> agents, Agent reviewerAgent,
                                 ApplicationEventPublisher eventPublisher,
                                 int maxIterations, String qualityCriteria,
                                 Memory memory) {
        this.originalAgents = new ArrayList<>(agents);
        this.reviewerAgent = reviewerAgent;
        this.eventPublisher = eventPublisher;
        this.maxIterations = maxIterations;
        this.qualityCriteria = qualityCriteria;
        this.memory = memory;
        this.skillRegistry = new SkillRegistry();
        this.skillValidator = new SkillValidator();
        this.gapAnalyzer = new SkillGapAnalyzer();
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

        int effectiveMaxIterations = maxIterations > 0 ? Math.min(maxIterations, ABSOLUTE_MAX_ITERATIONS) : ABSOLUTE_MAX_ITERATIONS;
        logger.info("Self-Improving Process: Starting (max {} iterations, auto-stop on convergence)", effectiveMaxIterations);

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

        // Convergence tracking
        int previousOutputLength = 0;
        Set<String> previousGaps = new HashSet<>();
        int staleIterations = 0;

        // Cross-iteration gap memory — prevents the same gap from being processed repeatedly
        List<String> processedGapDescriptions = new ArrayList<>();
        int gapsSkippedAsDuplicate = 0;

        while (iteration < effectiveMaxIterations && !approved) {
            iteration++;

            // Check budget-based stopping (leave 10% headroom for final output)
            BudgetTracker budgetTracker = inputs != null && inputs.get("__budgetTracker") instanceof BudgetTracker bt ? bt : null;
            String budgetSwarmId = inputs != null && inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
            if (budgetTracker != null) {
                BudgetSnapshot snap = budgetTracker.getSnapshot(budgetSwarmId);
                if (snap != null && (snap.tokenUtilizationPercent() > 90.0 || snap.costUtilizationPercent() > 90.0)) {
                    logger.info("Self-Improving Process: Stopping — budget utilization at {}% tokens / {}% cost",
                        String.format("%.1f", snap.tokenUtilizationPercent()),
                        String.format("%.1f", snap.costUtilizationPercent()));
                    break;
                }
            }

            logger.info("Self-Improving Process: Iteration {}/{}", iteration, effectiveMaxIterations);
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

                    // Record budget usage
                    BudgetTracker bt = inputs != null && inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
                    String bsId = inputs != null && inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
                    recordBudgetUsage(bt, bsId, output, taskAgent.getModelName());

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

            // 2a. Tool-evidence quality check — detect if agents used tools or relied on LLM knowledge
            String outputToReview = iterationOutputs.stream()
                .map(TaskOutput::getRawOutput)
                .collect(Collectors.joining("\n\n---\n\n"));

            String toolEvidenceWarning = checkToolEvidence(outputToReview);
            if (toolEvidenceWarning != null) {
                logger.warn("Iteration {}: Tool evidence check: {}", iteration, toolEvidenceWarning);
                // Prepend the warning to the review input so the reviewer sees it
                outputToReview = "TOOL USAGE WARNING: " + toolEvidenceWarning +
                    "\nThe reviewer should flag this as a QUALITY ISSUE, not a CAPABILITY GAP.\n\n" +
                    outputToReview;
            }

            // 2b. Reviewer evaluates

            Task reviewTask = createReviewTask(outputToReview, iteration, tasks);
            TaskOutput reviewOutput = reviewerAgent.executeTask(reviewTask, Collections.emptyList());
            String reviewText = reviewOutput.getRawOutput();

            // Record reviewer budget usage
            BudgetTracker bt2 = inputs != null && inputs.get("__budgetTracker") instanceof BudgetTracker b ? b : null;
            String bsId2 = inputs != null && inputs.get("__budgetSwarmId") instanceof String s ? s : swarmId;
            recordBudgetUsage(bt2, bsId2, reviewOutput, reviewerAgent.getModelName());

            // 3. Parse structured review
            ReviewResult review = ReviewResult.parse(reviewText);

            if (review.approved()) {
                approved = true;
                logger.info("Iteration {}: APPROVED by reviewer", iteration);
                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_PASSED,
                    "Iteration " + iteration + " approved", swarmId, Map.of());

            } else if (review.hasCapabilityGaps()) {
                // 4. CAPABILITY GAPS — pre-filter: reclassify tool-error gaps as quality issues
                List<String> genuineGaps = new ArrayList<>();
                List<String> reclassifiedAsQuality = new ArrayList<>();

                for (String gap : review.capabilityGaps()) {
                    String gapLower = gap.toLowerCase();
                    boolean isToolError = gapLower.contains("i/o error") || gapLower.contains("io error") ||
                        gapLower.contains("connection refused") || gapLower.contains("connection timed out") ||
                        gapLower.contains("unknownhostexception") || gapLower.contains("status=404") ||
                        gapLower.contains("status=403") || gapLower.contains("http error") ||
                        (gapLower.contains("failed") && (gapLower.contains("api") || gapLower.contains("request")) &&
                         (gapLower.contains("error") || gapLower.contains("i/o")));

                    if (isToolError) {
                        reclassifiedAsQuality.add(gap);
                    } else {
                        genuineGaps.add(gap);
                    }
                }

                if (!reclassifiedAsQuality.isEmpty()) {
                    logger.info("Iteration {}: Reclassified {}/{} gaps as quality issues (tool errors, not missing capabilities)",
                        iteration, reclassifiedAsQuality.size(), review.capabilityGaps().size());
                    for (String reclassified : reclassifiedAsQuality) {
                        logger.info("  RECLASSIFIED (tool error): {}", truncate(reclassified, 100));
                    }
                }

                if (genuineGaps.isEmpty()) {
                    // All gaps were tool errors — treat as quality issues only
                    reviewFeedback = reviewText +
                        "\n\nNOTE: The reviewer flagged tool errors as capability gaps, but these are quality issues. " +
                        "The agents should use REAL URLs (not placeholder domains) and try different data sources.";
                    logger.info("Iteration {}: All {} gaps reclassified as quality issues — no skills to generate",
                        iteration, review.capabilityGaps().size());
                    publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                        "Iteration " + iteration + " needs refinement (quality issues, gaps reclassified)", swarmId, Map.of());
                    continue; // skip to next iteration with quality feedback
                }

                logger.info("Iteration {}: {} genuine capability gaps (out of {} total)",
                    iteration, genuineGaps.size(), review.capabilityGaps().size());

                // Collect tools once for all gaps in this iteration
                List<String> existingNames = currentAgents.stream()
                    .flatMap(a -> a.getTools().stream())
                    .map(BaseTool::getFunctionName)
                    .distinct()
                    .collect(Collectors.toList());

                Map<String, BaseTool> existingToolsMap = currentAgents.stream()
                    .flatMap(a -> a.getTools().stream())
                    .collect(Collectors.toMap(BaseTool::getFunctionName, t -> t, (a, b) -> a));

                // Create generator once and discover formats once (cached, skips generated skills)
                SkillGenerator generator = new SkillGenerator(reviewerAgent.getChatClient());
                generator.discoverToolFormats(existingToolsMap);

                for (String gap : genuineGaps) {
                    // --- Semantic Deduplication: check for existing similar skills ---
                    GeneratedSkill reusedSkill = findReusableSkill(gap, swarmId);
                    if (reusedSkill != null) {
                        // Skill already exists — reuse it if not already in agent toolkit
                        if (!existingNames.contains(reusedSkill.getFunctionName())) {
                            reusedSkill.setAvailableTools(existingToolsMap);
                            rebuildAgentsWithSkill(reusedSkill);
                            existingNames.add(reusedSkill.getFunctionName());
                        }
                        skillsReused++;
                        saveToMemory("skill-reuse", String.format(
                            "Reused skill '%s' for gap: %s (effectiveness: %.0f%%)",
                            reusedSkill.getName(), truncate(gap, 100), reusedSkill.getEffectiveness() * 100),
                            Map.of("skillName", reusedSkill.getName(), "gap", gap));
                        continue;
                    }

                    // --- Gap Quality Analysis: should we generate a skill at all? ---
                    List<BaseTool> allExistingTools = existingToolsMap.values().stream()
                        .collect(java.util.stream.Collectors.toList());
                    SkillGapAnalyzer.GapAnalysis gapAnalysis = gapAnalyzer.analyze(
                        gap, allExistingTools, skillRegistry);

                    if (!gapAnalysis.shouldGenerate()) {
                        logger.info("Gap analysis REJECTED skill generation for: {} (recommendation={}, score={:.2f}, reasons={})",
                            truncate(gap, 60), gapAnalysis.recommendation(), gapAnalysis.score(), gapAnalysis.reasons());

                        publishEvent(SwarmEvent.Type.SKILL_GENERATION_SKIPPED,
                            "Skill generation skipped: " + truncate(gap, 80), swarmId,
                            Map.of("gap", gap, "recommendation", gapAnalysis.recommendation().name(),
                                   "reasons", gapAnalysis.reasons()));

                        saveToMemory("skill-skipped", String.format(
                            "Skipped skill generation for gap: %s (reason: %s, score: %.2f)",
                            truncate(gap, 100), gapAnalysis.recommendation(), gapAnalysis.score()),
                            Map.of("gap", gap, "recommendation", gapAnalysis.recommendation().name()));
                        continue;
                    }

                    logger.info("Gap analysis APPROVED skill generation: {} (type={}, score={:.2f})",
                        truncate(gap, 60), gapAnalysis.recommendedType(), gapAnalysis.score());

                    // --- Cross-iteration dedup: skip if a very similar gap was already processed ---
                    boolean isDuplicateGap = false;
                    Set<String> gapTokens = tokenizeForDedup(gap);
                    for (String processed : processedGapDescriptions) {
                        Set<String> processedTokens = tokenizeForDedup(processed);
                        double overlap = jaccardSimilaritySimple(gapTokens, processedTokens);
                        if (overlap > 0.35) {
                            isDuplicateGap = true;
                            gapsSkippedAsDuplicate++;
                            logger.info("Gap SKIPPED (duplicate of previously processed gap, overlap={:.0f}%): {}",
                                overlap * 100, truncate(gap, 80));
                            break;
                        }
                    }
                    if (isDuplicateGap) continue;

                    // Track this gap as processed (whether generation succeeds or fails)
                    processedGapDescriptions.add(gap);

                    // --- Query memory for hints about similar past gaps ---
                    String memoryHint = queryMemoryForGap(gap);

                    logger.info("Generating {} skill for gap: {}", gapAnalysis.recommendedType(), gap);

                    GeneratedSkill skill = generator.generate(
                        memoryHint != null ? gap + "\n\nHINT FROM PAST RUNS:\n" + memoryHint : gap,
                        existingNames);

                    if (skill != null) {
                        publishEvent(SwarmEvent.Type.SKILL_GENERATED,
                            "Generated skill: " + skill.getName(), swarmId,
                            Map.of("skillName", skill.getName(), "gap", gap));

                        // Inject existing tools so validation can succeed
                        skill.setAvailableTools(existingToolsMap);

                        // Validate
                        SkillValidator.ValidationResult validation = skillValidator.validate(skill);

                        if (validation.passed()) {
                            skill.setStatus(SkillStatus.VALIDATED);
                            skill.setAvailableTools(existingToolsMap);
                            skillRegistry.register(skill);
                            skillsGenerated++;

                            publishEvent(SwarmEvent.Type.SKILL_VALIDATED,
                                "Skill validated: " + skill.getName(), swarmId,
                                Map.of("skillName", skill.getName(), "skillId", skill.getId()));
                            publishEvent(SwarmEvent.Type.SKILL_REGISTERED,
                                "Skill registered: " + skill.getName(), swarmId,
                                Map.of("skillName", skill.getName(), "skillId", skill.getId()));

                            logger.info("New skill validated and registered: {} ({})",
                                skill.getName(), skill.getId());

                            // Rebuild agents with new skill
                            rebuildAgentsWithSkill(skill);
                            existingNames.add(skill.getFunctionName());

                            // Persist to memory for future runs
                            saveToMemory("skill-generated", String.format(
                                "Generated skill '%s' for gap: %s",
                                skill.getName(), truncate(gap, 150)),
                                Map.of("skillName", skill.getName(), "gap", gap,
                                    "iteration", iteration));

                        } else {
                            logger.warn("Skill '{}' failed validation: {}",
                                skill.getName(), validation.errorsAsString());

                            publishEvent(SwarmEvent.Type.SKILL_VALIDATION_FAILED,
                                "Skill validation failed: " + skill.getName(), swarmId,
                                Map.of("skillName", skill.getName(), "errors", validation.errorsAsString()));

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

                                    publishEvent(SwarmEvent.Type.SKILL_VALIDATED,
                                        "Refined skill validated: " + refined.getName(), swarmId,
                                        Map.of("skillName", refined.getName(), "refined", true));
                                    publishEvent(SwarmEvent.Type.SKILL_REGISTERED,
                                        "Refined skill registered: " + refined.getName(), swarmId,
                                        Map.of("skillName", refined.getName(), "skillId", refined.getId()));

                                    logger.info("Refined skill validated: {}", refined.getName());
                                    rebuildAgentsWithSkill(refined);
                                    existingNames.add(refined.getFunctionName());

                                    saveToMemory("skill-refined", String.format(
                                        "Skill '%s' required refinement for gap: %s (original errors: %s)",
                                        refined.getName(), truncate(gap, 100), truncate(validation.errorsAsString(), 100)),
                                        Map.of("skillName", refined.getName(), "gap", gap));
                                } else {
                                    logger.warn("Refined skill also failed. Skipping gap: {}", gap);
                                    saveToMemory("skill-failed", String.format(
                                        "Failed to generate skill for gap: %s (errors: %s)",
                                        truncate(gap, 150), truncate(retryValidation.errorsAsString(), 100)),
                                        Map.of("gap", gap, "iteration", iteration));
                                }
                            }
                        }
                    }
                }

                // Also pass quality feedback
                reviewFeedback = reviewText;

                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                    "Iteration " + iteration + " needs refinement (capability gaps)", swarmId,
                    Map.of("gaps", review.capabilityGaps().size(),
                           "skillsGenerated", skillsGenerated,
                           "skillsReused", skillsReused));

            } else {
                // 5. QUALITY ISSUES only — standard feedback
                reviewFeedback = reviewText;
                logger.info("Iteration {}: NEEDS_REFINEMENT (quality issues only)", iteration);

                publishEvent(SwarmEvent.Type.ITERATION_REVIEW_FAILED,
                    "Iteration " + iteration + " needs refinement", swarmId, Map.of());
            }

            // ---- Convergence detection ----
            if (!approved) {
                // Check output growth: if output length didn't grow by >10%, we're stalling
                int currentOutputLength = allOutputs.stream()
                    .mapToInt(o -> o.getRawOutput() != null ? o.getRawOutput().length() : 0).sum();
                boolean outputGrew = previousOutputLength == 0 ||
                    currentOutputLength > previousOutputLength * 1.1;

                // Check gap repetition: if same gaps keep appearing, we're stuck
                Set<String> currentGaps = new HashSet<>();
                if (review != null && review.hasCapabilityGaps()) {
                    currentGaps.addAll(review.capabilityGaps());
                }
                boolean sameGaps = !currentGaps.isEmpty() && currentGaps.equals(previousGaps);

                if (!outputGrew || sameGaps) {
                    staleIterations++;
                } else {
                    staleIterations = 0;
                }

                previousOutputLength = currentOutputLength;
                previousGaps = currentGaps;

                if (staleIterations >= 2) {
                    logger.info("Self-Improving Process: Auto-stopping — no meaningful progress for {} iterations " +
                        "(output growth stalled: {}, repeated gaps: {})", staleIterations, !outputGrew, sameGaps);
                    break;
                }
            }

            // Memory flush: persist iteration outcome for future runs
            saveToMemory("iteration-outcome", String.format(
                "Iteration %d/%d: %s (skills generated: %d, skills reused: %d, registry size: %d)",
                iteration, effectiveMaxIterations, approved ? "APPROVED" : "NEEDS_REFINEMENT",
                skillsGenerated, skillsReused, skillRegistry.size()),
                Map.of("iteration", iteration, "approved", approved,
                       "skillsGenerated", skillsGenerated, "skillsReused", skillsReused));

            publishEvent(SwarmEvent.Type.ITERATION_COMPLETED,
                "Iteration " + iteration + " completed (approved: " + approved + ")", swarmId,
                Map.of("approved", approved, "iteration", iteration));
        }

        String stopReason;
        if (approved) {
            stopReason = "APPROVED by reviewer";
        } else if (staleIterations >= 2) {
            stopReason = "auto-stopped (convergence — no meaningful progress)";
        } else {
            stopReason = "max iterations (" + effectiveMaxIterations + ") reached";
        }
        logger.info("Self-Improving Process: Stopped — {}", stopReason);

        // Auto-promote skills that meet the effectiveness threshold
        int promoted = autoPromoteSkills(swarmId);

        // Build final output with metadata set via builder (getMetadata() returns a defensive copy)
        SwarmOutput.Builder outputBuilder = SwarmOutput.builder()
            .swarmId(swarmId)
            .successful(approved || !allOutputs.isEmpty())
            .taskOutputs(allOutputs)
            .metadata("skillsGenerated", skillsGenerated)
            .metadata("skillsReused", skillsReused)
            .metadata("gapsSkippedAsDuplicate", gapsSkippedAsDuplicate)
            .metadata("skillsPromoted", promoted)
            .metadata("totalIterations", iteration)
            .metadata("stopReason", stopReason)
            .metadata("registryStats", skillRegistry.getStats());

        if (!allOutputs.isEmpty()) {
            outputBuilder.finalOutput(allOutputs.get(allOutputs.size() - 1).getRawOutput());
        }

        SwarmOutput output = outputBuilder.build();

        // Persist generated skills to disk for reuse in future runs
        if (skillsGenerated > 0 || promoted > 0) {
            try {
                skillRegistry.save(DEFAULT_SKILLS_DIR);
                logger.info("Persisted {} skills to {}", skillRegistry.size(), DEFAULT_SKILLS_DIR);
            } catch (Exception e) {
                logger.warn("Failed to persist skills: {}", e.getMessage());
            }
        }

        logger.info("Self-Improving Process complete: {} iterations, {} skills generated, {} reused, {} promoted, approved={}",
            iteration, skillsGenerated, skillsReused, promoted, approved);

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

    // ==================== Semantic Skill Deduplication ====================

    /**
     * Search the registry for an existing skill that matches the gap description.
     * Returns the best match above the similarity threshold, or null if none found.
     */
    private GeneratedSkill findReusableSkill(String gap, String swarmId) {
        List<SkillRegistry.SimilarSkill> similar = skillRegistry.findSimilar(gap, SKILL_SIMILARITY_THRESHOLD);

        if (!similar.isEmpty()) {
            SkillRegistry.SimilarSkill best = similar.get(0);
            logger.info("Reusing existing skill '{}' for gap '{}' (similarity: {})",
                best.skill().getName(), truncate(gap, 60),
                String.format("%.2f", best.similarity()));

            publishEvent(SwarmEvent.Type.SKILL_REUSED,
                "Reused skill: " + best.skill().getName() + " (similarity: " +
                    String.format("%.0f%%", best.similarity() * 100) + ")",
                swarmId,
                Map.of("skillName", best.skill().getName(),
                       "similarity", best.similarity(),
                       "gap", gap));

            return best.skill();
        }

        return null;
    }

    // ==================== Memory Integration ====================

    /**
     * Save a learning to memory for cross-run persistence.
     */
    private void saveToMemory(String category, String content, Map<String, Object> metadata) {
        if (memory == null) return;
        try {
            Map<String, Object> enrichedMeta = new HashMap<>(metadata);
            enrichedMeta.put("category", category);
            enrichedMeta.put("processType", "SELF_IMPROVING");
            memory.save("self-improving-process", content, enrichedMeta);
        } catch (Exception e) {
            logger.debug("Failed to save to memory: {}", e.getMessage());
        }
    }

    /**
     * Query memory for past learnings relevant to a capability gap.
     * Returns a hint string if useful context is found, null otherwise.
     */
    private String queryMemoryForGap(String gap) {
        if (memory == null) return null;
        try {
            List<String> memories = memory.search(gap, 3);
            if (memories != null && !memories.isEmpty()) {
                String hint = memories.stream()
                    .map(m -> "- " + m)
                    .collect(Collectors.joining("\n"));
                logger.info("Found {} memory hints for gap: {}", memories.size(), truncate(gap, 50));
                return hint;
            }
        } catch (Exception e) {
            logger.debug("Memory query failed: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Skill Lifecycle ====================

    /**
     * Auto-promote skills that meet the effectiveness threshold.
     * Returns the number of skills promoted.
     */
    private int autoPromoteSkills(String swarmId) {
        int promoted = 0;
        for (GeneratedSkill skill : skillRegistry.getActiveSkills()) {
            if (skill.getStatus() == SkillStatus.ACTIVE && skill.meetsPromotionThreshold()) {
                skillRegistry.promote(skill.getId());
                promoted++;
                logger.info("Auto-promoted skill: {} (usage={}, effectiveness={}%)",
                    skill.getName(), skill.getUsageCount(),
                    String.format("%.0f", skill.getEffectiveness() * 100));

                publishEvent(SwarmEvent.Type.SKILL_PROMOTED,
                    "Skill promoted: " + skill.getName(), swarmId,
                    Map.of("skillName", skill.getName(),
                           "usageCount", skill.getUsageCount(),
                           "effectiveness", skill.getEffectiveness()));

                saveToMemory("skill-promoted", String.format(
                    "Promoted skill '%s' (usage: %d, effectiveness: %.0f%%)",
                    skill.getName(), skill.getUsageCount(), skill.getEffectiveness() * 100),
                    Map.of("skillName", skill.getName()));
            }
        }
        return promoted;
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

    /**
     * Tokenize a gap description for cross-iteration deduplication.
     * Filters stop words and short tokens to focus on semantic content.
     */
    private Set<String> tokenizeForDedup(String text) {
        if (text == null) return Set.of();
        Set<String> stopWords = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "are", "was", "tool",
            "would", "could", "should", "need", "provide", "existing", "currently",
            "more", "also", "new", "which", "using", "used", "will", "can", "not",
            "data", "output", "report", "analysis", "information", "sources", "multiple");
        return java.util.Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
            .filter(t -> t.length() > 3)
            .filter(t -> !stopWords.contains(t))
            .collect(Collectors.toSet());
    }

    /**
     * Check whether the output contains evidence of actual tool usage.
     * If the output appears to be purely LLM knowledge with no tool data,
     * return a warning message. Otherwise return null (evidence found).
     */
    private String checkToolEvidence(String output) {
        if (output == null || output.isBlank()) return "Output is empty";

        String lower = output.toLowerCase();

        // Positive signals: evidence that tools were actually called
        String[] toolEvidenceMarkers = {
            "http://", "https://",           // URLs from http_request/web_scrape
            "api response", "api returned",   // API data
            "json", "status code",            // HTTP response data
            "search results",                 // web_search output
            "scraped", "fetched",             // web_scrape output
            "command output", "exit code",    // shell_command output
            "**command:**", "**stdout:**",    // shell_command formatted output
            "nmap", "scan report",            // nmap output
            "open port", "filtered",          // port scan results
            "mac address", "host is up",      // network scan results
            "calculated", "calculation",      // calculator output
            "file content", "csv data",       // file tool output
            "retrieved:", "source:",          // data attribution
            "[from tool]", "[from api]",      // explicit source markers
            "wikipedia", "github.com",        // known-good API sources
            "hn.algolia", "duckduckgo"        // known-good API sources
        };

        int evidenceCount = 0;
        for (String marker : toolEvidenceMarkers) {
            if (lower.contains(marker)) evidenceCount++;
        }

        // Negative signals: LLM-knowledge-only patterns
        String[] knowledgeOnlyMarkers = {
            "based on general knowledge",
            "from my training data",
            "as of my last update",
            "i don't have access to real-time",
            "no results were found",
            "data not available",
            "could not be retrieved"
        };

        int knowledgeOnlyCount = 0;
        for (String marker : knowledgeOnlyMarkers) {
            if (lower.contains(marker)) knowledgeOnlyCount++;
        }

        if (evidenceCount == 0 && output.length() > 500) {
            return "No tool evidence found in " + output.length() + " chars of output. " +
                "The agents appear to have answered from LLM knowledge without calling any tools. " +
                "They should use http_request with real URLs (Wikipedia API, GitHub API, HN Algolia) to gather data.";
        }

        if (knowledgeOnlyCount >= 2 && evidenceCount < 2) {
            return "Output contains " + knowledgeOnlyCount + " 'no data' markers and only " + evidenceCount +
                " tool evidence markers. The agents may not have used tools effectively.";
        }

        return null; // evidence looks OK
    }

    private double jaccardSimilaritySimple(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    // Expose registry and memory for external access
    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    public Memory getMemory() {
        return memory;
    }
}
