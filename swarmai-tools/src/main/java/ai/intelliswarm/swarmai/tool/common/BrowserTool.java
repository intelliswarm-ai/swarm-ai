package ai.intelliswarm.swarmai.tool.common;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-Chromium browser automation via Chrome DevTools Protocol (CDP).
 *
 * <p>This is the heavy-duty companion to {@link HeadlessBrowserTool} (HtmlUnit-based
 * pure-Java fetch). Use {@code BrowserTool} when:
 * <ul>
 *   <li>The page is a modern JS-heavy SPA that HtmlUnit can't render correctly</li>
 *   <li>You need to interact with the page (click, type, fill forms, wait for elements)</li>
 *   <li>You need to inherit an existing Chrome's login state (CDP attach mode)</li>
 *   <li>You need screenshots or to evaluate arbitrary JavaScript</li>
 * </ul>
 *
 * <h3>Operations</h3>
 * <pre>
 *   navigate    {url}                            → loads a URL, waits for network idle
 *   click       {selector}                       → clicks the first matching element
 *   type        {selector, text}                 → focuses and types into an input
 *   fill        {selector, text}                 → like type but clears first
 *   fill_form   {fields: {selector: value, ...}} → bulk fill multiple inputs
 *   screenshot  {path?, fullPage?}               → PNG saved to path; returns base64 if no path
 *   scrape      {selector?}                      → text content of selector (or whole body)
 *   evaluate_js {code}                           → run JS in the page; returns the eval result
 *   wait_for    {selector?, timeout_ms?}         → wait for element OR sleep
 *   close       {}                               → close the current page
 * </pre>
 *
 * <h3>Connection modes (via {@link BrowserToolProperties})</h3>
 * <ul>
 *   <li>{@code launch} — fresh Chromium per process. Default. No login state.</li>
 *   <li>{@code attach} — connect to an existing Chrome via CDP. Inherits cookies + logins.</li>
 *   <li>{@code persistent} — Playwright-managed profile at {@code userDataDir}.</li>
 * </ul>
 *
 * <h3>Security</h3>
 * <p>Permission level is {@link PermissionLevel#DANGEROUS} — agents need explicit
 * permission to use it. {@link BrowserToolProperties#getAllowedHosts()} adds a hard
 * allow-list at the framework layer.
 *
 * <p>Gated by {@code swarmai.tools.browser.enabled=true}. Off by default.
 */
@Component
@ConditionalOnClass(name = "com.microsoft.playwright.Playwright")
@ConditionalOnProperty(prefix = "swarmai.tools.browser", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BrowserToolProperties.class)
public class BrowserTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(BrowserTool.class);

    private final BrowserToolProperties props;

    /** Lazily initialised — first execute() call boots Playwright + a Browser + a Page. */
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    public BrowserTool(BrowserToolProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ BaseTool

    @Override
    public String getFunctionName() { return "browser"; }

    @Override
    public String getDescription() {
        return "Real-Chromium browser automation via Chrome DevTools Protocol. "
                + "Operations: navigate, click, type, fill, fill_form, screenshot, scrape, "
                + "evaluate_js, wait_for, close. Use this when HtmlUnit (browse) can't render "
                + "a modern JS site or when you need to interact with elements (click, type, "
                + "fill forms). Page state PERSISTS across calls so multi-step flows work. "
                + "DANGEROUS — can navigate anywhere and access any login state of the running "
                + "Chrome session.";
    }

    @Override
    public boolean isAsync() { return false; }

    @Override
    public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }

    @Override
    public String getCategory() { return "web"; }

    @Override
    public List<String> getTags() { return List.of("browser", "automation", "cdp", "chromium"); }

    @Override
    public String getTriggerWhen() {
        return "User needs to interact with a website (click, type, fill a form, log in), "
                + "scrape a JS-rendered page, or take a screenshot.";
    }

    @Override
    public String getAvoidWhen() {
        return "User just needs to fetch the visible text of a static page (use 'browse' instead — "
                + "much lighter); or the question is about static content the LLM already knows.";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "operation", Map.of(
                                "type", "string",
                                "enum", List.of("navigate", "click", "type", "fill", "fill_form",
                                        "screenshot", "scrape", "evaluate_js", "wait_for", "close"),
                                "description", "What to do."),
                        "url", Map.of("type", "string"),
                        "selector", Map.of("type", "string"),
                        "text", Map.of("type", "string"),
                        "fields", Map.of("type", "object"),
                        "code", Map.of("type", "string"),
                        "path", Map.of("type", "string"),
                        "fullPage", Map.of("type", "boolean"),
                        "timeout_ms", Map.of("type", "integer")),
                "required", List.of("operation"));
    }

    // ------------------------------------------------------------------ execute

    @Override
    public Object execute(Map<String, Object> parameters) {
        Map<String, Object> p = parameters != null ? parameters : Map.of();
        String op = String.valueOf(p.getOrDefault("operation", "")).toLowerCase();
        if (op.isBlank()) return "ERROR: missing 'operation'";

        try {
            return switch (op) {
                case "navigate"    -> doNavigate(strParam(p, "url"));
                case "click"       -> doClick(strParam(p, "selector"));
                case "type"        -> doType(strParam(p, "selector"), strParam(p, "text"));
                case "fill"        -> doFill(strParam(p, "selector"), strParam(p, "text"));
                case "fill_form"   -> doFillForm(p.get("fields"));
                case "screenshot"  -> doScreenshot(strParam(p, "path"),
                                                   Boolean.TRUE.equals(p.get("fullPage")));
                case "scrape"      -> doScrape(p.get("selector") == null ? null : strParam(p, "selector"));
                case "evaluate_js" -> doEvaluateJs(strParam(p, "code"));
                case "wait_for"    -> doWaitFor(p.get("selector") == null ? null : strParam(p, "selector"),
                                                 intParam(p, "timeout_ms", props.getTimeoutMs()));
                case "close"       -> doClose();
                default            -> "ERROR: unknown operation '" + op + "'. "
                                       + "Try: navigate, click, type, fill, fill_form, "
                                       + "screenshot, scrape, evaluate_js, wait_for, close.";
            };
        } catch (RuntimeException e) {
            logger.warn("BrowserTool {} failed: {}", op, e.getMessage());
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ operations

    private synchronized String doNavigate(String url) {
        guardHost(url);
        Page p = ensurePage();
        p.navigate(url);
        p.waitForLoadState(LoadState.NETWORKIDLE);
        return "Navigated to " + url + "  title=\"" + p.title() + "\"  status=ok";
    }

    private synchronized String doClick(String selector) {
        require(selector, "selector");
        ensurePage().click(selector);
        return "Clicked " + selector;
    }

    private synchronized String doType(String selector, String text) {
        require(selector, "selector"); require(text, "text");
        ensurePage().type(selector, text);
        return "Typed " + text.length() + " chars into " + selector;
    }

    private synchronized String doFill(String selector, String text) {
        require(selector, "selector"); require(text, "text");
        ensurePage().fill(selector, text);
        return "Filled " + selector + " with " + text.length() + " chars";
    }

    @SuppressWarnings("unchecked")
    private synchronized String doFillForm(Object fieldsObj) {
        if (!(fieldsObj instanceof Map)) {
            return "ERROR: 'fields' must be an object {selector: value, …}";
        }
        Map<String, Object> fields = (Map<String, Object>) fieldsObj;
        Page p = ensurePage();
        StringBuilder sb = new StringBuilder("Filled " + fields.size() + " field(s):");
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            p.fill(e.getKey(), String.valueOf(e.getValue()));
            sb.append("\n  - ").append(e.getKey());
        }
        return sb.toString();
    }

    private synchronized String doScreenshot(String pathStr, boolean fullPage) {
        Page p = ensurePage();
        Page.ScreenshotOptions opts = new Page.ScreenshotOptions().setFullPage(fullPage);
        if (pathStr != null && !pathStr.isBlank()) {
            Path out = Paths.get(pathStr);
            opts.setPath(out);
            p.screenshot(opts);
            return "Saved screenshot to " + out.toAbsolutePath();
        }
        // No path → return base64 inline so the agent can attach it to its output.
        byte[] png = p.screenshot(opts);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private synchronized String doScrape(String selector) {
        Page p = ensurePage();
        String text = (selector == null || selector.isBlank())
                ? p.innerText("body")
                : p.innerText(selector);
        // Clamp so an enormous page doesn't blow up the agent's context.
        int max = getMaxResponseLength();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n... [truncated " + (text.length() - max) + " chars]";
        }
        return text;
    }

    private synchronized Object doEvaluateJs(String code) {
        require(code, "code");
        Object result = ensurePage().evaluate(code);
        return result == null ? "null" : result.toString();
    }

    private synchronized String doWaitFor(String selector, int timeoutMs) {
        Page p = ensurePage();
        if (selector == null || selector.isBlank()) {
            // No selector → just sleep. Bounded by the prop default to avoid runaway.
            try { Thread.sleep(Math.min(timeoutMs, 60_000)); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return "Slept " + timeoutMs + "ms";
        }
        p.waitForSelector(selector, new Page.WaitForSelectorOptions().setTimeout(timeoutMs));
        return "Element appeared: " + selector;
    }

    private synchronized String doClose() {
        if (page != null) {
            try { page.close(); } catch (Exception ignored) {}
            page = null;
        }
        return "Page closed";
    }

    // ------------------------------------------------------------------ lifecycle

    private synchronized Page ensurePage() {
        if (page != null && !page.isClosed()) return page;

        if (playwright == null) {
            playwright = Playwright.create();
        }

        BrowserType chromium = playwright.chromium();
        switch (props.getMode().toLowerCase()) {
            case "attach" -> {
                if (props.getCdpUrl() == null || props.getCdpUrl().isBlank()) {
                    throw new IllegalStateException(
                            "swarmai.tools.browser.mode=attach requires swarmai.tools.browser.cdp-url");
                }
                if (browser == null) browser = chromium.connectOverCDP(props.getCdpUrl());
                if (context == null) {
                    context = browser.contexts().isEmpty()
                            ? browser.newContext()
                            : browser.contexts().get(0);
                }
                page = context.newPage();
            }
            case "persistent" -> {
                if (props.getUserDataDir() == null || props.getUserDataDir().isBlank()) {
                    throw new IllegalStateException(
                            "swarmai.tools.browser.mode=persistent requires swarmai.tools.browser.user-data-dir");
                }
                ensureDir(Paths.get(props.getUserDataDir()));
                if (context == null) {
                    context = chromium.launchPersistentContext(
                            Paths.get(props.getUserDataDir()),
                            new BrowserType.LaunchPersistentContextOptions()
                                    .setHeadless(props.isHeadless())
                                    .setViewportSize(props.getViewportWidth(), props.getViewportHeight()));
                }
                page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            }
            default -> { // "launch"
                if (browser == null) {
                    browser = chromium.launch(new BrowserType.LaunchOptions()
                            .setHeadless(props.isHeadless()));
                }
                if (context == null) {
                    context = browser.newContext(new Browser.NewContextOptions()
                            .setViewportSize(props.getViewportWidth(), props.getViewportHeight()));
                }
                page = context.newPage();
            }
        }
        page.setDefaultTimeout(props.getTimeoutMs());
        logger.info("BrowserTool: ready ({}, headless={})", props.getMode(), props.isHeadless());
        return page;
    }

    @PreDestroy
    public synchronized void shutdown() {
        try { if (page != null) page.close(); } catch (Exception ignored) {}
        try { if (context != null) context.close(); } catch (Exception ignored) {}
        try { if (browser != null) browser.close(); } catch (Exception ignored) {}
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        page = null; context = null; browser = null; playwright = null;
        logger.info("BrowserTool: shut down");
    }

    // ------------------------------------------------------------------ helpers

    private static void ensureDir(Path p) {
        try { Files.createDirectories(p); } catch (Exception e) {
            throw new RuntimeException("Cannot create user-data-dir " + p + ": " + e.getMessage(), e);
        }
    }

    private void guardHost(String url) {
        if (props.getAllowedHosts().isEmpty()) return;
        try {
            String host = URI.create(url).getHost();
            if (host == null) throw new IllegalArgumentException("Invalid URL: " + url);
            String h = host.toLowerCase();
            for (String allowed : props.getAllowedHosts()) {
                if (h.equals(allowed) || h.endsWith("." + allowed)) return;
            }
            throw new SecurityException(
                    "Host '" + host + "' is not in swarmai.tools.browser.allowed-hosts");
        } catch (IllegalArgumentException ex) {
            throw new SecurityException("URL parse failed: " + ex.getMessage(), ex);
        }
    }

    private static void require(Object v, String name) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException("Missing required parameter '" + name + "'");
        }
    }

    private static String strParam(Map<String, Object> p, String name) {
        Object v = p.get(name);
        return v == null ? null : String.valueOf(v);
    }

    private static int intParam(Map<String, Object> p, String name, int def) {
        Object v = p.get(name);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return def; }
        }
        return def;
    }

    /**
     * Schema for Spring AI's tool-calling layer. Mirrors the JSON schema returned by
     * {@link #getParameterSchema()} but as a Java record so the OpenAI / Anthropic tool
     * descriptor can be auto-derived.
     */
    public record Request(
            String operation,
            String url,
            String selector,
            String text,
            @com.fasterxml.jackson.annotation.JsonProperty(required = false)
            Map<String, Object> fields,
            String code,
            String path,
            Boolean fullPage,
            Integer timeout_ms) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (operation  != null) m.put("operation",  operation);
            if (url        != null) m.put("url",        url);
            if (selector   != null) m.put("selector",   selector);
            if (text       != null) m.put("text",       text);
            if (fields     != null) m.put("fields",     fields);
            if (code       != null) m.put("code",       code);
            if (path       != null) m.put("path",       path);
            if (fullPage   != null) m.put("fullPage",   fullPage);
            if (timeout_ms != null) m.put("timeout_ms", timeout_ms);
            return m;
        }
    }
}
