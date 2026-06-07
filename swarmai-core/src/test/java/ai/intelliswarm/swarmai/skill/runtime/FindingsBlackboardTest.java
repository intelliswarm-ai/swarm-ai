package ai.intelliswarm.swarmai.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FindingsBlackboardTest {

    @Test
    @DisplayName("record + all() round-trips findings in order")
    void recordAndAll() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.portOpen("192.168.1.1", 80, "http"));
        bb.record(Finding.portOpen("192.168.1.1", 443, "https"));
        bb.record(Finding.portOpen("192.168.1.5", 22, "ssh"));
        assertEquals(3, bb.size());
        assertFalse(bb.isEmpty());

        List<Finding> all = bb.all();
        assertEquals(3, all.size());
        assertEquals(80, all.get(0).port());
        assertEquals(443, all.get(1).port());
        assertEquals(22, all.get(2).port());
    }

    @Test
    @DisplayName("byType filters correctly")
    void byType() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.portOpen("a", 80, "http"));
        bb.record(Finding.hostUp("b"));
        bb.record(Finding.portOpen("c", 443, "https"));
        assertEquals(2, bb.byType("PORT_OPEN").size());
        assertEquals(1, bb.byType("HOST_UP").size());
        assertEquals(0, bb.byType("BOGUS").size());
    }

    @Test
    @DisplayName("hostsWithService returns the expected hosts only")
    void hostsWithService() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.portOpen("192.168.1.1", 80, "http"));
        bb.record(Finding.portOpen("192.168.1.1", 443, "https"));
        bb.record(Finding.portOpen("192.168.1.2", 80, "http"));
        bb.record(Finding.portOpen("192.168.1.5", 22, "ssh"));

        Set<String> httpHosts = bb.hostsWithService("http");
        assertEquals(Set.of("192.168.1.1", "192.168.1.2"), httpHosts);

        Set<String> sshHosts = bb.hostsWithService("ssh");
        assertEquals(Set.of("192.168.1.5"), sshHosts);

        Set<String> noneHosts = bb.hostsWithService("ftp");
        assertTrue(noneHosts.isEmpty());
    }

    @Test
    @DisplayName("byHost returns all findings for a given host")
    void byHost() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.hostUp("192.168.1.1"));
        bb.record(Finding.portOpen("192.168.1.1", 80, "http"));
        bb.record(Finding.portOpen("192.168.1.1", 443, "https"));
        bb.record(Finding.portOpen("192.168.1.2", 22, "ssh"));

        List<Finding> host1 = bb.byHost("192.168.1.1");
        assertEquals(3, host1.size());

        List<Finding> host2 = bb.byHost("192.168.1.2");
        assertEquals(1, host2.size());
    }

    @Test
    @DisplayName("groupedByType preserves insertion order")
    void groupedByType() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.portOpen("a", 80, "http"));
        bb.record(Finding.hostUp("b"));
        bb.record(Finding.portOpen("c", 443, "https"));

        Map<String, List<Finding>> grouped = bb.groupedByType();
        // insertion order: PORT_OPEN first, then HOST_UP
        assertEquals(List.of("PORT_OPEN", "HOST_UP"), List.copyOf(grouped.keySet()));
        assertEquals(2, grouped.get("PORT_OPEN").size());
    }

    @Test
    @DisplayName("recordAll batches multiple findings atomically")
    void recordAll() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.recordAll(List.of(
                Finding.portOpen("a", 80, "http"),
                Finding.portOpen("b", 443, "https"),
                Finding.hostUp("c")));
        assertEquals(3, bb.size());
    }

    @Test
    @DisplayName("HTTP_FINGERPRINT helper carries attributes")
    void httpFingerprint() {
        FindingsBlackboard bb = new FindingsBlackboard();
        bb.record(Finding.httpFingerprint("192.168.1.1", 80, Map.of(
                "server", "lighttpd/1.4.71",
                "title", "Router admin")));
        assertEquals(1, bb.size());
        Finding f = bb.all().get(0);
        assertEquals("HTTP_FINGERPRINT", f.type());
        assertEquals("lighttpd/1.4.71", f.attributes().get("server"));
    }
}
