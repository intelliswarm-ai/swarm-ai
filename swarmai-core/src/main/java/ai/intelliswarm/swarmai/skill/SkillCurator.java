package ai.intelliswarm.swarmai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;

/**
 * Independently evaluates, stack-ranks, and prunes generated skills,
 * publishing the best to a curated repository.
 *
 * The Curator assesses each skill across 5 dimensions (execution, effectiveness,
 * code quality, test coverage, uniqueness), groups similar skills by capability,
 * and keeps only the top performers per group.
 *
 * Skills scoring >= 60 are published to the curated repository.
 * Skills scoring < 60 are archived (moved to _archived/).
 * Within each group, only the top K (default 3) are kept.
 */
public class SkillCurator {

    private static final Logger logger = LoggerFactory.getLogger(SkillCurator.class);
    private static final double GROUPING_THRESHOLD = 0.50;
    private static final int CURATION_BAR = 60;
    private static final int DEFAULT_TOP_K = 3;

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "that", "this", "with", "from", "are", "was",
        "will", "can", "not", "but", "have", "has", "had", "been", "being",
        "should", "would", "could", "does", "did", "its", "into", "than",
        "when", "what", "which", "who", "how", "all", "each", "any", "both",
        "tool", "use", "using", "used", "new", "get", "set", "add"
    );

    private final ObjectMapper objectMapper;

    public SkillCurator() {
        this.objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ==================== Assessment ====================

    /**
     * Assess a single skill across all 5 dimensions.
     *
     * @param skill     the skill to assess
     * @param allSkills all skills in the registry (for uniqueness comparison)
     * @return a SkillAssessment with scores, grade, and notes
     */
    public SkillAssessment assess(GeneratedSkill skill, List<GeneratedSkill> allSkills) {
        List<String> notes = new ArrayList<>();

        // 1. Execution score (0-25)
        int executionScore = computeExecutionScore(skill, notes);

        // 2. Effectiveness score (0-25)
        int effectivenessScore = computeEffectivenessScore(skill, notes);

        // 3. Code quality score (0-20)
        int codeQualityScore = computeCodeQualityScore(skill, notes);

        // 4. Test coverage score (0-15)
        int testCoverageScore = computeTestCoverageScore(skill, notes);

        // 5. Uniqueness score (0-15)
        int uniquenessScore = computeUniquenessScore(skill, allSkills, notes);

        int totalScore = executionScore + effectivenessScore + codeQualityScore
                        + testCoverageScore + uniquenessScore;
        String grade = SkillAssessment.gradeFromScore(totalScore);
        boolean passes = totalScore >= CURATION_BAR;

        logger.info("Assessed skill '{}': {}/100 ({}) [exec={}, eff={}, qual={}, test={}, uniq={}]",
            skill.getName(), totalScore, grade,
            executionScore, effectivenessScore, codeQualityScore,
            testCoverageScore, uniquenessScore);

        return new SkillAssessment(
            skill, executionScore, effectivenessScore, codeQualityScore,
            testCoverageScore, uniquenessScore, totalScore, grade, passes,
            null, 0, notes
        );
    }

    /**
     * Execution score (0-25):
     * +5 if code compiles (syntax check via GroovyShell.parse())
     * +10 if at least 1 test case passes
     * +5 if all test cases pass
     * +5 if skill execution with empty params returns non-error output
     */
    private int computeExecutionScore(GeneratedSkill skill, List<String> notes) {
        int score = 0;
        String code = skill.getCode();
        SkillType type = skill.getSkillType();

        // PROMPT-only skills get a base execution score for having a valid body
        if (type == SkillType.PROMPT) {
            String body = skill.getInstructionBody();
            if (body != null && !body.isBlank()) {
                score = 15; // PROMPT skills don't compile/execute code
                notes.add("PROMPT skill: +15 base execution (valid instruction body)");
            }
            // Check if execution returns non-error output
            try {
                Object result = skill.execute(Map.of());
                if (result != null && !result.toString().startsWith("Error:")) {
                    score += 5;
                    notes.add("PROMPT execution returned valid output: +5");
                }
            } catch (Exception e) {
                notes.add("PROMPT execution failed: " + e.getMessage());
            }
            return Math.min(25, score);
        }

        // CODE/HYBRID/COMPOSITE: check compilation
        if (code != null && !code.isBlank()) {
            try {
                CompilerConfiguration config = createCompilerConfig();
                new GroovyShell(config).parse(code);
                score += 5;
                notes.add("Compilation: PASS (+5)");
            } catch (Exception e) {
                notes.add("Compilation: FAIL (" + e.getMessage() + ")");
                return score; // Can't proceed if code doesn't compile
            }
        } else if (type == SkillType.COMPOSITE) {
            // Composite without code gets base score if it has routing/sub-skills
            score += 5;
            notes.add("COMPOSITE skill: +5 base (routing-based)");
        }

        // Test execution
        List<String> testCases = skill.getTestCases();
        if (testCases != null && !testCases.isEmpty()) {
            int passed = 0;
            for (String testCode : testCases) {
                try {
                    Object result = skill.executeTest(testCode);
                    if (result instanceof Boolean && !(Boolean) result) {
                        notes.add("Test returned false");
                    } else {
                        passed++;
                    }
                } catch (Exception e) {
                    notes.add("Test failed: " + e.getMessage());
                }
            }
            if (passed >= 1) {
                score += 10;
                notes.add("At least 1 test passed: +10 (" + passed + "/" + testCases.size() + ")");
            }
            if (passed == testCases.size()) {
                score += 5;
                notes.add("All tests passed: +5");
            }
        }

        // Execute with empty params
        try {
            Object result = skill.execute(Map.of());
            if (result != null && !result.toString().startsWith("Error:")) {
                score += 5;
                notes.add("Empty-param execution: PASS (+5)");
            } else {
                notes.add("Empty-param execution returned error: " + result);
            }
        } catch (Exception e) {
            notes.add("Empty-param execution threw: " + e.getMessage());
        }

        return Math.min(25, score);
    }

    /**
     * Effectiveness score (0-25):
     * Based on historical success rate (successCount / usageCount).
     */
    private int computeEffectivenessScore(GeneratedSkill skill, List<String> notes) {
        if (skill.getUsageCount() == 0) {
            notes.add("Effectiveness: 0 (no usage data)");
            return 0;
        }
        double effectiveness = skill.getEffectiveness();
        int score = (int) (25 * effectiveness);
        score = Math.min(25, score);
        notes.add(String.format("Effectiveness: %d/25 (%.0f%% success, %d/%d uses)",
            score, effectiveness * 100, skill.getSuccessCount(), skill.getUsageCount()));
        return score;
    }

    /**
     * Code quality score (0-20):
     * Uses existing SkillQualityScore.assess() and normalizes to 0-20.
     */
    private int computeCodeQualityScore(GeneratedSkill skill, List<String> notes) {
        SkillQualityScore qs = SkillQualityScore.assess(skill);
        int normalized = (int) ((qs.totalScore() / 100.0) * 20);
        normalized = Math.min(20, normalized);
        notes.add(String.format("Code quality: %d/20 (raw %d/100, grade %s)",
            normalized, qs.totalScore(), qs.grade()));
        return normalized;
    }

    /**
     * Test coverage score (0-15):
     * +5 per test case (up to 10 points)
     * +5 if any test has "assert" keyword
     */
    private int computeTestCoverageScore(GeneratedSkill skill, List<String> notes) {
        int score = 0;
        List<String> testCases = skill.getTestCases();

        if (testCases == null || testCases.isEmpty()) {
            // PROMPT skills: check for self-check items and examples as test proxies
            if (skill.getSkillType() == SkillType.PROMPT) {
                SkillDefinition def = skill.getDefinition();
                if (def.getSelfCheckItems() != null && !def.getSelfCheckItems().isEmpty()) {
                    score += 5;
                    notes.add("PROMPT test coverage: +5 (has self-check items)");
                }
                if (def.getExamples() != null && !def.getExamples().isEmpty()) {
                    score += 5;
                    notes.add("PROMPT test coverage: +5 (has examples)");
                }
            } else {
                notes.add("Test coverage: 0 (no test cases)");
            }
            return Math.min(15, score);
        }

        // +5 per test case, up to 10
        int testPoints = Math.min(10, testCases.size() * 5);
        score += testPoints;
        notes.add(String.format("Test count: +%d (%d test case(s))", testPoints, testCases.size()));

        // +5 if any test has "assert"
        boolean hasAssert = testCases.stream().anyMatch(t -> t.contains("assert"));
        if (hasAssert) {
            score += 5;
            notes.add("Test assertions: +5 (has assert keyword)");
        }

        return Math.min(15, score);
    }

    /**
     * Uniqueness score (0-15):
     * Computes Jaccard similarity against all other skills.
     * More unique = higher score. Better version of a duplicate gets a bonus.
     */
    private int computeUniquenessScore(GeneratedSkill skill, List<GeneratedSkill> allSkills, List<String> notes) {
        if (allSkills == null || allSkills.size() <= 1) {
            notes.add("Uniqueness: 15 (only skill in registry)");
            return 15;
        }

        Set<String> myTokens = tokenizeSkill(skill);
        double maxSimilarity = 0.0;
        GeneratedSkill mostSimilar = null;

        for (GeneratedSkill other : allSkills) {
            if (other.getId().equals(skill.getId())) continue;
            Set<String> otherTokens = tokenizeSkill(other);
            double similarity = jaccardSimilarity(myTokens, otherTokens);
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                mostSimilar = other;
            }
        }

        int score;
        if (maxSimilarity > 0.60) {
            score = 0;
            notes.add(String.format("Uniqueness: 0 (redundant, %.0f%% similar to '%s')",
                maxSimilarity * 100, mostSimilar != null ? mostSimilar.getName() : "unknown"));
        } else if (maxSimilarity > 0.40) {
            score = 5;
            notes.add(String.format("Uniqueness: 5 (somewhat unique, %.0f%% max similarity)",
                maxSimilarity * 100));
        } else if (maxSimilarity > 0.20) {
            score = 10;
            notes.add(String.format("Uniqueness: 10 (fairly unique, %.0f%% max similarity)",
                maxSimilarity * 100));
        } else {
            score = 15;
            notes.add(String.format("Uniqueness: 15 (unique, %.0f%% max similarity)",
                maxSimilarity * 100));
        }

        // Bonus: if this skill has higher effectiveness than its most similar peer, add +5
        if (mostSimilar != null && skill.getEffectiveness() > mostSimilar.getEffectiveness()) {
            score = Math.min(15, score + 5);
            notes.add(String.format("Uniqueness bonus: +5 (better effectiveness than similar peer '%s': %.0f%% vs %.0f%%)",
                mostSimilar.getName(), skill.getEffectiveness() * 100, mostSimilar.getEffectiveness() * 100));
        }

        return score;
    }

    // ==================== Batch Assessment ====================

    /**
     * Assess all active skills in the registry.
     * Returns assessments sorted by total score descending.
     */
    public List<SkillAssessment> assessAll(SkillRegistry registry) {
        List<GeneratedSkill> allSkills = registry.getAllSkills();
        logger.info("Assessing {} skills from registry", allSkills.size());

        return allSkills.stream()
            .map(skill -> assess(skill, allSkills))
            .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
            .collect(Collectors.toList());
    }

    // ==================== Grouping ====================

    /**
     * Group assessments by capability using category pre-grouping
     * and Jaccard similarity-based single-linkage clustering within each category.
     *
     * Group name = name of the highest-scoring skill in each group.
     */
    public Map<String, List<SkillAssessment>> groupByCapability(List<SkillAssessment> assessments) {
        // Pre-group by category
        Map<String, List<SkillAssessment>> byCategory = assessments.stream()
            .collect(Collectors.groupingBy(
                sa -> sa.skill().getCategory() != null ? sa.skill().getCategory() : "uncategorized",
                LinkedHashMap::new,
                Collectors.toList()
            ));

        Map<String, List<SkillAssessment>> result = new LinkedHashMap<>();

        for (var catEntry : byCategory.entrySet()) {
            List<SkillAssessment> catSkills = catEntry.getValue();

            // Single-linkage clustering within the category
            List<List<SkillAssessment>> clusters = singleLinkageClustering(catSkills);

            for (List<SkillAssessment> cluster : clusters) {
                // Sort by score descending
                cluster.sort(Comparator.comparingInt(SkillAssessment::totalScore).reversed());

                // Group name = highest scoring skill's name
                String groupName = cluster.get(0).skill().getName();

                // Assign ranks
                List<SkillAssessment> ranked = new ArrayList<>();
                for (int i = 0; i < cluster.size(); i++) {
                    SkillAssessment sa = cluster.get(i);
                    ranked.add(new SkillAssessment(
                        sa.skill(), sa.executionScore(), sa.effectivenessScore(),
                        sa.codeQualityScore(), sa.testCoverageScore(), sa.uniquenessScore(),
                        sa.totalScore(), sa.grade(), sa.passesCurationBar(),
                        groupName, i + 1, sa.assessmentNotes()
                    ));
                }

                result.put(groupName, ranked);
            }
        }

        logger.info("Grouped {} assessments into {} groups", assessments.size(), result.size());
        return result;
    }

    /**
     * Single-linkage clustering: if skill A is similar to B, and B to C,
     * they all end up in the same cluster.
     */
    private List<List<SkillAssessment>> singleLinkageClustering(List<SkillAssessment> assessments) {
        int n = assessments.size();
        // Union-Find
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        // Pairwise Jaccard on skill names
        for (int i = 0; i < n; i++) {
            Set<String> tokensI = tokenizeSkill(assessments.get(i).skill());
            for (int j = i + 1; j < n; j++) {
                Set<String> tokensJ = tokenizeSkill(assessments.get(j).skill());
                double similarity = jaccardSimilarity(tokensI, tokensJ);
                if (similarity >= GROUPING_THRESHOLD) {
                    union(parent, i, j);
                }
            }
        }

        // Collect clusters
        Map<Integer, List<SkillAssessment>> clusterMap = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            clusterMap.computeIfAbsent(root, k -> new ArrayList<>()).add(assessments.get(i));
        }

        return new ArrayList<>(clusterMap.values());
    }

    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]; // path compression
            i = parent[i];
        }
        return i;
    }

    private void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[rootB] = rootA;
        }
    }

    // ==================== Full Curation Pipeline ====================

    /**
     * Run the full curation pipeline:
     * 1. Assess all skills
     * 2. Group by capability
     * 3. Rank within groups
     * 4. Publish passing skills to curated repo
     * 5. Archive failing skills
     * 6. Keep top K per group
     * 7. Write CATALOG.md
     * 8. Return CurationReport
     */
    public CurationReport curate(SkillRegistry registry, Path curatedRepoPath) throws IOException {
        Path sourceSkillsDir = Path.of("output/skills");

        logger.info("=== Skill Curation Starting ===");
        logger.info("Source: {}", sourceSkillsDir);
        logger.info("Curated repo: {}", curatedRepoPath);

        // 1. Assess all skills
        List<SkillAssessment> assessments = assessAll(registry);
        if (assessments.isEmpty()) {
            logger.info("No skills to curate");
            return new CurationReport(0, 0, 0, 0, 0, 0, Map.of(), LocalDateTime.now());
        }

        // 2. Group by capability
        Map<String, List<SkillAssessment>> groups = groupByCapability(assessments);

        // 3. Tally
        int totalPassed = 0;
        int totalFailed = 0;
        int totalPublished = 0;
        int totalArchived = 0;

        Files.createDirectories(curatedRepoPath);

        // 4-6. Process each group
        Map<String, List<SkillAssessment>> finalGroups = new LinkedHashMap<>();

        for (var entry : groups.entrySet()) {
            String groupName = entry.getKey();
            List<SkillAssessment> ranked = entry.getValue();

            List<SkillAssessment> kept = new ArrayList<>();

            for (SkillAssessment sa : ranked) {
                if (sa.passesCurationBar()) {
                    totalPassed++;

                    // Keep top K per group
                    if (kept.size() < DEFAULT_TOP_K) {
                        try {
                            publishToCuratedRepo(sa, curatedRepoPath);
                            totalPublished++;
                            sa.skill().setStatus(SkillStatus.CURATED);
                            kept.add(sa);
                            logger.info("Published skill '{}' (score={}, grade={}, rank={})",
                                sa.skill().getName(), sa.totalScore(), sa.grade(), sa.rankInGroup());
                        } catch (IOException e) {
                            logger.error("Failed to publish skill '{}': {}", sa.skill().getName(), e.getMessage());
                        }
                    } else {
                        // Beyond top K but still passing — archive the surplus
                        try {
                            archiveSkill(sa.skill(), sourceSkillsDir);
                            registry.archive(sa.skill().getId());
                            totalArchived++;
                            logger.info("Archived surplus skill '{}' (score={}, rank={} > top-{})",
                                sa.skill().getName(), sa.totalScore(), sa.rankInGroup(), DEFAULT_TOP_K);
                        } catch (IOException e) {
                            logger.warn("Failed to archive surplus skill '{}': {}", sa.skill().getName(), e.getMessage());
                        }
                        kept.add(sa);
                    }
                } else {
                    totalFailed++;
                    // Archive failing skills
                    try {
                        archiveSkill(sa.skill(), sourceSkillsDir);
                        registry.archive(sa.skill().getId());
                        totalArchived++;
                        logger.info("Archived failing skill '{}' (score={}, grade={})",
                            sa.skill().getName(), sa.totalScore(), sa.grade());
                    } catch (IOException e) {
                        logger.warn("Failed to archive skill '{}': {}", sa.skill().getName(), e.getMessage());
                    }
                    kept.add(sa);
                }
            }

            finalGroups.put(groupName, kept);
        }

        // 7. Write CATALOG.md
        writeCatalog(finalGroups, curatedRepoPath);

        CurationReport report = new CurationReport(
            assessments.size(), totalPassed, totalFailed,
            totalPublished, totalArchived, groups.size(),
            finalGroups, LocalDateTime.now()
        );

        logger.info("=== Skill Curation Complete ===");
        logger.info("Assessed: {}, Passed: {}, Failed: {}, Published: {}, Archived: {}, Groups: {}",
            report.totalAssessed(), report.totalPassed(), report.totalFailed(),
            report.totalPublished(), report.totalArchived(), report.groupsIdentified());

        return report;
    }

    // ==================== Publishing ====================

    /**
     * Publish a passing skill to the curated repository.
     * Creates: SKILL.md, _meta.json, _assessment.json
     */
    private void publishToCuratedRepo(SkillAssessment assessment, Path curatedRepoPath) throws IOException {
        String category = assessment.skill().getCategory() != null
            ? assessment.skill().getCategory() : "uncategorized";
        String skillName = assessment.skill().getName();

        Path targetDir = curatedRepoPath.resolve(category).resolve(skillName);
        Files.createDirectories(targetDir);

        // 1. Write SKILL.md
        Files.writeString(targetDir.resolve("SKILL.md"), assessment.skill().toSkillMd());

        // 2. Write _meta.json (same format as SkillRegistry.save())
        Map<String, Object> meta = new LinkedHashMap<>();
        GeneratedSkill skill = assessment.skill();
        meta.put("id", skill.getId());
        meta.put("slug", skill.getName());
        meta.put("displayName", skill.getName().replace("_", " "));
        meta.put("status", SkillStatus.CURATED.name());
        meta.put("skillType", skill.getSkillType().name());
        meta.put("usageCount", skill.getUsageCount());
        meta.put("successCount", skill.getSuccessCount());
        meta.put("createdAt", skill.getCreatedAt().toString());
        meta.put("parentSkillId", skill.getParentSkillId());

        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("version", skill.getVersion());
        latest.put("publishedAt", System.currentTimeMillis());
        meta.put("latest", latest);

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

        if (skill.getQualityScore() != null) {
            meta.put("qualityScore", skill.getQualityScore().toMap());
        }

        if (!skill.getSubSkills().isEmpty()) {
            meta.put("subSkills", skill.getSubSkills().stream()
                .map(GeneratedSkill::getName).collect(Collectors.toList()));
        }

        objectMapper.writeValue(targetDir.resolve("_meta.json").toFile(), meta);

        // 3. Write _assessment.json
        Map<String, Object> assessmentJson = new LinkedHashMap<>();
        assessmentJson.put("totalScore", assessment.totalScore());
        assessmentJson.put("grade", assessment.grade());
        assessmentJson.put("dimensions", Map.of(
            "execution", assessment.executionScore(),
            "effectiveness", assessment.effectivenessScore(),
            "codeQuality", assessment.codeQualityScore(),
            "testCoverage", assessment.testCoverageScore(),
            "uniqueness", assessment.uniquenessScore()
        ));
        assessmentJson.put("assessedAt", LocalDateTime.now().toString());
        assessmentJson.put("assessmentNotes", assessment.assessmentNotes());
        assessmentJson.put("rankInGroup", assessment.rankInGroup());
        assessmentJson.put("groupName", assessment.groupName());
        assessmentJson.put("passesCurationBar", assessment.passesCurationBar());
        objectMapper.writeValue(targetDir.resolve("_assessment.json").toFile(), assessmentJson);

        // 4. Copy references/ if present
        if (!skill.getReferences().isEmpty()) {
            Path refsDir = targetDir.resolve("references");
            Files.createDirectories(refsDir);
            for (Map.Entry<String, String> ref : skill.getReferences().entrySet()) {
                String safeName = ref.getKey().replaceAll("[^a-zA-Z0-9_\\-.]", "_");
                if (safeName.length() > 100) safeName = safeName.substring(0, 100);
                Files.writeString(refsDir.resolve(safeName + ".md"), ref.getValue());
            }
        }

        // 5. Copy resources/ if present
        if (!skill.getResources().isEmpty()) {
            Path resDir = targetDir.resolve("resources");
            Files.createDirectories(resDir);
            for (Map.Entry<String, String> res : skill.getResources().entrySet()) {
                Files.writeString(resDir.resolve(res.getKey()), res.getValue());
            }
        }
    }

    // ==================== Archiving ====================

    /**
     * Archive a skill: copy to _archived/ and delete the original directory.
     */
    private void archiveSkill(GeneratedSkill skill, Path sourceSkillsDir) throws IOException {
        Path skillDir = sourceSkillsDir.resolve(skill.getName());
        Path archivedDir = sourceSkillsDir.resolve("_archived").resolve(skill.getName());

        if (Files.exists(skillDir)) {
            Files.createDirectories(archivedDir.getParent());

            // Copy recursively
            try (var stream = Files.walk(skillDir)) {
                stream.forEach(source -> {
                    try {
                        Path target = archivedDir.resolve(skillDir.relativize(source));
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        logger.debug("Failed to copy during archive: {}", source);
                    }
                });
            }

            // Delete original
            try (var stream = Files.walk(skillDir).sorted(Comparator.reverseOrder())) {
                stream.forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { /* skip */ }
                });
            }

            logger.debug("Archived skill directory: {} -> {}", skillDir, archivedDir);
        } else {
            logger.debug("Skill directory not found for archiving: {}", skillDir);
        }
    }

    // ==================== CATALOG.md ====================

    /**
     * Write a CATALOG.md to the curated repo root with all published skills,
     * grouped by category and sorted by score.
     */
    private void writeCatalog(Map<String, List<SkillAssessment>> groups, Path curatedRepoPath) throws IOException {
        StringBuilder catalog = new StringBuilder();
        catalog.append("# Curated Skills Catalog\n\n");
        catalog.append("*Auto-generated by SwarmAI Skill Curator on ")
               .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
               .append("*\n\n");

        // Collect all published (passing) skills grouped by category
        Map<String, List<SkillAssessment>> byCategory = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            for (SkillAssessment sa : entry.getValue()) {
                if (sa.passesCurationBar() && sa.rankInGroup() <= DEFAULT_TOP_K) {
                    String cat = sa.skill().getCategory() != null ? sa.skill().getCategory() : "uncategorized";
                    byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(sa);
                }
            }
        }

        // Summary
        int totalPublished = byCategory.values().stream().mapToInt(List::size).sum();
        catalog.append("**Total Published Skills:** ").append(totalPublished).append("\n");
        catalog.append("**Categories:** ").append(byCategory.size()).append("\n\n");

        // Table per category
        for (var catEntry : byCategory.entrySet()) {
            catalog.append("## ").append(catEntry.getKey()).append("\n\n");
            catalog.append("| Skill | Score | Grade | Type | Version | Description |\n");
            catalog.append("|-------|-------|-------|------|---------|-------------|\n");

            List<SkillAssessment> sorted = catEntry.getValue().stream()
                .sorted(Comparator.comparingInt(SkillAssessment::totalScore).reversed())
                .collect(Collectors.toList());

            for (SkillAssessment sa : sorted) {
                GeneratedSkill skill = sa.skill();
                String desc = skill.getDescription();
                if (desc != null && desc.length() > 50) {
                    desc = desc.substring(0, 47) + "...";
                }
                catalog.append(String.format("| [%s](%s/%s/SKILL.md) | %d | %s | %s | %s | %s |\n",
                    skill.getName(),
                    catEntry.getKey(), skill.getName(),
                    sa.totalScore(), sa.grade(),
                    skill.getSkillType(), skill.getVersion(),
                    desc != null ? desc : ""));
            }
            catalog.append("\n");
        }

        Files.writeString(curatedRepoPath.resolve("CATALOG.md"), catalog.toString());
        logger.info("Wrote CATALOG.md to {}", curatedRepoPath);
    }

    // ==================== Utility ====================

    /**
     * Tokenize a skill's name + description + tags into lowercase words,
     * filtering stop words and short tokens.
     */
    private Set<String> tokenizeSkill(GeneratedSkill skill) {
        StringBuilder text = new StringBuilder();
        if (skill.getName() != null) text.append(skill.getName()).append(" ");
        if (skill.getDescription() != null) text.append(skill.getDescription()).append(" ");
        if (skill.getTags() != null) {
            skill.getTags().forEach(t -> text.append(t).append(" "));
        }
        return tokenize(text.toString());
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
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

    /**
     * Create a CompilerConfiguration matching GeneratedSkill's sandbox
     * for syntax checking.
     */
    private CompilerConfiguration createCompilerConfig() {
        CompilerConfiguration config = new CompilerConfiguration();
        ImportCustomizer imports = new ImportCustomizer();
        imports.addStarImports("java.util", "java.math", "groovy.json", "groovy.xml",
            "java.util.regex", "java.time");
        imports.addImports("java.net.URLEncoder", "java.net.URLDecoder");
        config.addCompilationCustomizers(imports);
        return config;
    }
}
