package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry for dynamically generated skills.
 * Stores skills, tracks usage and effectiveness, handles promotion.
 *
 * Enhanced with:
 * - Version history tracking with rollback
 * - Category-based filtering for discovery
 * - Quality-aware promotion decisions
 * - Persistence of all enhanced metadata
 */
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private static final int PROMOTION_USAGE_THRESHOLD = 5;
    private static final double PROMOTION_SUCCESS_THRESHOLD = 0.70;

    private final Map<String, GeneratedSkill> skills = new ConcurrentHashMap<>();

    /**
     * Register a new skill in the registry.
     * Deduplicates by name — if a skill with the same name already exists,
     * keeps the one with higher usage count (or the newer one if equal).
     * When replacing, the old version is captured in version history.
     */
    public void register(GeneratedSkill skill) {
        // Check for existing skill with the same name
        Optional<GeneratedSkill> existing = skills.values().stream()
            .filter(s -> s.getName().equals(skill.getName()))
            .findFirst();

        if (existing.isPresent()) {
            GeneratedSkill old = existing.get();
            if (skill.getUsageCount() >= old.getUsageCount()) {
                // Capture old version in history before replacing
                skill.createNewVersion("Replaced skill " + old.getId() + " (usage: " + old.getUsageCount() + ")");
                skills.remove(old.getId());
                skills.put(skill.getId(), skill);
                logger.info("Skill replaced: {} (old={}, new={}, usage: {}->{})",
                    skill.getName(), old.getId(), skill.getId(), old.getUsageCount(), skill.getUsageCount());
            } else {
                logger.debug("Skill '{}' already exists with higher usage — skipping duplicate (id={})",
                    skill.getName(), skill.getId());
            }
        } else {
            skills.put(skill.getId(), skill);
            logger.info("Skill registered: {} v{} (id={}, status={}, category={})",
                skill.getName(), skill.getVersion(), skill.getId(), skill.getStatus(), skill.getCategory());
        }
    }

    /**
     * Find skills by keyword match against name and description.
     */
    public List<GeneratedSkill> search(String query, int limit) {
        if (query == null || query.trim().isEmpty()) return List.of();

        String lower = query.toLowerCase();
        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE) // Only validated+
            .filter(s -> s.getName().toLowerCase().contains(lower) ||
                        s.getDescription().toLowerCase().contains(lower) ||
                        (s.getDomain() != null && s.getDomain().toLowerCase().contains(lower)))
            .sorted(Comparator.comparingDouble(GeneratedSkill::getEffectiveness).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Find skills by category. Returns all skills in the given category.
     */
    public List<GeneratedSkill> findByCategory(String category) {
        if (category == null || category.isBlank()) return List.of();

        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .filter(s -> category.equalsIgnoreCase(s.getCategory()))
            .sorted(Comparator.comparingDouble(GeneratedSkill::getEffectiveness).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Find skills matching any of the given tags.
     */
    public List<GeneratedSkill> findByTags(List<String> tags, int limit) {
        if (tags == null || tags.isEmpty()) return List.of();

        Set<String> lowerTags = tags.stream().map(String::toLowerCase).collect(Collectors.toSet());

        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .filter(s -> s.getTags().stream().anyMatch(t -> lowerTags.contains(t.toLowerCase())))
            .sorted(Comparator.comparingDouble(GeneratedSkill::getEffectiveness).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Find skills semantically similar to a gap description using keyword-based
     * Jaccard similarity. Returns matches above the threshold, sorted by score descending.
     */
    public List<SimilarSkill> findSimilar(String gapDescription, double threshold) {
        if (gapDescription == null || gapDescription.isBlank()) return List.of();

        Set<String> gapTokens = tokenize(gapDescription);
        if (gapTokens.isEmpty()) return List.of();

        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .map(skill -> {
                // Score against name, description, and tags
                Set<String> skillTokens = new HashSet<>();
                skillTokens.addAll(tokenize(skill.getName()));
                skillTokens.addAll(tokenize(skill.getDescription()));
                skill.getTags().forEach(t -> skillTokens.addAll(tokenize(t)));

                double similarity = jaccardSimilarity(gapTokens, skillTokens);
                return new SimilarSkill(skill, similarity);
            })
            .filter(s -> s.similarity() >= threshold)
            .sorted(Comparator.comparingDouble(SimilarSkill::similarity).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Select the most relevant skills for a given task description.
     * Enhanced with category-based pre-filtering and tag matching.
     * Combines keyword relevance, effectiveness, and quality scoring.
     */
    public List<GeneratedSkill> selectRelevant(String taskDescription, int maxSkills) {
        if (taskDescription == null || taskDescription.isBlank() || maxSkills <= 0) {
            return getActiveSkills().stream().limit(maxSkills).collect(Collectors.toList());
        }

        Set<String> taskTokens = tokenize(taskDescription);

        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .map(skill -> {
                Set<String> skillTokens = new HashSet<>();
                skillTokens.addAll(tokenize(skill.getName()));
                skillTokens.addAll(tokenize(skill.getDescription()));
                // Include tags in similarity matching
                skill.getTags().forEach(t -> skillTokens.addAll(tokenize(t)));
                // Include category
                skillTokens.addAll(tokenize(skill.getCategory()));

                double relevance = jaccardSimilarity(taskTokens, skillTokens);
                double effectiveness = skill.getEffectiveness();

                // Quality bonus: higher quality skills get a boost
                double qualityBonus = 0.0;
                if (skill.getQualityScore() != null) {
                    qualityBonus = skill.getQualityScore().totalScore() / 500.0; // max 0.2 bonus
                }

                // Combined score: 50% relevance + 30% effectiveness + 20% quality
                double score = (relevance * 0.5) + (effectiveness * 0.3) + (qualityBonus);
                return Map.entry(skill, score);
            })
            .sorted(Map.Entry.<GeneratedSkill, Double>comparingByValue().reversed())
            .limit(maxSkills)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Select the most relevant skills using custom weights from the PolicyEngine.
     * Weights correspond to [relevance, effectiveness, quality] and should sum to ~1.0.
     */
    public List<GeneratedSkill> selectRelevant(String taskDescription, int maxSkills, double[] weights) {
        if (weights == null || weights.length < 3) {
            return selectRelevant(taskDescription, maxSkills);
        }

        if (taskDescription == null || taskDescription.isBlank() || maxSkills <= 0) {
            return getActiveSkills().stream().limit(maxSkills).collect(Collectors.toList());
        }

        Set<String> taskTokens = tokenize(taskDescription);

        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .map(skill -> {
                Set<String> skillTokens = new HashSet<>();
                skillTokens.addAll(tokenize(skill.getName()));
                skillTokens.addAll(tokenize(skill.getDescription()));
                skill.getTags().forEach(t -> skillTokens.addAll(tokenize(t)));
                skillTokens.addAll(tokenize(skill.getCategory()));

                double relevance = jaccardSimilarity(taskTokens, skillTokens);
                double effectiveness = skill.getEffectiveness();
                double qualityBonus = 0.0;
                if (skill.getQualityScore() != null) {
                    qualityBonus = skill.getQualityScore().totalScore() / 500.0;
                }

                double score = (relevance * weights[0]) + (effectiveness * weights[1]) + (qualityBonus * weights[2]);
                return Map.entry(skill, score);
            })
            .sorted(Map.Entry.<GeneratedSkill, Double>comparingByValue().reversed())
            .limit(maxSkills)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Tokenize a string into lowercase keywords, filtering stop words and short tokens.
     */
    private Set<String> tokenize(String text) {
        if (text == null) return Set.of();
        return Stream.of(text.toLowerCase().split("[^a-z0-9]+"))
            .filter(t -> t.length() > 2)
            .filter(t -> !STOP_WORDS.contains(t))
            .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "that", "this", "with", "from", "are", "was",
        "will", "can", "not", "but", "have", "has", "had", "been", "being",
        "should", "would", "could", "does", "did", "its", "into", "than",
        "when", "what", "which", "who", "how", "all", "each", "any", "both",
        "tool", "use", "using", "used", "new", "get", "set", "add"
    );

    /**
     * Result of a similarity search — skill with its similarity score.
     */
    public record SimilarSkill(GeneratedSkill skill, double similarity) {}

    /**
     * Get all skills that are validated or above (usable in workflows).
     */
    public List<GeneratedSkill> getActiveSkills() {
        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .collect(Collectors.toList());
    }

    /**
     * Returns the count of active (non-candidate) skills.
     */
    public int getActiveSkillCount() {
        return (int) skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .count();
    }

    /**
     * Get all skills regardless of status.
     */
    public List<GeneratedSkill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    /**
     * Get a skill by ID.
     */
    public Optional<GeneratedSkill> getById(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    /**
     * Record a usage of a skill (success or failure).
     * Uses enhanced promotion that considers quality score.
     */
    public void recordUsage(String skillId, boolean success) {
        // Usage tracking is handled by GeneratedSkill.execute() itself
        // This method can be used for external tracking
        getById(skillId).ifPresent(skill -> {
            if (skill.meetsEnhancedPromotionThreshold() && skill.getStatus() == SkillStatus.ACTIVE) {
                promote(skillId);
            }
        });
    }

    /**
     * Promote a skill that meets the enhanced threshold (usage + effectiveness + quality).
     */
    public void promote(String skillId) {
        getById(skillId).ifPresent(skill -> {
            if (skill.getUsageCount() >= PROMOTION_USAGE_THRESHOLD &&
                skill.getEffectiveness() >= PROMOTION_SUCCESS_THRESHOLD) {
                skill.setStatus(SkillStatus.PROMOTED);
                logger.info("Skill promoted: {} v{} (usage={}, effectiveness={:.0f}%, quality={})",
                    skill.getName(), skill.getVersion(), skill.getUsageCount(),
                    skill.getEffectiveness() * 100,
                    skill.getQualityScore() != null ? skill.getQualityScore().grade() : "N/A");
            }
        });
    }

    /**
     * Rollback a skill to a previous version.
     * Creates a new GeneratedSkill from the version history entry.
     */
    public Optional<GeneratedSkill> rollback(String skillId, String targetVersion) {
        return getById(skillId).flatMap(skill -> {
            Optional<SkillVersion> version = skill.getVersion(targetVersion);
            if (version.isEmpty()) {
                logger.warn("Version {} not found for skill {}", targetVersion, skillId);
                return Optional.empty();
            }

            SkillVersion v = version.get();
            GeneratedSkill rolledBack = new GeneratedSkill(
                skill.getName(), v.description(), skill.getDomain(),
                v.code(), skill.getParameterSchema(), skill.getTestCases()
            );
            rolledBack.setVersion(targetVersion);
            rolledBack.setStatus(skill.getStatus());
            rolledBack.setCategory(skill.getCategory());
            rolledBack.setTags(skill.getTags());
            rolledBack.setTriggerWhen(skill.getTriggerWhen());
            rolledBack.setAvoidWhen(skill.getAvoidWhen());
            rolledBack.setReferences(skill.getReferences());
            rolledBack.setResources(skill.getResources());

            // Replace in registry
            skills.remove(skillId);
            skills.put(rolledBack.getId(), rolledBack);

            logger.info("Skill '{}' rolled back to v{}", skill.getName(), targetVersion);
            return Optional.of(rolledBack);
        });
    }

    /**
     * Remove a skill from the registry.
     */
    public void remove(String skillId) {
        GeneratedSkill removed = skills.remove(skillId);
        if (removed != null) {
            logger.info("Skill removed: {} (id={})", removed.getName(), skillId);
        }
    }

    /**
     * Archive a skill — marks it as no longer active but preserves it in registry.
     * Used by SkillCurator to demote surplus or failing skills.
     */
    public void archive(String skillId) {
        getById(skillId).ifPresent(skill -> {
            skill.setStatus(SkillStatus.CANDIDATE); // Demote back to candidate
            logger.info("Skill archived: {} (id={})", skill.getName(), skillId);
        });
    }

    /**
     * Get registry statistics, now including category and quality distributions.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSkills", skills.size());
        stats.put("byStatus", skills.values().stream()
            .collect(Collectors.groupingBy(GeneratedSkill::getStatus, Collectors.counting())));
        stats.put("byCategory", skills.values().stream()
            .collect(Collectors.groupingBy(GeneratedSkill::getCategory, Collectors.counting())));
        stats.put("totalUsages", skills.values().stream().mapToInt(GeneratedSkill::getUsageCount).sum());
        stats.put("averageEffectiveness", skills.values().stream()
            .filter(s -> s.getUsageCount() > 0)
            .mapToDouble(GeneratedSkill::getEffectiveness)
            .average().orElse(0.0));
        stats.put("averageQuality", skills.values().stream()
            .filter(s -> s.getQualityScore() != null)
            .mapToInt(s -> s.getQualityScore().totalScore())
            .average().orElse(0.0));
        return stats;
    }

    public int size() {
        return skills.size();
    }

    // ==================== Persistence ====================

    /**
     * Save skills as directory-based packages.
     *
     * Each skill becomes a directory:
     * <pre>
     * output/skills/{skill-name}/
     *   SKILL.md       — The full skill definition (frontmatter + body)
     *   _meta.json     — Registry metadata (version history, usage stats, quality)
     *   references/    — Reference documents
     *   resources/     — Templates, specs
     * </pre>
     */
    public void save(Path directory) throws IOException {
        Files.createDirectories(directory);
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        List<GeneratedSkill> activeSkills = getActiveSkills();
        if (activeSkills.isEmpty()) {
            logger.info("No active skills to save");
            return;
        }

        StringBuilder index = new StringBuilder();
        index.append("# Generated Skills Registry\n\n");
        index.append("| Name | Type | Version | Status | Category | Quality | Usage | Description |\n");
        index.append("|------|------|---------|--------|----------|---------|-------|-------------|\n");

        for (GeneratedSkill skill : activeSkills) {
            // Create skill directory
            Path skillDir = directory.resolve(skill.getName());
            Files.createDirectories(skillDir);

            // 1. Write SKILL.md — the canonical skill definition
            Files.writeString(skillDir.resolve("SKILL.md"), skill.toSkillMd());

            // 2. Write _meta.json — registry metadata
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", skill.getId());
            meta.put("slug", skill.getName());
            meta.put("displayName", skill.getName().replace("_", " "));
            meta.put("status", skill.getStatus().name());
            meta.put("skillType", skill.getSkillType().name());
            meta.put("usageCount", skill.getUsageCount());
            meta.put("successCount", skill.getSuccessCount());
            meta.put("createdAt", skill.getCreatedAt().toString());
            meta.put("parentSkillId", skill.getParentSkillId());

            // Version info
            Map<String, Object> latest = new LinkedHashMap<>();
            latest.put("version", skill.getVersion());
            latest.put("publishedAt", System.currentTimeMillis());
            meta.put("latest", latest);

            // Version history
            List<Map<String, Object>> history = new ArrayList<>();
            for (SkillVersion v : skill.getVersionHistory()) {
                Map<String, Object> vData = new LinkedHashMap<>();
                vData.put("version", v.version());
                vData.put("publishedAt", v.createdAt().toString());
                vData.put("changeReason", v.changeReason());
                vData.put("usageCount", v.usageCount());
                vData.put("successCount", v.successCount());
                history.add(vData);
            }
            meta.put("history", history);

            // Quality score
            if (skill.getQualityScore() != null) {
                meta.put("qualityScore", skill.getQualityScore().toMap());
            }

            // Sub-skills
            if (!skill.getSubSkills().isEmpty()) {
                meta.put("subSkills", skill.getSubSkills().stream()
                    .map(GeneratedSkill::getName).collect(Collectors.toList()));
            }

            mapper.writeValue(skillDir.resolve("_meta.json").toFile(), meta);

            // 3. Write references/ directory
            if (!skill.getReferences().isEmpty()) {
                Path refsDir = skillDir.resolve("references");
                Files.createDirectories(refsDir);
                for (Map.Entry<String, String> ref : skill.getReferences().entrySet()) {
                    // Sanitize reference name to be a valid filename
                    String safeName = ref.getKey().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
                    if (safeName.length() > 100) safeName = safeName.substring(0, 100);
                    Files.writeString(refsDir.resolve(safeName + ".md"), ref.getValue());
                }
            }

            // 4. Write resources/ directory
            if (!skill.getResources().isEmpty()) {
                Path resDir = skillDir.resolve("resources");
                Files.createDirectories(resDir);
                for (Map.Entry<String, String> res : skill.getResources().entrySet()) {
                    Files.writeString(resDir.resolve(res.getKey()), res.getValue());
                }
            }

            // 5. Write tests/ directory — self-contained integration tests
            List<SkillDefinition.IntegrationTest> integrationTests = skill.getDefinition().getIntegrationTests();
            if (integrationTests != null && !integrationTests.isEmpty()) {
                Path testsDir = skillDir.resolve("tests");
                Files.createDirectories(testsDir);

                // Write each integration test as an individual .groovy file
                List<Map<String, Object>> testManifest = new ArrayList<>();
                for (SkillDefinition.IntegrationTest test : integrationTests) {
                    String safeName = test.name().replaceAll("[^a-zA-Z0-9_]", "_");

                    // Build self-contained test script
                    StringBuilder testScript = new StringBuilder();
                    testScript.append("// Integration Test: ").append(test.name()).append("\n");
                    if (test.description() != null && !test.description().isBlank()) {
                        testScript.append("// ").append(test.description()).append("\n");
                    }
                    testScript.append("//\n");
                    testScript.append("// This test verifies the skill works end-to-end through skill.execute().\n");
                    testScript.append("// To run: load the skill, call skill.runIntegrationTests()\n");
                    testScript.append("//\n");
                    testScript.append("// Input params:\n");
                    if (test.inputParams() != null) {
                        test.inputParams().forEach((k, v) ->
                            testScript.append("//   ").append(k).append(": ").append(v).append("\n"));
                    }
                    if (test.expectedToolCalls() != null && !test.expectedToolCalls().isEmpty()) {
                        testScript.append("// Expected tool calls: ")
                            .append(String.join(", ", test.expectedToolCalls())).append("\n");
                    }
                    testScript.append("\n// --- Assertions (run against 'output' from skill.execute()) ---\n");
                    testScript.append(test.assertionCode()).append("\n");

                    Files.writeString(testsDir.resolve(safeName + ".groovy"), testScript.toString());

                    // Build manifest entry
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", test.name());
                    entry.put("description", test.description());
                    entry.put("file", safeName + ".groovy");
                    entry.put("inputParams", test.inputParams());
                    entry.put("expectedToolCalls", test.expectedToolCalls());
                    testManifest.add(entry);
                }

                // Write TEST_MANIFEST.json
                mapper.writeValue(testsDir.resolve("TEST_MANIFEST.json").toFile(), testManifest);

                // Write README for reproducibility
                StringBuilder testReadme = new StringBuilder();
                testReadme.append("# Integration Tests for ").append(skill.getName()).append("\n\n");
                testReadme.append("These tests verify the skill works end-to-end through its `execute()` pipeline.\n\n");
                testReadme.append("## How to run\n\n");
                testReadme.append("```java\n");
                testReadme.append("// Load the skill\n");
                testReadme.append("GeneratedSkill skill = registry.getById(\"").append(skill.getId()).append("\").get();\n");
                testReadme.append("skill.setAvailableTools(toolMap); // inject real tools\n\n");
                testReadme.append("// Run all integration tests\n");
                testReadme.append("var results = skill.runIntegrationTests();\n");
                testReadme.append("System.out.println(results.summary());\n");
                testReadme.append("assert results.allPassed() : \"Integration tests failed\";\n");
                testReadme.append("```\n\n");
                testReadme.append("## Tests\n\n");
                testReadme.append("| Test | Description | Expected Tools |\n");
                testReadme.append("|------|-------------|----------------|\n");
                for (SkillDefinition.IntegrationTest test : integrationTests) {
                    testReadme.append("| ").append(test.name()).append(" | ")
                        .append(test.description() != null ? test.description() : "")
                        .append(" | ").append(test.expectedToolCalls() != null
                            ? String.join(", ", test.expectedToolCalls()) : "").append(" |\n");
                }
                Files.writeString(testsDir.resolve("README.md"), testReadme.toString());
            }

            // 6. Save sub-skills as nested directories
            for (GeneratedSkill subSkill : skill.getSubSkills()) {
                Path subDir = skillDir.resolve(subSkill.getName());
                Files.createDirectories(subDir);
                Files.writeString(subDir.resolve("SKILL.md"), subSkill.toSkillMd());
            }

            // Add to index
            index.append(String.format("| [%s](%s/SKILL.md) | %s | %s | %s | %s | %s | %d | %s |\n",
                skill.getName(), skill.getName(),
                skill.getSkillType(), skill.getVersion(), skill.getStatus(), skill.getCategory(),
                skill.getQualityScore() != null ? skill.getQualityScore().grade() : "N/A",
                skill.getUsageCount(),
                skill.getDescription().length() > 35
                    ? skill.getDescription().substring(0, 32) + "..."
                    : skill.getDescription()));
        }

        Files.writeString(directory.resolve("SKILLS.md"), index.toString());
        logger.info("Saved {} skills as packages to {}", activeSkills.size(), directory);
    }

    /**
     * Load skills from directory-based packages OR legacy JSON files.
     * Supports both skill packages (SKILL.md + _meta.json) and legacy flat JSON.
     */
    @SuppressWarnings("unchecked")
    public int load(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            logger.info("Skill directory does not exist: {}", directory);
            return 0;
        }

        ObjectMapper mapper = new ObjectMapper();
        int loaded = 0;

        try (var stream = Files.list(directory)) {
            List<Path> entries = stream.collect(Collectors.toList());

            for (Path entry : entries) {
                try {
                    if (Files.isDirectory(entry)) {
                        // Skill package: directory with SKILL.md + _meta.json
                        GeneratedSkill skill = loadSkillPackage(entry, mapper);
                        if (skill != null) {
                            register(skill);
                            loaded++;
                        }
                    } else if (entry.toString().endsWith(".json") && !entry.getFileName().toString().startsWith("_")) {
                        // Legacy flat JSON file
                        GeneratedSkill skill = loadLegacyJson(entry, mapper);
                        if (skill != null) {
                            register(skill);
                            loaded++;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load skill from {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read skill directory: {}", directory, e);
        }

        logger.info("Loaded {} skills from {}", loaded, directory);
        return loaded;
    }

    /**
     * Load a skill from a package directory.
     */
    private GeneratedSkill loadSkillPackage(Path skillDir, ObjectMapper mapper) throws IOException {
        Path skillMdPath = skillDir.resolve("SKILL.md");
        Path metaPath = skillDir.resolve("_meta.json");

        if (!Files.exists(skillMdPath)) return null;

        // Parse SKILL.md
        String skillMdContent = Files.readString(skillMdPath);
        SkillDefinition def = SkillDefinition.fromSkillMd(skillMdContent);

        if (def.getName() == null || def.getName().isBlank()) {
            def.setName(skillDir.getFileName().toString());
        }

        // Load references/ directory
        Path refsDir = skillDir.resolve("references");
        if (Files.isDirectory(refsDir)) {
            try (var refs = Files.list(refsDir)) {
                Map<String, String> references = new LinkedHashMap<>();
                refs.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        String refName = f.getFileName().toString().replaceAll("\\.md$", "");
                        references.put(refName, Files.readString(f));
                    } catch (IOException e) {
                        logger.warn("Failed to read reference: {}", f);
                    }
                });
                def.setReferences(references);
            }
        }

        // Load resources/ directory
        Path resDir = skillDir.resolve("resources");
        if (Files.isDirectory(resDir)) {
            try (var res = Files.list(resDir)) {
                Map<String, String> resources = new LinkedHashMap<>();
                res.filter(Files::isRegularFile).forEach(f -> {
                    try {
                        resources.put(f.getFileName().toString(), Files.readString(f));
                    } catch (IOException e) {
                        logger.warn("Failed to read resource: {}", f);
                    }
                });
                def.setResources(resources);
            }
        }

        // Load integration tests from tests/ directory (if not already parsed from SKILL.md)
        Path testsDir = skillDir.resolve("tests");
        if (Files.isDirectory(testsDir) && (def.getIntegrationTests() == null || def.getIntegrationTests().isEmpty())) {
            Path manifestPath = testsDir.resolve("TEST_MANIFEST.json");
            if (Files.exists(manifestPath)) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> manifest = mapper.readValue(manifestPath.toFile(), List.class);
                    List<SkillDefinition.IntegrationTest> loadedTests = new ArrayList<>();
                    for (Map<String, Object> entry : manifest) {
                        String testName = (String) entry.get("name");
                        String testDesc = (String) entry.get("description");
                        @SuppressWarnings("unchecked")
                        Map<String, String> inputParams = entry.get("inputParams") != null
                            ? (Map<String, String>) entry.get("inputParams") : Map.of();
                        @SuppressWarnings("unchecked")
                        List<String> expectedTools = entry.get("expectedToolCalls") != null
                            ? (List<String>) entry.get("expectedToolCalls") : List.of();

                        // Read assertion code from the .groovy file
                        String testFile = (String) entry.get("file");
                        String assertionCode = "";
                        if (testFile != null) {
                            Path groovyPath = testsDir.resolve(testFile);
                            if (Files.exists(groovyPath)) {
                                String content = Files.readString(groovyPath);
                                // Extract assertion code (everything after the header comments)
                                int assertStart = content.indexOf("// --- Assertions");
                                if (assertStart >= 0) {
                                    int nextLine = content.indexOf('\n', assertStart);
                                    if (nextLine >= 0) {
                                        assertionCode = content.substring(nextLine + 1).trim();
                                    }
                                } else {
                                    // Fallback: use non-comment lines
                                    assertionCode = Arrays.stream(content.split("\n"))
                                        .filter(line -> !line.trim().startsWith("//"))
                                        .collect(Collectors.joining("\n")).trim();
                                }
                            }
                        }

                        if (testName != null && !assertionCode.isBlank()) {
                            loadedTests.add(new SkillDefinition.IntegrationTest(
                                testName, testDesc, inputParams, assertionCode, expectedTools));
                        }
                    }
                    if (!loadedTests.isEmpty()) {
                        def.setIntegrationTests(loadedTests);
                        logger.info("Loaded {} integration tests from tests/ for skill '{}'",
                            loadedTests.size(), def.getName());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to load integration tests from {}: {}", testsDir, e.getMessage());
                }
            }
        }

        GeneratedSkill skill = new GeneratedSkill(def);

        // Restore metadata from _meta.json
        if (Files.exists(metaPath)) {
            Map<String, Object> meta = mapper.readValue(metaPath.toFile(), Map.class);

            String statusStr = (String) meta.getOrDefault("status", "VALIDATED");
            try { skill.setStatus(SkillStatus.valueOf(statusStr)); }
            catch (IllegalArgumentException e) { skill.setStatus(SkillStatus.VALIDATED); }

            Map<String, Object> latest = (Map<String, Object>) meta.get("latest");
            if (latest != null && latest.containsKey("version")) {
                skill.setVersion((String) latest.get("version"));
            }

            skill.setParentSkillId((String) meta.get("parentSkillId"));

            // Restore quality score
            if (meta.containsKey("qualityScore")) {
                Map<String, Object> qs = (Map<String, Object>) meta.get("qualityScore");
                skill.setQualityScore(new SkillQualityScore(
                    toInt(qs.get("documentation")), toInt(qs.get("testCoverage")),
                    toInt(qs.get("errorHandling")), toInt(qs.get("codeComplexity")),
                    toInt(qs.get("outputFormat"))
                ));
            }
        }

        // Load sub-skills (nested directories with SKILL.md)
        try (var subs = Files.list(skillDir)) {
            subs.filter(Files::isDirectory)
                .filter(d -> !d.getFileName().toString().equals("references") &&
                             !d.getFileName().toString().equals("resources") &&
                             !d.getFileName().toString().equals("tests"))
                .forEach(subDir -> {
                    try {
                        GeneratedSkill subSkill = loadSkillPackage(subDir, mapper);
                        if (subSkill != null) {
                            skill.addSubSkill(subSkill);
                        }
                    } catch (IOException e) {
                        logger.warn("Failed to load sub-skill: {}", subDir);
                    }
                });
        }

        logger.info("Loaded skill package: {} (type={}, v{})", skill.getName(), skill.getSkillType(), skill.getVersion());
        return skill;
    }

    /**
     * Load a skill from a legacy flat JSON file (backward compatibility).
     */
    @SuppressWarnings("unchecked")
    private GeneratedSkill loadLegacyJson(Path jsonFile, ObjectMapper mapper) throws IOException {
        Map<String, Object> data = mapper.readValue(jsonFile.toFile(), Map.class);

        String name = (String) data.get("name");
        String description = (String) data.get("description");
        String domain = (String) data.getOrDefault("domain", "generated");
        String code = (String) data.get("code");
        Map<String, Object> schema = (Map<String, Object>) data.get("parameterSchema");
        List<String> testCases = (List<String>) data.get("testCases");

        if (code == null || code.isEmpty()) return null;

        GeneratedSkill skill = new GeneratedSkill(name, description, domain, code, schema, testCases);

        String statusStr = (String) data.getOrDefault("status", "VALIDATED");
        try { skill.setStatus(SkillStatus.valueOf(statusStr)); }
        catch (IllegalArgumentException e) { skill.setStatus(SkillStatus.VALIDATED); }

        if (data.containsKey("version")) skill.setVersion((String) data.get("version"));
        skill.setTriggerWhen((String) data.get("triggerWhen"));
        skill.setAvoidWhen((String) data.get("avoidWhen"));
        skill.setCategory((String) data.getOrDefault("category", "generated"));
        skill.setTags(data.containsKey("tags") ? (List<String>) data.get("tags") : List.of());
        skill.setParentSkillId((String) data.get("parentSkillId"));

        if (data.containsKey("references")) skill.setReferences((Map<String, String>) data.get("references"));
        if (data.containsKey("resources")) skill.setResources((Map<String, String>) data.get("resources"));

        if (data.containsKey("qualityScore")) {
            Map<String, Object> qs = (Map<String, Object>) data.get("qualityScore");
            skill.setQualityScore(new SkillQualityScore(
                toInt(qs.get("documentation")), toInt(qs.get("testCoverage")),
                toInt(qs.get("errorHandling")), toInt(qs.get("codeComplexity")),
                toInt(qs.get("outputFormat"))
            ));
        }

        logger.info("Loaded legacy skill: {} v{}", name, skill.getVersion());
        return skill;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return 0;
    }
}
