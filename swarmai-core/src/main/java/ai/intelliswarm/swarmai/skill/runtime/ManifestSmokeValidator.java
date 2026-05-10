package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Capability;
import ai.intelliswarm.swarmai.skill.runtime.CapabilityManifest.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a smoke test against every capability declared in a
 * {@link CapabilityManifest} and reports which ones pass / fail. The
 * point isn't to functionally test a tool (that's the user's domain
 * problem) — it's to <em>verify the declaration is honest</em>: the
 * capability actually exists in the image, accepts the declared
 * parameters, and produces some non-empty output.
 *
 * <h2>Why this is essential for trusting LLM-generated manifests</h2>
 * The dynamic capability loop assumes Codex or another LLM will emit
 * manifests that accurately describe the image. LLMs hallucinate. A
 * manifest can declare {@code kali_nmap} but the image might not have
 * nmap installed; or the entrypoint might be wrong; or the env-var
 * name is different. Without smoke validation we'd register a fake
 * tool with the agent and confuse it for the next 30 turns.
 *
 * <h2>What "passes" means</h2>
 * For each capability we run the underlying container with a
 * caller-supplied sample input (or a synthetic default). The tool
 * passes if all three hold:
 * <ol>
 *   <li>Container exits zero, OR exits non-zero but produced
 *       non-empty stdout (many security tools exit non-zero on
 *       findings, which is still a successful invocation).</li>
 *   <li>Stdout is non-empty after trimming.</li>
 *   <li>Wall-clock time is below 80% of the declared timeout —
 *       a tool right at the timeout is suspicious.</li>
 * </ol>
 *
 * <h2>What this is NOT</h2>
 * <ul>
 *   <li>Not a security audit. The manifest could still describe a tool
 *       that does something malicious; that's the {@link ContainerPolicy}
 *       and {@link ImageApprovalGate}'s job to catch.</li>
 *   <li>Not a correctness check. {@code kali_nmap} could "work" on
 *       127.0.0.1 here and still be useless on real targets.</li>
 *   <li>Not free. Each smoke test is one full container run. For 18
 *       tools that's ~30-60 seconds. Run it once after building, cache
 *       the result keyed by manifest hash.</li>
 * </ul>
 */
public final class ManifestSmokeValidator {

    private static final Logger logger = LoggerFactory.getLogger(ManifestSmokeValidator.class);

    /**
     * Default safe sample inputs the validator falls back to when the
     * caller hasn't supplied one for a parameter. {@code 127.0.0.1} for
     * {@code target}; {@code example.com} for {@code domain}; etc. These
     * cover the most common pentest-tool parameter shapes; any tool
     * with non-standard parameter names should be supplied with a
     * specific sample input by the caller.
     */
    public static final Map<String, String> DEFAULT_SAMPLES = Map.of(
            "target", "127.0.0.1",
            "host", "127.0.0.1",
            "domain", "example.com",
            "url", "http://127.0.0.1",
            "query", "linux",
            "hash", "5f4dcc3b5aa765d61d8327deb882cf99");

    private final ContainerSkillRuntime runtime;
    private final ManifestToolFactory factory;

    public ManifestSmokeValidator(ContainerSkillRuntime runtime,
                                  ManifestToolFactory factory) {
        this.runtime = runtime;
        this.factory = factory;
    }

    /**
     * Run smoke checks against every capability in the manifest.
     *
     * @param manifest      the manifest to validate
     * @param customSamples optional per-capability parameter overrides; key is
     *                      the capability name, value is the param map.
     *                      Capabilities without a custom entry use
     *                      {@link #DEFAULT_SAMPLES}.
     * @return one {@link Result} per capability, in declaration order
     */
    public List<Result> validate(CapabilityManifest manifest,
                                 Map<String, Map<String, Object>> customSamples) {
        Map<String, Map<String, Object>> samples = customSamples == null
                ? Map.of()
                : customSamples;
        List<Result> results = new ArrayList<>(manifest.size());
        for (Capability cap : manifest.tools()) {
            results.add(validateOne(cap, samples.get(cap.name())));
        }
        long passed = results.stream().filter(Result::pass).count();
        logger.info("[smoke] {}/{} capabilities passed (manifest: {})",
                passed, results.size(), manifest.name());
        return results;
    }

    private Result validateOne(Capability cap, Map<String, Object> customParams) {
        Map<String, Object> params = customParams != null
                ? customParams
                : defaultParamsFor(cap);

        // Materialise just this one capability so we can call it as a BaseTool.
        var tool = factory.toTool(cap);

        Instant start = Instant.now();
        Object output;
        String failureReason = null;
        try {
            output = tool.execute(params);
        } catch (RuntimeException e) {
            return new Result(cap.name(), false,
                    "execution threw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Duration.between(start, Instant.now()));
        }
        Duration elapsed = Duration.between(start, Instant.now());
        Duration declared = cap.timeout();

        // Check: produced something, didn't take the entire timeout.
        String s = output == null ? "" : output.toString().trim();
        if (s.isEmpty() || s.equals("(no output)")) {
            failureReason = "tool produced no output";
        } else if (s.startsWith("Error:")) {
            failureReason = "tool returned an error string: " + abbreviate(s, 120);
        } else if (elapsed.compareTo(declared.multipliedBy(8).dividedBy(10)) >= 0) {
            failureReason = "ran for " + elapsed.toSeconds()
                    + "s — at or near declared timeout (" + declared.toSeconds() + "s)";
        }

        return new Result(cap.name(), failureReason == null, failureReason, elapsed);
    }

    /** Build a parameter map from {@link #DEFAULT_SAMPLES} for a capability's required params. */
    private static Map<String, Object> defaultParamsFor(Capability cap) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Parameter p : cap.parameters()) {
            if (!p.required()) continue;
            String sample = DEFAULT_SAMPLES.get(p.name());
            if (sample == null) {
                // Last-ditch fallback: a string that's clearly a placeholder
                // so failures are easy to attribute to a missing default.
                sample = "smoke-test-placeholder";
            }
            params.put(p.name(), sample);
        }
        return params;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /**
     * Smoke-test result for one capability.
     *
     * @param toolName       the capability that was tested
     * @param pass           true if the tool passed all three checks
     * @param failureReason  null when {@code pass}; otherwise a short,
     *                       human-readable reason
     * @param elapsed        wall-clock time the tool took to run
     */
    public record Result(String toolName, boolean pass, String failureReason, Duration elapsed) {
        public String summary() {
            return (pass ? "✓ " : "✗ ") + toolName
                 + " (" + elapsed.toMillis() + " ms)"
                 + (failureReason == null ? "" : " — " + failureReason);
        }
    }
}
