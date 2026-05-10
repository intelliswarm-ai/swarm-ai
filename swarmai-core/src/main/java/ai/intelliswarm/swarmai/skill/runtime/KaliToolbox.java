package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.skill.ContainerSkillSpec;
import ai.intelliswarm.swarmai.tool.base.BaseTool;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Kali-Linux-backed tool library. Exposes the major capabilities of the
 * Kali toolset as discrete {@link BaseTool}s the agent can call directly,
 * the same way it calls a CalculatorTool or WebSearchTool.
 *
 * <h2>Design</h2>
 * <p>Every tool here points at the <em>same</em> inline Dockerfile —
 * a Kali rolling base with the union of all listed packages installed.
 * {@link ContainerSkillRuntime} hashes the Dockerfile content (SHA1) into
 * the image tag, so all 18 specs share one cached image build. Per-call
 * we just override {@code --entrypoint} to pick which tool runs. The
 * heavy build cost is paid once; subsequent tool calls are pure
 * {@code docker run} overhead.
 *
 * <h2>Categories covered</h2>
 * <ul>
 *   <li><b>Network discovery</b> — nmap, masscan, dig</li>
 *   <li><b>DNS / OSINT</b> — dnsrecon, theharvester, whois</li>
 *   <li><b>Web</b> — whatweb, nikto, gobuster, wpscan, sqlmap (banner-only)</li>
 *   <li><b>SMB / Active Directory</b> — smbmap, enum4linux, crackmapexec</li>
 *   <li><b>TLS / SSH</b> — sslscan, ssh-audit</li>
 *   <li><b>Hashes / exploits</b> — hashid, searchsploit</li>
 * </ul>
 *
 * <h2>Why these tools (not the other 297 in Kali)</h2>
 * Kali ships ~300 tools; most are variants, GUIs, or wireless/RF
 * specialties that don't fit a one-shot container model. The 18 below
 * cover every major attack surface a network agent needs to reason about,
 * each one is non-redundant (different protocol or different abstraction
 * level), and each one produces stdout that an agent can interpret. Tools
 * that need long-running interactive sessions (msfconsole), GUIs
 * (burpsuite, zaproxy), or wireless capture (aircrack-ng) are excluded
 * by design — they don't fit the BaseTool-shaped contract.
 *
 * <h2>Network mode</h2>
 * All tools run with {@code --network bridge} so they can reach the
 * target. None ask for {@code --privileged} or {@code --network host};
 * tools that strictly require raw sockets (masscan with high speed,
 * arp-scan) are configured to fall back to working-but-slower modes when
 * privileges aren't granted.
 *
 * <h2>Authorization</h2>
 * Caller must supply targets they own or are explicitly authorized to
 * test. {@link ContainerPolicy} should still wrap the runtime in
 * production deployments to enforce target allowlists.
 */
public final class KaliToolbox {

    /**
     * Shared Kali toolbox Dockerfile. ~2 GB built; built once and
     * cached. Edit cautiously — a single byte changes the SHA1 and
     * forces a rebuild for every consumer.
     */
    public static final String DOCKERFILE =
              "FROM kalilinux/kali-rolling\n"
            + "ENV DEBIAN_FRONTEND=noninteractive\n"
            + "RUN apt-get update && apt-get install -y --no-install-recommends \\\n"
            + "        nmap masscan dnsutils dnsrecon whois \\\n"
            + "        whatweb nikto gobuster wpscan sqlmap \\\n"
            + "        smbmap enum4linux-ng crackmapexec \\\n"
            + "        sslscan \\\n"
            + "        hashid \\\n"
            + "        theharvester \\\n"
            + "        exploitdb \\\n"
            + "        wordlists dirb \\\n"
            + "        python3 python3-pip ca-certificates curl \\\n"
            + "    && pip3 install --break-system-packages --no-cache-dir ssh-audit \\\n"
            + "    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*\n"
            + "ENTRYPOINT [\"/bin/bash\", \"-c\"]\n";

    private final ContainerSkillRuntime runtime;

    public KaliToolbox(ContainerSkillRuntime runtime) {
        this.runtime = runtime;
    }

    // =====================================================================
    // 1. Network discovery
    // =====================================================================

