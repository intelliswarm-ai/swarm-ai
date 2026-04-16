package ai.intelliswarm.swarmai.skill.runtime;

import java.util.List;

public record SecurityReport(boolean ok, List<String> violations) {

    public SecurityReport {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public static SecurityReport passed() {
        return new SecurityReport(true, List.of());
    }

    public static SecurityReport failed(List<String> violations) {
        return new SecurityReport(false, violations);
    }

    public static SecurityReport failed(String violation) {
        return new SecurityReport(false, List.of(violation));
    }
}
