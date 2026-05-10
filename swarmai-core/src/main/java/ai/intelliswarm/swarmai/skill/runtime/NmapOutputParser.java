package ai.intelliswarm.swarmai.skill.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@link Finding}s from nmap stdout (text format).
 *
 * <p>Handles the standard nmap output sections:
 * <pre>
 * Nmap scan report for hostname (1.2.3.4)
 * Host is up (0.0021s latency).
 * PORT     STATE   SERVICE       VERSION
 * 22/tcp   open    ssh           OpenSSH 9.6p1
 * 80/tcp   open    http          lighttpd
 * 443/tcp  open    ssl/http      lighttpd
 * </pre>
 *
 * <p>Emits:
 * <ul>
 *   <li>{@code HOST_UP} for each "Host is up" line</li>
 *   <li>{@code PORT_OPEN} for each open port row, with service banner</li>
 * </ul>
 *
 * <p>Service normalisation: {@code ssl/http} and {@code https} both map
 * to {@code service="https"} so {@link FindingsBlackboard#hostsWithService}
 * queries are clean. Likewise {@code microsoft-ds} → {@code smb}.
 */
public final class NmapOutputParser {

    private NmapOutputParser() {}

    // "Nmap scan report for [name (]a.b.c.d[)]" — captures the IP
    // (preferring the parenthesised one when both name + IP are present).
    private static final Pattern HEADER = Pattern.compile(
            "^Nmap scan report for (?:[^\\s(]+\\s*\\(([^)]+)\\)|(\\S+))\\s*$");

    private static final Pattern HOST_UP = Pattern.compile(
            "^Host is up\\b.*$");

    // PORT row: e.g. "80/tcp   open  http      lighttpd 1.4.x"
    // Captures: 1=port  2=proto  3=service  4=version (optional)
    private static final Pattern PORT_OPEN = Pattern.compile(
            "^\\s*(\\d+)/(tcp|udp)\\s+open\\s+(\\S+)\\s*(.*)$");

    /**
     * Parse an nmap stdout blob into findings, keeping host context
     * across lines (each "Nmap scan report for X" resets the active host).
     */
    public static List<Finding> parse(String stdout) {
        List<Finding> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return out;

        String currentHost = null;
        for (String line : stdout.split("\\r?\\n")) {
            Matcher h = HEADER.matcher(line);
            if (h.matches()) {
                currentHost = h.group(1) != null ? h.group(1) : h.group(2);
                continue;
            }
            if (currentHost == null) continue;
            if (HOST_UP.matcher(line).matches()) {
                out.add(Finding.hostUp(currentHost));
                continue;
            }
            Matcher p = PORT_OPEN.matcher(line);
            if (p.matches()) {
                int port = Integer.parseInt(p.group(1));
                String rawService = p.group(3);
                String version = p.group(4) == null ? "" : p.group(4).trim();
                String service = normalizeService(rawService);
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("rawService", rawService);
                attrs.put("proto", p.group(2));
                if (!version.isEmpty()) attrs.put("version", version);
                out.add(new Finding(
                        "PORT_OPEN",
                        currentHost,
                        port,
                        service,
                        attrs,
                        java.time.Instant.now()));
            }
        }
        return out;
    }

    /**
     * Map nmap's raw service strings to a small canonical set used for
     * blackboard queries. Aggressive on purpose: a downstream phase that
     * wants "hosts with web servers" should ask for {@code "http"} OR
     * {@code "https"} and get back exactly those, even if nmap reported
     * {@code ssl/http} or {@code http-proxy}.
     */
    static String normalizeService(String raw) {
        if (raw == null) return "unknown";
        String s = raw.toLowerCase();
        if (s.equals("ssl/http") || s.equals("https")) return "https";
        if (s.equals("http") || s.equals("http-proxy") || s.equals("http-alt")) return "http";
        if (s.equals("microsoft-ds") || s.equals("netbios-ssn") || s.equals("smb")) return "smb";
        if (s.equals("ms-wbt-server") || s.equals("rdp")) return "rdp";
        if (s.equals("ssh")) return "ssh";
        if (s.equals("domain") || s.equals("dns")) return "dns";
        if (s.equals("ftp") || s.equals("ftp-data")) return "ftp";
        if (s.equals("smtp") || s.equals("smtps") || s.equals("submission")) return "smtp";
        return s;
    }
}
