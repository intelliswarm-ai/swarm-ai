package ai.intelliswarm.swarmai.tool.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that drives a REAL Chromium against Gmail to prove the BrowserTool
 * works on a heavy modern SPA with login state.
 *
 * <h3>How to run</h3>
 * <pre>
 *   1. Start your everyday Chrome with the debugging port open:
 *        macOS:   /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
 *                   --remote-debugging-port=9222 --user-data-dir="$HOME/cdp-chrome"
 *        Linux:   google-chrome --remote-debugging-port=9222 --user-data-dir=$HOME/cdp-chrome
 *        Windows: chrome.exe --remote-debugging-port=9222 --user-data-dir=%USERPROFILE%\cdp-chrome
 *      Sign in to Gmail in that Chrome instance ONCE.
 *
 *   2. Run:
 *        mvn -pl swarmai-tools test -Dtest=BrowserToolGmailIT -DenableBrowserTests=true
 *
 *      With openai-mini if you also want to drive an Agent:
 *        OPENAI_API_KEY=sk-… SPRING_PROFILES_ACTIVE=openai-mini \
 *        mvn -pl swarmai-tools test -Dtest=BrowserToolGmailIT -DenableBrowserTests=true
 * </pre>
 *
 * <p>Why attach mode and not script-the-login: Google's auth flow has bot detection
 * that flags Playwright headless instances, plus 2FA which can't be automated. Attaching
 * to your real, already-signed-in Chrome session sidesteps both — the BrowserTool sees
 * your Gmail exactly as you do.
 *
 * <p>This test is excluded from regular CI runs by the {@link EnabledIfSystemProperty}
 * gate. Without {@code -DenableBrowserTests=true} JUnit silently skips it.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "enableBrowserTests", matches = "true")
class BrowserToolGmailIT {

    private BrowserTool tool;

    @AfterEach
    void tearDown() {
        if (tool != null) tool.shutdown();
    }

    private BrowserTool attached() {
        BrowserToolProperties p = new BrowserToolProperties();
        p.setEnabled(true);
        p.setMode("attach");
        p.setCdpUrl(System.getProperty("cdp.url", "http://localhost:9222"));
        p.setHeadless(false); // attached Chromes are typically not headless
        // Hard allow-list — refuse navigation anywhere except Google.
        p.setAllowedHosts(java.util.List.of("google.com", "gmail.com"));
        return new BrowserTool(p);
    }

    // ------------------------------------------------------------------ probes

    @Test
    void gmail_inbox_loadsAndShowsMessages() {
        tool = attached();

        // 1. Navigate to Gmail.
        Object navResult = tool.execute(Map.of(
                "operation", "navigate",
                "url", "https://mail.google.com/mail/u/0/#inbox"));
        assertThat(String.valueOf(navResult)).startsWith("Navigated to");

        // 2. Wait for the inbox table to render (Gmail's main mail list lives in [role="main"]).
        Object waitResult = tool.execute(Map.of(
                "operation", "wait_for",
                "selector", "[role=\"main\"]",
                "timeout_ms", 20_000));
        assertThat(String.valueOf(waitResult)).startsWith("Element appeared");

        // 3. Scrape a small slice of the inbox so we get visible message subjects.
        //    Don't assert on specific subjects — too brittle. Just assert there's text and
        //    enough of it that we know we're past the loading skeleton.
        Object scraped = tool.execute(Map.of(
                "operation", "scrape",
                "selector", "[role=\"main\"]"));
        String body = String.valueOf(scraped);
        assertThat(body).isNotBlank();
        assertThat(body.length()).isGreaterThan(200);

        // 4. Use evaluate_js to count unread rows ("zE" is Gmail's unread CSS class today;
        //    if Google rotates it the assertion just counts ZERO — we treat 0 as a soft pass
        //    since this is observability, not correctness).
        Object unreadCount = tool.execute(Map.of(
                "operation", "evaluate_js",
                "code", "document.querySelectorAll('tr.zE').length"));
        // Print so the human running the test sees the number; assert it's a non-negative int.
        System.out.println("BrowserToolGmailIT: unread rows visible = " + unreadCount);
        assertThat(unreadCount.toString()).matches("\\d+");
    }

    @Test
    void allowedHosts_blocksNavigationOffPolicy() {
        // Sanity check on the security guard — even an attached Chrome cannot follow links
        // outside swarmai.tools.browser.allowed-hosts.
        tool = attached();
        Object result = tool.execute(Map.of(
                "operation", "navigate",
                "url", "https://example.com/"));
        // The tool returns the error string rather than throwing — easier for agents to handle.
        assertThat(String.valueOf(result))
                .startsWith("ERROR:")
                .contains("not in swarmai.tools.browser.allowed-hosts");
    }
}
