package ai.intelliswarm.swarmai.tool.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One permission rule. Two parts:
 * <ul>
 *   <li>{@link #toolName()} — the tool this rule applies to, or {@code "*"} for any</li>
 *   <li>{@link #specifier()} — narrows the match to a subset of invocations of that tool</li>
 * </ul>
 *
 * <p>The {@link Specifier} variants capture the patterns Claude Code's permission
 * DSL surfaces today:
 *
 * <pre>{@code
 * Bash                       → toolName=Bash, specifier=Any
 * Bash(npm run *)            → toolName=Bash, specifier=Glob("npm run *") on "command"
 * Read(./.env)               → toolName=Read, specifier=Glob("./.env") on "path"
 * WebFetch(domain:*.acme.io) → toolName=WebFetch, specifier=Domain("*.acme.io")
 * MCP(slack:post-message)    → toolName=MCP, specifier=McpQualified("slack","post-message")
 * }</pre>
 *
 * <p>Action (allow / ask / deny) is not part of the rule — it's determined by
 * which list of {@link PermissionRules} the rule lives in. Deny-first evaluation.
 */
@PublicApi(since = "1.0.24")
public record PermissionRule(String toolName, Specifier specifier) {

    public PermissionRule {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(specifier, "specifier must not be null");
    }

    public boolean matches(PermissionRequest req) {
        if (!"*".equals(toolName) && !toolName.equalsIgnoreCase(req.toolName())) {
            return false;
        }
        return specifier.matches(req);
    }

    @PublicApi(since = "1.0.24")
    public sealed interface Specifier permits Specifier.Any, Specifier.Glob, Specifier.Domain, Specifier.McpQualified {
        boolean matches(PermissionRequest req);

        /** Match every invocation of the named tool. */
        @PublicApi(since = "1.0.24")
        record Any() implements Specifier {
            public boolean matches(PermissionRequest req) {
                return true;
            }
        }

        /**
         * Glob match against a string-typed argument. Lookup keys are tool-specific
         * conventions: {@code command} for Bash, {@code path} for Read/Write/Edit,
         * {@code url} for HttpRequest, falling back to the first string argument.
         */
        @PublicApi(since = "1.0.24")
        record Glob(String pattern) implements Specifier {
            public Glob {
                Objects.requireNonNull(pattern, "pattern must not be null");
            }

            public boolean matches(PermissionRequest req) {
                String value = req.firstStringArg(
                        "command", "path", "filePath", "file_path",
                        "url", "expression", "query");
                if (value == null) {
                    // No string arg to match against — only Any-spec wildcards succeed.
                    return false;
                }
                return globMatch(pattern, value);
            }
        }

        /**
         * Domain match for tools whose argument is a URL. Patterns may include
         * a leading {@code *.} wildcard ({@code *.acme.io} matches
         * {@code api.acme.io} and {@code deep.api.acme.io} but not {@code acme.io}).
         */
        @PublicApi(since = "1.0.24")
        record Domain(String pattern) implements Specifier {
            public Domain {
                Objects.requireNonNull(pattern, "pattern must not be null");
            }

            public boolean matches(PermissionRequest req) {
                String url = req.firstStringArg("url", "domain", "host");
                if (url == null) {
                    return false;
                }
                String host = extractHost(url);
                if (host == null) {
                    return false;
                }
                return domainMatch(pattern, host);
            }
        }

        /**
         * Qualified MCP tool match. {@code server} and {@code tool} both accept
         * {@code "*"} as a wildcard for that segment.
         */
        @PublicApi(since = "1.0.24")
        record McpQualified(String server, String tool) implements Specifier {
            public McpQualified {
                Objects.requireNonNull(server, "server must not be null");
                Objects.requireNonNull(tool, "tool must not be null");
            }

            public boolean matches(PermissionRequest req) {
                if (!"MCP".equalsIgnoreCase(req.toolName())) {
                    return false;
                }
                boolean serverOk = "*".equals(server) || server.equalsIgnoreCase(req.mcpServer());
                boolean toolOk = "*".equals(tool) || tool.equalsIgnoreCase(req.mcpTool());
                return serverOk && toolOk;
            }
        }
    }

    // ---------- Matching utilities ----------

    static boolean globMatch(String pattern, String value) {
        // Translate a shell-style glob (* and ?) into a regex. Backslash-escape every
        // regex metacharacter except the two glob wildcards so dots and parens stay literal.
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' ->
                        regex.append('\\').append(c);
                default -> regex.append(c);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString()).matcher(value).matches();
    }

    static boolean domainMatch(String pattern, String host) {
        host = host.toLowerCase();
        String p = pattern.toLowerCase();
        if (p.startsWith("*.")) {
            String suffix = p.substring(1); // ".acme.io"
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(p);
    }

    static String extractHost(String url) {
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) {
                return null;
            }
            int authStart = schemeEnd + 3;
            // Authority ends at the first '/', '?', or '#' — without this,
            // path-less URLs like "https://api.acme.io?x=1" parse their query
            // string as part of the host, so domain rules false-negative.
            int authEnd = url.length();
            for (int i = authStart; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    authEnd = i;
                    break;
                }
            }
            String authority = url.substring(authStart, authEnd);
            int at = authority.indexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
            }
            int colon = authority.indexOf(':');
            return (colon < 0) ? authority : authority.substring(0, colon);
        } catch (Exception e) {
            return null;
        }
    }
}