    public BaseTool nmap() {
        return adapter()
                .name("kali_nmap")
                .description("Run an nmap port scan + service-version detection. Probes the top "
                        + "100 TCP ports with -sV. Returns structured (host, port, service, "
                        + "version) findings parsed from output.")
                .triggerWhen("First step of any network audit; works on IPs, hostnames, or CIDR ranges.")
                .avoidWhen("Don't run on /16 or larger networks. Don't run against hosts you don't own.")
                .category("security/network")
                .tags(List.of("network", "discovery", "port-scan", "service-version"))
                .parameterSchema(stringParamSchema("target",
                        "Target CIDR / hostname / IP (e.g. 192.168.1.0/24, 10.0.0.5, example.com)"))
                .spec(specFor("nmap -F -sV --top-ports 100 -T4 \"$TARGET\"",
                        Map.of("target", "TARGET"), Duration.ofMinutes(6)))
                .outputParser(stdout -> formatFindings(NmapOutputParser.parse(stdout), "kali_nmap"))
                .build();
    }

    public BaseTool masscan() {
        return adapter()
                .name("kali_masscan")
                .description("Fast, asynchronous port scanner. Sweeps the top 100 TCP ports across "
                        + "an entire CIDR much faster than nmap. Output is one line per open "
                        + "(ip, port). Use as a first pass on large ranges, then nmap individual "
                        + "hosts for service detection.")
                .triggerWhen("Sweeping a /22 or larger CIDR for live ports. Fast triage before "
                        + "running deeper scans.")
                .avoidWhen("Running with --rate above 1000 from a bridge network may drop packets. "
                        + "Use nmap if you need service version, OS, or NSE scripts.")
                .category("security/network")
                .tags(List.of("network", "fast-scan", "port-discovery"))
                .parameterSchema(stringParamSchema("target",
                        "Target CIDR (e.g. 192.168.1.0/24)"))
                // --rate 800 chosen to be polite on a bridge network and avoid raw-socket loss.
                .spec(specFor("masscan --top-ports 100 --rate 800 \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(8)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool dig() {
        return adapter()
                .name("kali_dig")
                .description("Run DNS lookups: ANY, NS, MX records for a domain. Useful for "
                        + "fingerprinting a domain's mail and DNS infrastructure.")
                .triggerWhen("Target is a domain name and you want DNS-level metadata.")
                .avoidWhen("Don't run against IP addresses or CIDRs.")
                .category("security/dns")
                .tags(List.of("dns", "domain-recon"))
                .parameterSchema(stringParamSchema("domain", "Domain name (e.g. example.com)"))
                .spec(specFor(
                        "dig +short ANY \"$DOMAIN\"; dig +short NS \"$DOMAIN\"; dig +short MX \"$DOMAIN\"",
                        Map.of("domain", "DOMAIN"), Duration.ofMinutes(1)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // 2. DNS / OSINT
    // =====================================================================

    public BaseTool dnsrecon() {
        return adapter()
                .name("kali_dnsrecon")
                .description("DNS enumeration suite. Runs standard record lookups (A, AAAA, MX, "
                        + "NS, SOA, TXT, SRV) plus zone-transfer attempts.")
                .triggerWhen("Domain-name target and you want a thorough DNS audit beyond basic dig.")
                .avoidWhen("Don't run against IPs/CIDRs. Output can be 50-200 lines; truncated.")
                .category("security/dns")
                .tags(List.of("dns", "enumeration"))
                .parameterSchema(stringParamSchema("domain", "Domain name (e.g. example.com)"))
                .spec(specFor("dnsrecon -d \"$DOMAIN\" -t std 2>&1",
                        Map.of("domain", "DOMAIN"), Duration.ofMinutes(3)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool theharvester() {
        return adapter()
                .name("kali_theharvester")
                .description("OSINT gathering: collects emails, subdomains, hosts, employee names "
                        + "for a domain from public sources (DuckDuckGo, crt.sh).")
                .triggerWhen("Domain-name target during the OSINT phase, before active scanning.")
                .avoidWhen("Don't use against IPs. Some sources rate-limit aggressive querying.")
                .category("security/osint")
                .tags(List.of("osint", "subdomain-enum", "passive"))
                .parameterSchema(stringParamSchema("domain", "Domain name (e.g. example.com)"))
                .spec(specFor("theHarvester -d \"$DOMAIN\" -b duckduckgo,crtsh -l 100 2>&1",
                        Map.of("domain", "DOMAIN"), Duration.ofMinutes(3)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool whois() {
        return adapter()
                .name("kali_whois")
                .description("Look up WHOIS registration info: registrar, name servers, "
                        + "registration / expiry dates, contact emails (when not redacted).")
                .triggerWhen("Domain target, want ownership / registrar context.")
                .avoidWhen("Don't run against private IPs (no useful WHOIS).")
                .category("security/osint")
                .tags(List.of("osint", "whois"))
                .parameterSchema(stringParamSchema("target", "Domain or public IP"))
                .spec(specFor("whois \"$TARGET\"",
                        Map.of("target", "TARGET"), Duration.ofSeconds(45)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // 3. Web fingerprinting / vuln scanning
    // =====================================================================

    public BaseTool whatweb() {
        return adapter()
                .name("kali_whatweb")
                .description("Fingerprint a web server: HTTP server software, frameworks, CMS "
                        + "platforms, JavaScript libraries.")
                .triggerWhen("After nmap finds 80/443/8080 open and you want to identify the stack.")
                .avoidWhen("Don't run against hosts without a web server. Don't repeat for the same URL.")
                .category("security/web")
                .tags(List.of("web", "http", "fingerprint"))
                .parameterSchema(stringParamSchema("target",
                        "URL or host (e.g. http://192.168.1.1, https://example.com)"))
                .spec(specFor("whatweb --quiet --no-errors -a 1 \"$TARGET\"",
                        Map.of("target", "TARGET"), Duration.ofMinutes(2)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool nikto() {
        return adapter()
                .name("kali_nikto")
                .description("Web server vulnerability scanner. Checks for outdated software, "
                        + "default files, misconfigurations, and ~6700 known issues. Capped at "
                        + "60s per scan to keep response times sane.")
                .triggerWhen("After whatweb identifies a web server and you want a vuln baseline.")
                .avoidWhen("Noisy and slow on large sites. Generates IDS alerts. Don't run more "
                        + "than once per host per audit.")
                .category("security/web")
                .tags(List.of("web", "vuln-scan", "noisy"))
                .parameterSchema(stringParamSchema("target",
                        "URL or host:port (e.g. http://192.168.1.1, 10.0.0.5:8080)"))
                .spec(specFor("nikto -h \"$TARGET\" -nointeractive -Tuning 123b -maxtime 60 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(3)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool gobuster() {
        return adapter()
                .name("kali_gobuster")
                .description("Directory / file brute-forcer for web applications. Uses dirb's "
                        + "common.txt wordlist. Returns a list of paths that responded with 2xx/3xx.")
                .triggerWhen("Web server is up and you want to discover hidden paths/files.")
                .avoidWhen("Generates heavy traffic. Don't run on production targets without "
                        + "authorization. Stops at the first 50 hits.")
                .category("security/web")
                .tags(List.of("web", "content-discovery", "brute-force"))
                .parameterSchema(stringParamSchema("target",
                        "Base URL (e.g. http://192.168.1.1)"))
                .spec(specFor(
                        "gobuster dir -u \"$TARGET\" -w /usr/share/wordlists/dirb/common.txt "
                                + "-t 10 -q --no-tls-validation 2>&1 | head -60",
                        Map.of("target", "TARGET"), Duration.ofMinutes(4)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool wpscan() {
        return adapter()
                .name("kali_wpscan")
                .description("WordPress security scanner. Enumerates plugins, themes, users; "
                        + "checks for known WordPress vulnerabilities. Read-only — no exploitation.")
                .triggerWhen("whatweb / nikto identified a WordPress installation.")
                .avoidWhen("Don't run against non-WordPress sites (waste of cycles + noisy).")
                .category("security/web")
                .tags(List.of("web", "wordpress", "vuln-scan"))
                .parameterSchema(stringParamSchema("target", "WordPress site URL"))
                .spec(specFor(
                        "wpscan --url \"$TARGET\" --no-update --random-user-agent "
                                + "--enumerate u,vp --disable-tls-checks 2>&1 | head -120",
                        Map.of("target", "TARGET"), Duration.ofMinutes(5)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool sqlmapBanner() {
        return adapter()
                .name("kali_sqlmap_banner")
                .description("SQL injection probe — banner-only mode. Tests a single URL "
                        + "parameter for SQL injection and, if found, retrieves the database "
                        + "version banner. Read-only; does NOT dump data or escalate.")
                .triggerWhen("URL has query parameters (e.g. ?id=1) and you want a quick SQLi probe.")
                .avoidWhen("Don't run against URLs without parameters. Don't escalate beyond banner "
                        + "without explicit authorization.")
                .category("security/web")
                .tags(List.of("web", "sql-injection", "active"))
                .parameterSchema(stringParamSchema("target",
                        "URL with parameter (e.g. http://target/page?id=1)"))
                .spec(specFor(
                        "sqlmap -u \"$TARGET\" --batch --banner --random-agent "
                                + "--level=1 --risk=1 --timeout=30 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(5)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // 4. SMB / Active Directory
    // =====================================================================

    public BaseTool smbmap() {
        return adapter()
                .name("kali_smbmap")
                .description("Enumerate SMB shares + access permissions on a host. Tries guest "
                        + "credentials, falls back to anonymous.")
                .triggerWhen("nmap found port 445 / microsoft-ds open.")
                .avoidWhen("Don't run on hosts without SMB. Guest/anonymous only — no brute-force.")
                .category("security/smb")
                .tags(List.of("smb", "share-enumeration", "windows"))
                .parameterSchema(stringParamSchema("target", "Host with SMB open (e.g. 192.168.1.10)"))
                .spec(specFor(
                        "smbmap -H \"$TARGET\" -u guest -p '' 2>&1 || smbmap -H \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(2)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool enum4linux() {
        return adapter()
                .name("kali_enum4linux")
                .description("Comprehensive Windows / Samba enumeration: domain info, user lists, "
                        + "group lists, password policy, share lists, OS info. Read-only.")
                .triggerWhen("nmap found ports 139/445 open and you want detailed Windows enumeration.")
                .avoidWhen("Output is large (often 200+ lines); only run when SMB is confirmed open.")
                .category("security/smb")
                .tags(List.of("smb", "windows", "ad-enum"))
                .parameterSchema(stringParamSchema("target", "Host with SMB open"))
                .spec(specFor("enum4linux-ng \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(4)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool crackmapexecSmb() {
        return adapter()
                .name("kali_crackmapexec_smb")
                .description("CrackMapExec SMB module — fingerprints OS / domain / signing on a "
                        + "host. Read-only fingerprint mode (no auth attempts).")
                .triggerWhen("Want a one-line summary of an SMB host's domain and signing posture.")
                .avoidWhen("Don't pass credentials here. Use enum4linux for share details.")
                .category("security/smb")
                .tags(List.of("smb", "windows", "fingerprint"))
                .parameterSchema(stringParamSchema("target", "Host with SMB open"))
                .spec(specFor("crackmapexec smb \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(2)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // 5. TLS / SSH
    // =====================================================================

    public BaseTool sslscan() {
        return adapter()
                .name("kali_sslscan")
                .description("Probe a TLS endpoint's supported cipher suites, protocols, "
                        + "certificate details, and known weaknesses (Heartbleed etc.).")
                .triggerWhen("nmap found port 443 / 8443 / any TLS service.")
                .avoidWhen("Don't run on plain-HTTP endpoints.")
                .category("security/tls")
                .tags(List.of("tls", "cipher-audit"))
                .parameterSchema(stringParamSchema("target", "host:port (e.g. example.com:443)"))
                .spec(specFor("sslscan --no-colour \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(2)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool sshAudit() {
        return adapter()
                .name("kali_ssh_audit")
                .description("Audit an SSH server's algorithms, ciphers, KEX exchanges, and host "
                        + "keys. Flags weak / deprecated crypto.")
                .triggerWhen("nmap found port 22 / any ssh service.")
                .avoidWhen("Don't run on non-SSH hosts. Read-only — no auth attempts.")
                .category("security/ssh")
                .tags(List.of("ssh", "crypto-audit"))
                .parameterSchema(stringParamSchema("target", "Host with SSH open (e.g. 192.168.1.5)"))
                .spec(specFor("ssh-audit \"$TARGET\" 2>&1",
                        Map.of("target", "TARGET"), Duration.ofMinutes(2)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // 6. Hashes / exploits
    // =====================================================================

    public BaseTool hashid() {
        return adapter()
                .name("kali_hashid")
                .description("Identify the type of a hash: MD5, SHA-*, NTLM, bcrypt, etc. "
                        + "Useful when you've recovered a hash and need to know which cracker "
                        + "to point at it.")
                .triggerWhen("You have a hash string and need to identify its algorithm.")
                .avoidWhen("Don't pass cleartext or non-hash data — output will be noisy.")
                .category("security/hash")
                .tags(List.of("hash", "identification"))
                .parameterSchema(stringParamSchema("hash",
                        "Hash string (e.g. 5f4dcc3b5aa765d61d8327deb882cf99)"))
                .spec(specFor("hashid -m \"$HASH\"",
                        Map.of("hash", "HASH"), Duration.ofSeconds(15)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    public BaseTool searchsploit() {
        return adapter()
                .name("kali_searchsploit")
                .description("Search the offline Exploit-DB for known exploits matching a "
                        + "product, version, or CVE. Offline — no network calls.")
                .triggerWhen("nmap or whatweb identified a specific product+version and you want "
                        + "to know if public exploits exist.")
                .avoidWhen("Vague queries return many false positives. Include version when known.")
                .category("security/exploits")
                .tags(List.of("exploit-db", "cve", "search"))
                .parameterSchema(stringParamSchema("query",
                        "Search query (e.g. 'apache 2.4.49', 'CVE-2021-41773')"))
                .spec(specFor("searchsploit \"$QUERY\" --colour=never 2>&1 | head -40",
                        Map.of("query", "QUERY"), Duration.ofSeconds(20)))
                .outputParser(KaliToolbox::truncateForAgent)
                .build();
    }

    // =====================================================================
    // Aggregate
    // =====================================================================

    /** Returns every tool in the library, ready to register on an Agent. */
    public List<BaseTool> all() {
        return List.of(
                nmap(), masscan(), dig(),
                dnsrecon(), theharvester(), whois(),
                whatweb(), nikto(), gobuster(), wpscan(), sqlmapBanner(),
                smbmap(), enum4linux(), crackmapexecSmb(),
                sslscan(), sshAudit(),
                hashid(), searchsploit());
    }

    // =====================================================================
    // Internals
    // =====================================================================

    private ContainerBackedBaseTool.Builder adapter() {
        return ContainerBackedBaseTool.builder().runtime(runtime);
    }

    /**
     * Build a spec that runs {@code shellCommand} inside the shared Kali
     * toolbox image. Each tool gets its own command + env-var mappings +
     * timeout, but they all share the SHA1-derived image tag so the
     * Dockerfile only builds once.
     */
    private static ContainerSkillSpec specFor(String shellCommand,
                                              Map<String, String> paramToEnv,
                                              Duration timeout) {
        ContainerSkillSpec.Builder b = ContainerSkillSpec.builderFromDockerfile(DOCKERFILE)
                .entrypoint("/bin/bash")
                .command(List.of("-c", shellCommand))
                .network("bridge")
                .memory("1024m")
                .timeout(timeout);
        paramToEnv.forEach(b::envFromParam);
        return b.build();
    }

    private static Map<String, Object> stringParamSchema(String paramName, String description) {
        return Map.of(
                "type", "object",
                "properties", Map.of(paramName, Map.of(
                        "type", "string",
                        "description", description)),
                "required", List.of(paramName));
    }

    private static String formatFindings(List<Finding> findings, String toolName) {
        if (findings == null || findings.isEmpty()) {
            return toolName + ": no findings";
        }
        StringBuilder sb = new StringBuilder(toolName)
                .append(": ").append(findings.size()).append(" finding(s)\n");
        for (Finding f : findings) {
            sb.append("  - ").append(f.summary()).append("\n");
        }
        return sb.toString();
    }

    private static String truncateForAgent(String stdout) {
        if (stdout == null) return "(no output)";
        String s = stdout.trim();
        if (s.isEmpty()) return "(no output)";
        if (s.length() > 1500) s = s.substring(0, 1497) + "...";
        return s;
    }

    @SuppressWarnings("unused")
    private static <T, R> Function<T, R> identity(Function<T, R> f) { return f; }
}
