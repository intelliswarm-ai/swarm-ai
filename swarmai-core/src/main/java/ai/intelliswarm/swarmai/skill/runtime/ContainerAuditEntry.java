package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit record of one container skill execution. Accumulated by
 * {@link ContainerSkillRuntime#auditLog()} for telemetry, post-incident
 * review, and "what did the framework run on this host?" debugging.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@link #at} — the wall-clock timestamp when the run started.</li>
 *   <li>{@link #containerName} — the {@code swarmai-skill-<uuid8>} name the
 *       runtime gave the container. Useful for cross-referencing with
 *       {@code docker events} output.</li>
 *   <li>{@link #image} — the resolved image tag actually used (post-build for
 *       Dockerfile specs, identical to {@code spec.image()} for pulled tags).</li>
 *   <li>{@link #network} — the network mode the runtime applied.</li>
 *   <li>{@link #memory} — the memory cap, or null when unlimited.</li>
 *   <li>{@link #timeoutSeconds} — the wall-clock timeout configured.</li>
 *   <li>{@link #durationMs} — observed wall-clock from start to terminal.</li>
 *   <li>{@link #exitCode} — process exit code; -1 indicates a timeout or
 *       framework-level failure rather than a clean exit.</li>
 *   <li>{@link #stderrPreview} — first 200 chars of stderr (if non-zero exit)
 *       to support audit without storing megabytes of scan output.</li>
 *   <li>{@link #policyOutcome} — the verdict the {@link ContainerPolicy} returned
 *       before the run (passes that proceeded show {@code "OK"}; rejected
 *       runs show the violation list and never actually executed).</li>
 * </ul>
 */
public record ContainerAuditEntry(
        Instant at,
        String containerName,
        String image,
        String network,
        String memory,
        long timeoutSeconds,
        long durationMs,
        int exitCode,
        String stderrPreview,
        String policyOutcome) {

    public ContainerAuditEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(containerName, "containerName");
        if (image == null) image = "";
        if (network == null) network = "";
        if (stderrPreview == null) stderrPreview = "";
        if (policyOutcome == null) policyOutcome = "";
    }

    /**
     * Build an entry for a run that was admitted by policy and actually
     * executed. {@code stderrPreview} is auto-truncated to 200 chars.
     */
    public static ContainerAuditEntry ofExecution(
            String containerName,
            ContainerSkillSpec spec,
            String resolvedImage,
            int exitCode,
            String stderr,
            Duration duration) {
        return new ContainerAuditEntry(
                Instant.now(),
                containerName,
                resolvedImage,
                spec.network(),
                spec.memory(),
                spec.timeout().toSeconds(),
                duration.toMillis(),
                exitCode,
                stderr == null ? "" : (stderr.length() > 200 ? stderr.substring(0, 200) + "…" : stderr),
                "OK");
    }

    /**
     * Build an entry for a run that policy rejected before execution.
     * {@code containerName} is set to {@code "(rejected)"} since no
     * container was created.
     */
    public static ContainerAuditEntry ofPolicyDenial(
            ContainerSkillSpec spec,
            ContainerPolicy.PolicyResult result) {
        return new ContainerAuditEntry(
                Instant.now(),
                "(rejected)",
                spec == null ? "" : (spec.hasImage() ? spec.image() : "(dockerfile)"),
                spec == null ? "" : spec.network(),
                spec == null ? null : spec.memory(),
                spec == null ? 0 : spec.timeout().toSeconds(),
                0,
                -2, // sentinel: policy-rejected, never ran
                "",
                result.summary());
    }

    /** Pretty one-line summary, useful when dumping the log to stdout. */
    public String summary() {
        return at + " " + containerName
                + " image=" + image
                + " net=" + network
                + " exit=" + exitCode
                + " durMs=" + durationMs
                + " policy=" + policyOutcome
                + (stderrPreview.isEmpty() ? "" : " stderr=" + stderrPreview);
    }
}
