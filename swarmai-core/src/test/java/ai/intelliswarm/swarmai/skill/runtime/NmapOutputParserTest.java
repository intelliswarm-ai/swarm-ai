package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NmapOutputParser}. No Docker required — pure text
 * → {@link Finding} list.
 */
class NmapOutputParserTest {

    @Nested
    @DisplayName("HOST_UP detection")
    class HostUp {

        @Test
        @DisplayName("simple host-only report emits one HOST_UP finding")
        void hostOnlyReport() {
            String out = """
                    Nmap scan report for 192.168.32.7
                    Host is up (0.0011s latency).
                    All 100 scanned ports on 192.168.32.7 are in ignored states.
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            assertEquals(1, findings.size());
            assertEquals("HOST_UP", findings.get(0).type());
            assertEquals("192.168.32.7", findings.get(0).host());
            assertNull(findings.get(0).port());
        }

        @Test
        @DisplayName("hostname + IP form: parses the IP, not the hostname")
        void hostnameWithParentheses() {
            String out = """
                    Nmap scan report for router.local (192.168.1.1)
                    Host is up (0.0021s latency).
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            assertEquals(1, findings.size());
            assertEquals("192.168.1.1", findings.get(0).host(),
                    "parenthesised IP wins over the hostname");
        }
    }

    @Nested
    @DisplayName("PORT_OPEN extraction")
    class PortOpen {

        @Test
        @DisplayName("single port row produces a PORT_OPEN with service + raw banner")
        void singlePort() {
            String out = """
                    Nmap scan report for 192.168.32.1
                    Host is up (0.0021s latency).
                    PORT    STATE SERVICE
                    445/tcp open  microsoft-ds
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            // 1 HOST_UP + 1 PORT_OPEN
            assertEquals(2, findings.size());

            Finding port = findings.stream()
                    .filter(f -> "PORT_OPEN".equals(f.type()))
                    .findFirst().orElseThrow();
            assertEquals("192.168.32.1", port.host());
            assertEquals(445, port.port());
            assertEquals("smb", port.service(), "microsoft-ds normalises to smb");
            assertEquals("microsoft-ds", port.attributes().get("rawService"),
                    "raw banner preserved for traceability");
            assertEquals("tcp", port.attributes().get("proto"));
        }

        @Test
        @DisplayName("multi-host report attributes ports to the right host")
        void multiHostAttribution() {
            String out = """
                    Nmap scan report for 10.0.0.1
                    Host is up.
                    PORT    STATE SERVICE
                    22/tcp  open  ssh

                    Nmap scan report for 10.0.0.2
                    Host is up.
                    PORT    STATE SERVICE
                    80/tcp  open  http
                    443/tcp open  ssl/http
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            List<Finding> ports = findings.stream()
                    .filter(f -> "PORT_OPEN".equals(f.type()))
                    .toList();
            assertEquals(3, ports.size());

            // Per-host attribution check
            Finding ssh = ports.stream().filter(f -> f.port() == 22).findFirst().orElseThrow();
            assertEquals("10.0.0.1", ssh.host());
            assertEquals("ssh", ssh.service());

            Finding http = ports.stream().filter(f -> f.port() == 80).findFirst().orElseThrow();
            assertEquals("10.0.0.2", http.host());
            assertEquals("http", http.service());

            Finding https = ports.stream().filter(f -> f.port() == 443).findFirst().orElseThrow();
            assertEquals("10.0.0.2", https.host());
            assertEquals("https", https.service(), "ssl/http normalises to https");
        }

        @Test
        @DisplayName("version column captured into attributes")
        void serviceVersion() {
            String out = """
                    Nmap scan report for 192.168.1.1
                    Host is up.
                    PORT    STATE SERVICE  VERSION
                    22/tcp  open  ssh      OpenSSH 9.6p1 Ubuntu 3ubuntu13.5
                    80/tcp  open  http     lighttpd 1.4.71
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            Finding ssh = findings.stream()
                    .filter(f -> "PORT_OPEN".equals(f.type()) && f.port() == 22)
                    .findFirst().orElseThrow();
            assertTrue(ssh.attributes().get("version").startsWith("OpenSSH 9.6p1"));

            Finding http = findings.stream()
                    .filter(f -> "PORT_OPEN".equals(f.type()) && f.port() == 80)
                    .findFirst().orElseThrow();
            assertEquals("lighttpd 1.4.71", http.attributes().get("version"));
        }

        @Test
        @DisplayName("filtered/closed ports are not emitted as PORT_OPEN")
        void closedPortsIgnored() {
            String out = """
                    Nmap scan report for 192.168.32.0
                    Host is up.
                    All 100 scanned ports on 192.168.32.0 are in ignored states.
                    Not shown: 100 filtered tcp ports (no-response)
                    """;
            List<Finding> findings = NmapOutputParser.parse(out);
            assertTrue(findings.stream().noneMatch(f -> "PORT_OPEN".equals(f.type())),
                    "no open-port lines should produce no PORT_OPEN findings");
        }
    }

    @Nested
    @DisplayName("service normalisation")
    class Normalisation {

        @Test
        @DisplayName("common variants map to canonical names")
        void canonicalForms() {
            assertEquals("https", NmapOutputParser.normalizeService("ssl/http"));
            assertEquals("https", NmapOutputParser.normalizeService("https"));
            assertEquals("http", NmapOutputParser.normalizeService("http"));
            assertEquals("http", NmapOutputParser.normalizeService("http-proxy"));
            assertEquals("smb", NmapOutputParser.normalizeService("microsoft-ds"));
            assertEquals("smb", NmapOutputParser.normalizeService("netbios-ssn"));
            assertEquals("ssh", NmapOutputParser.normalizeService("ssh"));
            assertEquals("dns", NmapOutputParser.normalizeService("domain"));
            assertEquals("rdp", NmapOutputParser.normalizeService("ms-wbt-server"));
        }

        @Test
        @DisplayName("unknown services pass through lowercased")
        void unknownServicePassthrough() {
            assertEquals("postgresql", NmapOutputParser.normalizeService("PostgreSQL"));
            assertEquals("custom-service", NmapOutputParser.normalizeService("custom-service"));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class Edge {

        @Test
        @DisplayName("empty input → empty findings, no NPE")
        void emptyInput() {
            assertTrue(NmapOutputParser.parse(null).isEmpty());
            assertTrue(NmapOutputParser.parse("").isEmpty());
            assertTrue(NmapOutputParser.parse("   \n  \n").isEmpty());
        }

        @Test
        @DisplayName("port row before any host header is dropped (no host context)")
        void portWithoutHostContext() {
            String out = "22/tcp  open  ssh\n"; // no preceding "Nmap scan report for ..."
            assertTrue(NmapOutputParser.parse(out).isEmpty(),
                    "we don't guess hosts; orphan port lines are skipped");
        }
    }
}
