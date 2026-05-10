package ai.intelliswarm.swarmai.skill.runtime;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The relevance filter is what keeps the agent's prompt manageable as
 * the dynamic toolkit grows. These tests verify it ranks intuitively
 * relevant tools higher and degrades sensibly when the task or toolkit
 * is empty.
 */
class ToolRelevanceFilterTest {

    /** Minimal stub BaseTool — only the metadata methods the filter touches. */
    private static BaseTool stub(String name, String description, String triggerWhen,
                                 String category, List<String> tags) {
        return new BaseTool() {
            @Override public String getFunctionName() { return name; }
            @Override public String getDescription() { return description; }
            @Override public String getTriggerWhen() { return triggerWhen; }
            @Override public String getCategory() { return category; }
            @Override public List<String> getTags() { return tags; }
            @Override public Map<String, Object> getParameterSchema() { return Map.of(); }
            @Override public boolean isAsync() { return false; }
            @Override public Object execute(Map<String, Object> p) { return ""; }
        };
    }

    private static final BaseTool NMAP = stub(
            "nmap_recon",
            "Run a port scan and service version detection on a network",
            "First step of a network audit",
            "security/network",
            List.of("network", "port-scan"));
    private static final BaseTool WHATWEB = stub(
            "whatweb",
            "Fingerprint a web server",
            "After nmap finds an HTTP port",
            "security/web",
            List.of("web", "http"));
    private static final BaseTool CALCULATOR = stub(
            "calculator",
            "Evaluate a basic arithmetic expression",
            "When a number is needed and other tools cannot supply it",
            "computation",
            List.of("math", "arithmetic"));
    private static final BaseTool WEATHER = stub(
            "weather",
            "Look up the weather forecast for a city",
            "When the user asks about weather conditions",
            "data-io",
            List.of("weather", "forecast"));

    private static final List<BaseTool> ALL = List.of(NMAP, WHATWEB, CALCULATOR, WEATHER);

    @Test
    @DisplayName("network-related task ranks security tools above unrelated ones")
    void rankNetworkTask() {
        var filter = new ToolRelevanceFilter();
        var ranked = filter.rank("Scan the network 192.168.1.0/24 for open ports", ALL);
        assertEquals("nmap_recon", ranked.get(0).tool().getFunctionName());
        // weather + calculator should be at the bottom (no overlap with network tokens).
        assertTrue(ranked.get(2).score() < ranked.get(0).score());
    }

    @Test
    @DisplayName("web-related task ranks whatweb on top")
    void rankWebTask() {
        var filter = new ToolRelevanceFilter();
        var ranked = filter.rank("Fingerprint the web server stack", ALL);
        assertEquals("whatweb", ranked.get(0).tool().getFunctionName());
    }

    @Test
    @DisplayName("topK drops zero-score tools beyond the first match")
    void topKTruncates() {
        var filter = new ToolRelevanceFilter();
        // Tokens that match nmap_recon ('scan') and nothing else in the corpus.
        // Avoid 'for' / 'web' / 'http' / 'data' which weather/calc/whatweb pick up.
        var top4 = filter.topK("scan subnet hosts discovery", ALL, 4);
        assertEquals(1, top4.size(),
                "zero-score tools should be excluded once we have at least one match");
        assertEquals("nmap_recon", top4.get(0).getFunctionName());
    }

    @Test
    @DisplayName("topK with empty task returns the first k tools as a fallback")
    void topKEmptyTask() {
        var filter = new ToolRelevanceFilter();
        assertEquals(2, filter.topK("", ALL, 2).size());
        assertEquals(2, filter.topK(null, ALL, 2).size());
    }

    @Test
    @DisplayName("topK on empty toolkit returns empty")
    void topKEmptyToolkit() {
        var filter = new ToolRelevanceFilter();
        assertTrue(filter.topK("any task", List.of(), 5).isEmpty());
    }

    @Test
    @DisplayName("topK with k <= 0 returns empty")
    void topKZeroOrNegativeK() {
        var filter = new ToolRelevanceFilter();
        assertTrue(filter.topK("network", ALL, 0).isEmpty());
        assertTrue(filter.topK("network", ALL, -1).isEmpty());
    }

    @Test
    @DisplayName("rare tokens carry more weight than common ones")
    void rareTokensWeighted() {
        // "nmap" appears in NMAP's blurb only, "tool" / "the" appear nowhere
        // → "nmap" should win.
        var filter = new ToolRelevanceFilter();
        var ranked = filter.rank("nmap tool", ALL);
        assertEquals("nmap_recon", ranked.get(0).tool().getFunctionName());
    }

    @Test
    @DisplayName("tokenize lowercases and splits on non-alphanumeric")
    void tokenizeBehaviour() {
        var tokens = ToolRelevanceFilter.tokenize("Scan 192.168.1.0/24 for HTTP/HTTPS!");
        assertTrue(tokens.contains("scan"));
        assertTrue(tokens.contains("http"));
        assertTrue(tokens.contains("https"));
        assertTrue(tokens.contains("192") || tokens.contains("168"),
                "numeric tokens should be retained");
        assertFalse(tokens.contains("h"),  // 1-char tokens dropped
                "single-character tokens should be dropped");
    }

    @Test
    @DisplayName("ranking is stable for equal-scored tools")
    void stableTieBreaking() {
        // Two tools both with zero relevance to "qqq xxx zzz" — original order preserved.
        var filter = new ToolRelevanceFilter();
        var ranked = filter.rank("qqq xxx zzz", List.of(CALCULATOR, WEATHER));
        assertEquals("calculator", ranked.get(0).tool().getFunctionName());
        assertEquals("weather", ranked.get(1).tool().getFunctionName());
    }
}
