package ai.intelliswarm.swarmai.skill;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of a skill at a specific version.
 * Tracks the evolution of generated skills through refinement cycles.
 */
public record SkillVersion(
    String version,
    String code,
    String description,
    LocalDateTime createdAt,
    String changeReason,
    int usageCount,
    int successCount
) {
    /**
     * Parse a version string into its components for comparison.
     * Follows SemVer: major.minor.patch
     */
    public int[] semverParts() {
        String[] parts = version.split("\\.");
        return new int[] {
            parts.length > 0 ? Integer.parseInt(parts[0]) : 1,
            parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
            parts.length > 2 ? Integer.parseInt(parts[2]) : 0
        };
    }

    /**
     * Bump the patch version (e.g., 1.0.0 -> 1.0.1).
     */
    public static String bumpPatch(String currentVersion) {
        int[] parts = parseVersion(currentVersion);
        return parts[0] + "." + parts[1] + "." + (parts[2] + 1);
    }

    /**
     * Bump the minor version (e.g., 1.0.3 -> 1.1.0).
     */
    public static String bumpMinor(String currentVersion) {
        int[] parts = parseVersion(currentVersion);
        return parts[0] + "." + (parts[1] + 1) + ".0";
    }

    /**
     * Bump the major version (e.g., 1.2.3 -> 2.0.0).
     */
    public static String bumpMajor(String currentVersion) {
        int[] parts = parseVersion(currentVersion);
        return (parts[0] + 1) + ".0.0";
    }

    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) return new int[]{1, 0, 0};
        String[] parts = version.split("\\.");
        return new int[] {
            parts.length > 0 ? Integer.parseInt(parts[0]) : 1,
            parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
            parts.length > 2 ? Integer.parseInt(parts[2]) : 0
        };
    }
}
