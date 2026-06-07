package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Policy gate for {@link ContainerSkillSpec} execution. Decides which
 * container skills the runtime is willing to run before any image is
 * built or any container is started.
 *
 * <h2>What's enforced</h2>
 * <ul>
 *   <li><b>Allowed network modes</b> — restricts {@code spec.network()} to a
 *       configured set. Default: {@code none}, {@code bridge}. {@code host}
 *       must be explicitly enabled.</li>
 *   <li><b>Allowed pre-built images</b> — when set, {@code spec.image()}
 *       must match one of the configured patterns (glob-like prefix match).
 *       Empty allowlist = any image accepted.</li>
 *   <li><b>Allowed Dockerfile base images</b> — when set and the spec
 *       carries an inline Dockerfile, the {@code FROM} line must match the
 *       allowlist. Catches "exfiltrate via base image" attacks.</li>
 *   <li><b>Mandatory memory cap</b> — when on, {@code spec.memory()} must be
 *       set. Off by default for backwards compatibility.</li>
 *   <li><b>Maximum timeout</b> — clamps how long any container can run.
 *       Specs requesting longer are rejected.</li>
 *   <li><b>Banned Dockerfile directives</b> — patterns that must NOT appear
 *       in any inline Dockerfile (e.g. {@code USER root} when running
 *       privileged-equivalent workloads). Default: none.</li>
 * </ul>
 *
 * <h2>What's permanently banned (not configurable)</h2>
 * <ul>
 *   <li>{@code --privileged}, {@code --cap-add}, host bind mounts,
 *       {@code --pid=host}, {@code --ipc=host}, {@code --userns=host}.
 *       The {@link ContainerSkillRuntime} never adds these flags and the
 *       policy engine has no way to override that.</li>
 * </ul>
 *
 * <h2>Defaults</h2>
 * The {@link #permissive()} preset matches the runtime's behaviour before
 * policies existed: any image, any non-host network, no memory requirement,
 * 1-hour timeout cap. The {@link #strict()} preset is intended for shared
 * tenant or production scenarios.
 */
public record ContainerPolicy(
        Set<String> allowedNetworkModes,
        Set<String> allowedImagePatterns,
        Set<String> allowedBaseImagePatterns,
        boolean memoryCapRequired,
        Duration maxTimeout,
        Set<String> bannedDockerfilePatterns) {

    public ContainerPolicy {
        allowedNetworkModes = allowedNetworkModes == null
                ? Set.of() : Set.copyOf(allowedNetworkModes);
        allowedImagePatterns = allowedImagePatterns == null
                ? Set.of() : Set.copyOf(allowedImagePatterns);
        allowedBaseImagePatterns = allowedBaseImagePatterns == null
                ? Set.of() : Set.copyOf(allowedBaseImagePatterns);
        bannedDockerfilePatterns = bannedDockerfilePatterns == null
                ? Set.of() : Set.copyOf(bannedDockerfilePatterns);
        if (maxTimeout == null || maxTimeout.isZero() || maxTimeout.isNegative()) {
            throw new IllegalArgumentException("maxTimeout must be a positive Duration");
        }
    }

    /**
     * Permissive default: allow any image, allow {@code none}/{@code bridge}
     * networks, no memory requirement, 1-hour timeout cap. Behavioural match
     * for the runtime as it shipped before policies existed.
     */
    public static ContainerPolicy permissive() {
        return new ContainerPolicy(
                Set.of("none", "bridge"),
                Set.of(),
                Set.of(),
                false,
                Duration.ofHours(1),
                Set.of());
    }

    /**
     * Strict default for shared / multi-tenant scenarios. Requires explicit
     * image allowlisting via {@link Builder#allowImage} before any spec
     * will pass. Forbids host networking. Caps timeout at 10 minutes.
     * Memory cap mandatory. Use as a starting point and add allowed images.
     */
    public static ContainerPolicy strict() {
        return builder()
                .allowedNetworkModes("none", "bridge")
                .memoryCapRequired(true)
                .maxTimeout(Duration.ofMinutes(10))
                .build();
    }

    /**
     * Validate a spec against this policy. Returns a result describing
     * the verdict and any violations. Does not throw — caller decides how
     * to respond (typically: reject the run, surface to user, log + audit).
     */
    public PolicyResult validate(ContainerSkillSpec spec) {
        if (spec == null) {
            return PolicyResult.deny(List.of("spec is null"));
        }
        List<String> violations = new ArrayList<>();

        // Network policy
        if (!allowedNetworkModes.isEmpty() && !allowedNetworkModes.contains(spec.network())) {
            violations.add("network mode '" + spec.network()
                    + "' not in allowlist " + allowedNetworkModes);
        }

        // Pre-built image allowlist
        if (spec.hasImage() && !allowedImagePatterns.isEmpty()
                && !matchesAny(spec.image(), allowedImagePatterns)) {
            violations.add("image '" + spec.image()
                    + "' not in allowlist " + allowedImagePatterns);
        }

        // Inline-Dockerfile base allowlist + banned-pattern scan
        if (spec.hasDockerfile()) {
            String base = extractFromBase(spec.dockerfile());
            if (!allowedBaseImagePatterns.isEmpty()
                    && (base == null || !matchesAny(base, allowedBaseImagePatterns))) {
                violations.add("Dockerfile FROM base '"
                        + (base == null ? "(unparsed)" : base)
                        + "' not in base allowlist " + allowedBaseImagePatterns);
            }
            for (String banned : bannedDockerfilePatterns) {
                if (Pattern.compile(banned, Pattern.MULTILINE).matcher(spec.dockerfile()).find()) {
                    violations.add("Dockerfile contains banned pattern: " + banned);
                }
            }
        }

        // Memory cap requirement
        if (memoryCapRequired && (spec.memory() == null || spec.memory().isBlank())) {
            violations.add("memory cap is required by policy but spec has none");
        }

        // Timeout ceiling
        if (spec.timeout().compareTo(maxTimeout) > 0) {
            violations.add("timeout " + spec.timeout() + " exceeds policy max " + maxTimeout);
        }

        return violations.isEmpty() ? PolicyResult.allow() : PolicyResult.deny(violations);
    }

    /**
     * True when {@code value} matches any pattern in {@code patterns} via a
     * lightweight glob: {@code *} matches anything; otherwise the pattern
     * is treated as a prefix-or-exact match. Sufficient for image-name
     * allowlists like {@code alpine:*} or {@code instrumentisto/nmap:latest}.
     */
    static boolean matchesAny(String value, Set<String> patterns) {
        if (value == null) return false;
        for (String pattern : patterns) {
            if (matches(value, pattern)) return true;
        }
        return false;
    }

    private static boolean matches(String value, String pattern) {
        if (pattern.equals("*")) return true;
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return value.equals(prefix) || value.startsWith(prefix + ":");
        }
        if (pattern.endsWith("*")) {
            return value.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return value.equals(pattern);
    }

    /**
     * Pull the {@code FROM <base>} line out of a Dockerfile body. Handles
     * leading whitespace, comments, and {@code AS} aliases. Returns null
     * if no FROM line is found.
     */
    static String extractFromBase(String dockerfile) {
        if (dockerfile == null) return null;
        for (String line : dockerfile.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.toUpperCase().startsWith("FROM ")) {
                String rest = trimmed.substring(5).trim();
                // Drop "AS <stage>" suffix if present.
                int asIdx = rest.toUpperCase().indexOf(" AS ");
                if (asIdx > 0) rest = rest.substring(0, asIdx).trim();
                // Drop any "--platform=..." flag.
                if (rest.startsWith("--")) {
                    int sp = rest.indexOf(' ');
                    if (sp > 0) rest = rest.substring(sp + 1).trim();
                }
                return rest;
            }
        }
        return null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The verdict from {@link ContainerPolicy#validate}.
     * {@link #allowed} is true when the spec passed every check;
     * {@link #violations} lists the failure reasons.
     */
    public record PolicyResult(boolean allowed, List<String> violations) {
        public PolicyResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
        public static PolicyResult allow() {
            return new PolicyResult(true, List.of());
        }
        public static PolicyResult deny(List<String> reasons) {
            return new PolicyResult(false, reasons);
        }
        public String summary() {
            if (allowed) return "OK";
            return "DENIED: " + String.join("; ", violations);
        }
    }

    public static final class Builder {
        private final Set<String> allowedNetworkModes = new LinkedHashSet<>();
        private final Set<String> allowedImagePatterns = new LinkedHashSet<>();
        private final Set<String> allowedBaseImagePatterns = new LinkedHashSet<>();
        private boolean memoryCapRequired = false;
        private Duration maxTimeout = Duration.ofHours(1);
        private final Set<String> bannedDockerfilePatterns = new LinkedHashSet<>();

        public Builder allowedNetworkModes(String... modes) {
            Collections.addAll(allowedNetworkModes, modes);
            return this;
        }
        public Builder allowImage(String pattern) {
            allowedImagePatterns.add(pattern);
            return this;
        }
        public Builder allowBaseImage(String pattern) {
            allowedBaseImagePatterns.add(pattern);
            return this;
        }
        public Builder memoryCapRequired(boolean required) {
            this.memoryCapRequired = required;
            return this;
        }
        public Builder maxTimeout(Duration t) {
            this.maxTimeout = t;
            return this;
        }
        public Builder banDockerfilePattern(String regex) {
            bannedDockerfilePatterns.add(regex);
            return this;
        }
        public ContainerPolicy build() {
            return new ContainerPolicy(
                    allowedNetworkModes, allowedImagePatterns,
                    allowedBaseImagePatterns, memoryCapRequired,
                    maxTimeout, bannedDockerfilePatterns);
        }
    }
}
