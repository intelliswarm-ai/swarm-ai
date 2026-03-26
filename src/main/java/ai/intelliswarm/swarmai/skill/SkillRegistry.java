package ai.intelliswarm.swarmai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for dynamically generated skills.
 * Stores skills, tracks usage and effectiveness, handles promotion.
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
     */
    public void register(GeneratedSkill skill) {
        // Check for existing skill with the same name
        Optional<GeneratedSkill> existing = skills.values().stream()
            .filter(s -> s.getName().equals(skill.getName()))
            .findFirst();

        if (existing.isPresent()) {
            GeneratedSkill old = existing.get();
            if (skill.getUsageCount() >= old.getUsageCount()) {
                // Replace with the newer/better one
                skills.remove(old.getId());
                skills.put(skill.getId(), skill);
                logger.info("Skill replaced: {} (old={}, new={}, usage: {}→{})",
                    skill.getName(), old.getId(), skill.getId(), old.getUsageCount(), skill.getUsageCount());
            } else {
                logger.debug("Skill '{}' already exists with higher usage — skipping duplicate (id={})",
                    skill.getName(), skill.getId());
            }
        } else {
            skills.put(skill.getId(), skill);
            logger.info("Skill registered: {} (id={}, status={})", skill.getName(), skill.getId(), skill.getStatus());
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
     * Get all skills that are validated or above (usable in workflows).
     */
    public List<GeneratedSkill> getActiveSkills() {
        return skills.values().stream()
            .filter(s -> s.getStatus() != SkillStatus.CANDIDATE)
            .collect(Collectors.toList());
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
     */
    public void recordUsage(String skillId, boolean success) {
        // Usage tracking is handled by GeneratedSkill.execute() itself
        // This method can be used for external tracking
        getById(skillId).ifPresent(skill -> {
            if (skill.meetsPromotionThreshold() && skill.getStatus() == SkillStatus.ACTIVE) {
                promote(skillId);
            }
        });
    }

    /**
     * Promote a skill that meets the threshold.
     */
    public void promote(String skillId) {
        getById(skillId).ifPresent(skill -> {
            if (skill.getUsageCount() >= PROMOTION_USAGE_THRESHOLD &&
                skill.getEffectiveness() >= PROMOTION_SUCCESS_THRESHOLD) {
                skill.setStatus(SkillStatus.PROMOTED);
                logger.info("Skill promoted: {} (usage={}, effectiveness={:.0f}%)",
                    skill.getName(), skill.getUsageCount(), skill.getEffectiveness() * 100);
            }
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
     * Get registry statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSkills", skills.size());
        stats.put("byStatus", skills.values().stream()
            .collect(Collectors.groupingBy(GeneratedSkill::getStatus, Collectors.counting())));
        stats.put("totalUsages", skills.values().stream().mapToInt(GeneratedSkill::getUsageCount).sum());
        stats.put("averageEffectiveness", skills.values().stream()
            .filter(s -> s.getUsageCount() > 0)
            .mapToDouble(GeneratedSkill::getEffectiveness)
            .average().orElse(0.0));
        return stats;
    }

    public int size() {
        return skills.size();
    }

    // ==================== Persistence ====================

    /**
     * Save all active skills to a directory as individual JSON files.
     * Each skill is saved as {name}_{id}.json with full code and metadata.
     * Also writes a SKILLS.md index file for human readability.
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
        index.append("| Name | Status | Usage | Effectiveness | Domain | Description |\n");
        index.append("|------|--------|-------|---------------|--------|-------------|\n");

        for (GeneratedSkill skill : activeSkills) {
            // Save skill as JSON
            Map<String, Object> skillData = new LinkedHashMap<>();
            skillData.put("id", skill.getId());
            skillData.put("name", skill.getName());
            skillData.put("description", skill.getDescription());
            skillData.put("domain", skill.getDomain());
            skillData.put("code", skill.getCode());
            skillData.put("parameterSchema", skill.getParameterSchema());
            skillData.put("testCases", skill.getTestCases());
            skillData.put("status", skill.getStatus().name());
            skillData.put("usageCount", skill.getUsageCount());
            skillData.put("successCount", skill.getSuccessCount());
            skillData.put("createdAt", skill.getCreatedAt().toString());

            String filename = skill.getName() + "_" + skill.getId() + ".json";
            Path skillFile = directory.resolve(filename);
            mapper.writeValue(skillFile.toFile(), skillData);

            // Add to index
            index.append(String.format("| %s | %s | %d | %.0f%% | %s | %s |\n",
                skill.getName(), skill.getStatus(), skill.getUsageCount(),
                skill.getEffectiveness() * 100, skill.getDomain(),
                skill.getDescription().length() > 50
                    ? skill.getDescription().substring(0, 47) + "..."
                    : skill.getDescription()));
        }

        // Write index file
        index.append("\n\n## Skill Details\n\n");
        for (GeneratedSkill skill : activeSkills) {
            index.append("### ").append(skill.getName()).append("\n");
            index.append("- **ID:** ").append(skill.getId()).append("\n");
            index.append("- **Description:** ").append(skill.getDescription()).append("\n");
            index.append("- **Status:** ").append(skill.getStatus()).append("\n");
            index.append("- **Usage:** ").append(skill.getUsageCount())
                .append(" (").append(String.format("%.0f", skill.getEffectiveness() * 100)).append("% success)\n");
            index.append("- **Code:**\n```groovy\n").append(skill.getCode()).append("\n```\n\n");
        }

        Files.writeString(directory.resolve("SKILLS.md"), index.toString());

        logger.info("Saved {} skills to {}", activeSkills.size(), directory);
    }

    /**
     * Load skills from a directory of JSON files.
     * Previously saved skills are restored with their code and metadata.
     */
    public int load(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            logger.info("Skill directory does not exist: {}", directory);
            return 0;
        }

        ObjectMapper mapper = new ObjectMapper();
        int loaded = 0;

        try (var stream = Files.list(directory)) {
            List<Path> skillFiles = stream
                .filter(p -> p.toString().endsWith(".json"))
                .collect(Collectors.toList());

            for (Path skillFile : skillFiles) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = mapper.readValue(skillFile.toFile(), Map.class);

                    String name = (String) data.get("name");
                    String description = (String) data.get("description");
                    String domain = (String) data.getOrDefault("domain", "generated");
                    String code = (String) data.get("code");

                    @SuppressWarnings("unchecked")
                    Map<String, Object> schema = (Map<String, Object>) data.get("parameterSchema");

                    @SuppressWarnings("unchecked")
                    List<String> testCases = (List<String>) data.get("testCases");

                    if (code == null || code.isEmpty()) {
                        logger.warn("Skipping skill file with no code: {}", skillFile);
                        continue;
                    }

                    GeneratedSkill skill = new GeneratedSkill(name, description, domain, code, schema, testCases);

                    String statusStr = (String) data.getOrDefault("status", "VALIDATED");
                    try {
                        skill.setStatus(SkillStatus.valueOf(statusStr));
                    } catch (IllegalArgumentException e) {
                        skill.setStatus(SkillStatus.VALIDATED);
                    }

                    register(skill);
                    loaded++;
                    logger.info("Loaded skill from file: {} ({})", name, skill.getId());

                } catch (Exception e) {
                    logger.warn("Failed to load skill from {}: {}", skillFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to read skill directory: {}", directory, e);
        }

        logger.info("Loaded {} skills from {}", loaded, directory);
        return loaded;
    }
}
