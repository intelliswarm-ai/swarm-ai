package ai.intelliswarm.swarmai.tool.base;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares runtime requirements for a tool: environment variables, binaries, services, and OS constraints.
 * Enables pre-flight validation before assigning tools to agents.
 */
public record ToolRequirements(
    List<String> env,
    List<String> bins,
    List<String> services,
    List<String> os
) {
    public static final ToolRequirements NONE = new ToolRequirements(List.of(), List.of(), List.of(), List.of());

    /**
     * Check if all requirements are satisfied in the current environment.
     * Returns a list of unmet requirements (empty = all satisfied).
     */
    public List<String> checkSatisfied() {
        List<String> unmet = new ArrayList<>();

        for (String envVar : env) {
            String value = System.getenv(envVar);
            if (value == null || value.isBlank()) {
                // Also check Spring-style property fallback via system properties
                String sysProp = System.getProperty(envVar);
                if (sysProp == null || sysProp.isBlank()) {
                    unmet.add("Missing environment variable: " + envVar);
                }
            }
        }

        for (String bin : bins) {
            try {
                Process p = new ProcessBuilder("which", bin).start();
                int exit = p.waitFor();
                if (exit != 0) {
                    unmet.add("Missing binary on PATH: " + bin);
                }
            } catch (Exception e) {
                unmet.add("Cannot check binary '" + bin + "': " + e.getMessage());
            }
        }

        if (!os.isEmpty()) {
            String currentOs = System.getProperty("os.name", "").toLowerCase();
            boolean supported = os.stream().anyMatch(platform -> {
                String p = platform.toLowerCase();
                return currentOs.contains(p) ||
                       ("linux".equals(p) && currentOs.contains("linux")) ||
                       ("darwin".equals(p) && currentOs.contains("mac")) ||
                       ("win32".equals(p) && currentOs.contains("windows"));
            });
            if (!supported) {
                unmet.add("Unsupported OS: " + currentOs + " (requires: " + os + ")");
            }
        }

        // Services are noted but not actively probed (would need service-specific checks)
        // They serve as documentation for operators

        return unmet;
    }

    public boolean isEmpty() {
        return env.isEmpty() && bins.isEmpty() && services.isEmpty() && os.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<String> env = new ArrayList<>();
        private List<String> bins = new ArrayList<>();
        private List<String> services = new ArrayList<>();
        private List<String> os = new ArrayList<>();

        public Builder env(String... vars) {
            this.env.addAll(List.of(vars));
            return this;
        }

        public Builder bins(String... binaries) {
            this.bins.addAll(List.of(binaries));
            return this;
        }

        public Builder services(String... svcs) {
            this.services.addAll(List.of(svcs));
            return this;
        }

        public Builder os(String... platforms) {
            this.os.addAll(List.of(platforms));
            return this;
        }

        public ToolRequirements build() {
            return new ToolRequirements(
                List.copyOf(env), List.copyOf(bins),
                List.copyOf(services), List.copyOf(os)
            );
        }
    }
}
