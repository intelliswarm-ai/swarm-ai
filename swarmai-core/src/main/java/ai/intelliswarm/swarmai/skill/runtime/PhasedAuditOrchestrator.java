package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a phased network audit using {@link FindingsBlackboard}
 * to pass typed observations between phases.
 *
 * <h2>Phases</h2>
 * <ol>
 *   <li><b>Recon</b> — runs nmap in a small alpine image, parses stdout
 *       into {@code PORT_OPEN} {@link Finding}s. Covers all 256 IPs of a
 *       /24 in 30-90 seconds.</li>
 *   <li><b>Web enrichment</b> — for each {@code PORT_OPEN} where service
 *       is {@code http}/{@code https}, runs whatweb in a separate image
 *       against that exact host:port. Skips entirely when no web hosts
 *       were discovered, so an audit of a network with no web servers
 *       does no wasted work.</li>
 *   <li><b>Report</b> — aggregates all findings into Markdown + JSON.</li>
 * </ol>
 *
 * <h2>Why phased</h2>
 * The monolithic single-container approach we used previously installed
 * tools (sqlmap, hydra, gobuster) and then never invoked them — they sat
 * in a 900 MB Kali image while the entrypoint chain only ran nmap+nikto.
 * The phased architecture inverts that: each phase has a small focused
 * image with one job, and tools only run when there's actual structured
 * input to feed them. {@code sqlmap} would only get pulled in once a
 * phase finds an HTTP endpoint with a query parameter to test.
 *
 * <h2>Extending with more enrichment phases</h2>
 * Adding a phase for SSH, SMB, etc. is local — write a new method that
 * reads from the blackboard, runs its container, parses output, records
 * new findings. The orchestrator's main loop dispatches based on
 * {@link Finding#service()}, so phases plug in by service type.
 */
public final class PhasedAuditOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(PhasedAuditOrchestrator.class);

    private final ContainerSkillRuntime runtime;

    public PhasedAuditOrchestrator(ContainerSkillRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Run the full phased audit against {@code targetCidr}, populating a
     * fresh {@link FindingsBlackboard} and returning it. The blackboard
     * carries every observation made; the report phase has already
     * formatted things for human consumption but the raw findings are
     * available for downstream tooling.
     */
    public AuditResult run(String targetCidr) {
        FindingsBlackboard blackboard = new FindingsBlackboard();
        long start = System.currentTimeMillis();

        logger.info("");
        logger.info("════════ Phase R: Recon (nmap discovery + service version) ════════");
        runReconPhase(blackboard, targetCidr);
        logger.info("Recon complete — {} findings", blackboard.size());

        logger.info("");
        logger.info("════════ Phase E: Web enrichment (whatweb on http/https hosts) ════════");
        Set<String> httpHosts = blackboard.hostsWithService("http");
        Set<String> httpsHosts = blackboard.hostsWithService("https");
        Set<String> webHosts = new java.util.LinkedHashSet<>();
        webHosts.addAll(httpHosts);
        webHosts.addAll(httpsHosts);
        if (webHosts.isEmpty()) {
            logger.info("No http/https services discovered on the target — skipping web enrichment.");
        } else {
            logger.info("Targeting {} web host(s): {}", webHosts.size(), webHosts);
            for (String host : webHosts) {
                runWebEnrichmentForHost(blackboard, host);
            }
        }
        logger.info("Enrichment complete — blackboard now holds {} findings", blackboard.size());

        logger.info("");
        logger.info("════════ Phase Report: aggregating findings ════════");
        String markdown = renderMarkdown(blackboard, targetCidr);
        String json = renderJson(blackboard, targetCidr);

        long elapsedMs = System.currentTimeMillis() - start;
        return new AuditResult(blackboard, markdown, json, elapsedMs);
    }

    // ---------------------------------------------------------------------
    // Phase R — recon
    // ---------------------------------------------------------------------

    private void runReconPhase(FindingsBlackboard blackboard, String cidr) {
        // Small alpine image with nmap + nmap-scripts. Same Dockerfile every
        // run → image is cached after the first audit on this host.
        String dockerfile =
                "FROM alpine:3.19\n"
              + "RUN apk add --no-cache nmap nmap-scripts\n"
              + "ENTRYPOINT [\"sh\", \"-c\", \"nmap -F -sV --top-ports 100 -T4 $TARGET\"]\n";

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ai.swarmai.phase", "recon");
        labels.put("ai.swarmai.tools", "nmap,nmap-scripts");
        labels.put("ai.swarmai.input", "cidr");
        labels.put("ai.swarmai.output", "PORT_OPEN");

        String stdout = runContainerSkill(dockerfile, labels, cidr, "recon",
                Duration.ofMinutes(5));
        if (stdout == null) {
            logger.warn("Recon phase produced no output — blackboard left empty.");
            return;
        }
        List<Finding> findings = NmapOutputParser.parse(stdout);
        blackboard.recordAll(findings);
        long openPorts = blackboard.byType("PORT_OPEN").size();
        long hosts = blackboard.byType("HOST_UP").size();
        logger.info("  {} HOST_UP, {} PORT_OPEN findings extracted", hosts, openPorts);
    }

    // ---------------------------------------------------------------------
    // Phase E — web enrichment
    // ---------------------------------------------------------------------

    private void runWebEnrichmentForHost(FindingsBlackboard blackboard, String host) {
        // Per-host whatweb run. The image is tiny (alpine + curl + whatweb deps);
        // built once, reused for every host in this run via Docker's image cache.
        String dockerfile =
                "FROM alpine:3.19\n"
              + "RUN apk add --no-cache whatweb curl\n"
              + "ENTRYPOINT [\"sh\", \"-c\", \"whatweb --quiet --no-errors -a 1 $TARGET || curl -sS -I http://$TARGET\"]\n";

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ai.swarmai.phase", "enrich-web");
        labels.put("ai.swarmai.tools", "whatweb,curl");
        labels.put("ai.swarmai.input", "host");
        labels.put("ai.swarmai.output", "HTTP_FINGERPRINT");

        String stdout = runContainerSkill(dockerfile, labels, host, "enrich-web",
                Duration.ofMinutes(2));
        if (stdout == null || stdout.isBlank()) {
            return;
        }
        // Parse whatweb's compact one-line output:
        //   http://192.168.32.1 [200] HTTPServer[lighttpd], Title[Router]
        // Extract bracketed [Plugin] or Plugin[value] tokens.
        Map<String, String> attrs = parseWhatwebPlugins(stdout);
        attrs.put("rawOutput",
                stdout.length() > 400 ? stdout.substring(0, 397) + "..." : stdout.trim());
        blackboard.record(Finding.httpFingerprint(host, 80, attrs));
    }

    /**
     * Pull plugin-name → value pairs out of whatweb's stdout. Whatweb prints
     * lines like {@code Plugin[value], Other-Plugin}; we just extract those
     * and surface them as attributes on the HTTP_FINGERPRINT finding.
     */
    static Map<String, String> parseWhatwebPlugins(String stdout) {
        Map<String, String> attrs = new LinkedHashMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([A-Za-z][A-Za-z0-9-]*)\\[([^\\]]+)\\]");
        java.util.regex.Matcher m = p.matcher(stdout);
        while (m.find()) {
            attrs.putIfAbsent(m.group(1), m.group(2));
        }
        return attrs;
    }

    // ---------------------------------------------------------------------
    // Phase Report
    // ---------------------------------------------------------------------

    static String renderMarkdown(FindingsBlackboard bb, String cidr) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Network audit report\n\n");
        sb.append("**Target:** `").append(cidr).append("`\n");
        sb.append("**Total findings:** ").append(bb.size()).append("\n\n");

        // Hosts up summary
        List<Finding> hostsUp = bb.byType("HOST_UP");
        sb.append("## Hosts up\n\n");
        if (hostsUp.isEmpty()) {
            sb.append("_(none)_\n\n");
        } else {
            for (Finding f : hostsUp) {
                sb.append("- ").append(f.host()).append("\n");
            }
            sb.append("\n");
        }

        // Open ports table
        List<Finding> portsOpen = bb.byType("PORT_OPEN");
        sb.append("## Open ports\n\n");
        if (portsOpen.isEmpty()) {
            sb.append("_(none)_\n\n");
        } else {
            sb.append("| Host | Port | Service | Version |\n");
            sb.append("|------|------|---------|---------|\n");
            for (Finding f : portsOpen) {
                sb.append("| ").append(f.host())
                  .append(" | ").append(f.port())
                  .append(" | ").append(f.service())
                  .append(" | ").append(f.attributes().getOrDefault("version", ""))
                  .append(" |\n");
            }
            sb.append("\n");
        }

        // HTTP fingerprints
        List<Finding> http = bb.byType("HTTP_FINGERPRINT");
        if (!http.isEmpty()) {
            sb.append("## HTTP fingerprints\n\n");
            for (Finding f : http) {
                sb.append("### ").append(f.host()).append("\n");
                for (var e : f.attributes().entrySet()) {
                    if ("rawOutput".equals(e.getKey())) continue;
                    sb.append("- **").append(e.getKey()).append("**: ")
                      .append(e.getValue()).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    static String renderJson(FindingsBlackboard bb, String cidr) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"target\":\"").append(escape(cidr))
          .append("\",\"findings\":[");
        boolean first = true;
        for (Finding f : bb.all()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"type\":\"").append(escape(f.type()))
              .append("\",\"host\":\"").append(escape(f.host())).append("\"");
            if (f.port() != null) sb.append(",\"port\":").append(f.port());
            if (f.service() != null) sb.append(",\"service\":\"").append(escape(f.service())).append("\"");
            if (!f.attributes().isEmpty()) {
                sb.append(",\"attributes\":{");
                boolean ka = true;
                for (var e : f.attributes().entrySet()) {
                    if (!ka) sb.append(",");
                    ka = false;
                    sb.append("\"").append(escape(e.getKey())).append("\":\"")
                      .append(escape(e.getValue())).append("\"");
                }
                sb.append("}");
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    // ---------------------------------------------------------------------
    // Common: build + run a container skill, return stdout (or null)
    // ---------------------------------------------------------------------

    private String runContainerSkill(String dockerfile, Map<String, String> labels,
                                       String target, String phaseName, Duration timeout) {
        try {
            String tag = runtime.buildImage(dockerfile, labels);
            ContainerSkillSpec spec = ContainerSkillSpec.builder(tag)
                    .envFromParam("target", "TARGET")
                    .network("bridge")
                    .memory("512m")
                    .timeout(timeout)
                    .build();
            ContainerSkillRuntime.ExecResult result = runtime.execute(spec, Map.of("target", target));
            if (result.ok()) {
                logger.info("  [{}] container exit 0, {} ms, {} bytes stdout",
                        phaseName, result.elapsed().toMillis(), result.stdout().length());
                return result.stdout();
            }
            logger.warn("  [{}] container exited {} after {} ms — stderr head: {}",
                    phaseName, result.exitCode(), result.elapsed().toMillis(),
                    abbreviate(result.stderr(), 200));
            return result.stdout(); // some tools (whatweb on no-banner) exit non-zero with useful stdout
        } catch (Exception e) {
            logger.error("  [{}] container build/run failed: {}", phaseName, e.getMessage());
            return null;
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Result of a phased audit: the blackboard + rendered Markdown + JSON. */
    public record AuditResult(
            FindingsBlackboard blackboard,
            String markdown,
            String json,
            long elapsedMs) {}
}
