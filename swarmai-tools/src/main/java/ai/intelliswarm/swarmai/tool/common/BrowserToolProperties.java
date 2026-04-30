package ai.intelliswarm.swarmai.tool.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for {@link BrowserTool}.
 *
 * Bound from {@code swarmai.tools.browser.*} in application.yml.
 *
 * <p>Off by default — browser automation is a high-blast-radius capability
 * (can navigate to phishing sites, exfiltrate session cookies, automate
 * arbitrary form submissions on behalf of the logged-in user). Enable
 * explicitly with {@code swarmai.tools.browser.enabled=true} so a default
 * Spring Boot app doesn't accidentally ship a remote-controllable browser.
 */
@ConfigurationProperties(prefix = "swarmai.tools.browser")
public class BrowserToolProperties {

    /**
     * Connection mode:
     * <ul>
     *   <li>{@code launch} (default) — start a fresh Chromium instance per process.
     *       Cleanest, no login state inherited.</li>
     *   <li>{@code attach} — connect to an existing Chrome via CDP. Set
     *       {@link #cdpUrl} to {@code http://localhost:9222} after starting Chrome
     *       with {@code chrome --remote-debugging-port=9222}. Inherits ALL cookies
     *       and logins from that Chrome — powerful AND dangerous.</li>
     *   <li>{@code persistent} — Playwright manages a long-lived profile at
     *       {@link #userDataDir}. Logins survive restarts.</li>
     * </ul>
     */
    private String mode = "launch";

    /** Default false — must opt in to enable the BrowserTool bean. */
    private boolean enabled = false;

    /** Run Chromium in headless mode. Default true. Set false to see a real window. */
    private boolean headless = true;

    /** CDP endpoint when {@link #mode}=attach (e.g. {@code http://localhost:9222}). */
    private String cdpUrl;

    /** Profile directory when {@link #mode}=persistent. */
    private String userDataDir;

    /**
     * Hard allow-list of host suffixes the tool may navigate to. Empty list = no restriction
     * (which is convenient for development but unwise in production). A request with a host
     * not matching any suffix is rejected before the page loads.
     *
     * <p>Examples: {@code ["github.com", "wikipedia.org"]} blocks navigation anywhere else.
     */
    private List<String> allowedHosts = List.of();

    /** Default operation timeout in milliseconds. */
    private int timeoutMs = 30_000;

    /** Viewport width. Some sites render very differently below ~1024. */
    private int viewportWidth = 1280;
    private int viewportHeight = 800;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }

    public String getCdpUrl() { return cdpUrl; }
    public void setCdpUrl(String cdpUrl) { this.cdpUrl = cdpUrl; }

    public String getUserDataDir() { return userDataDir; }
    public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }

    public List<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : List.of();
    }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getViewportWidth() { return viewportWidth; }
    public void setViewportWidth(int viewportWidth) { this.viewportWidth = viewportWidth; }

    public int getViewportHeight() { return viewportHeight; }
    public void setViewportHeight(int viewportHeight) { this.viewportHeight = viewportHeight; }
}
