package ai.intelliswarm.swarmai.tool.permission;

import ai.intelliswarm.swarmai.api.PublicApi;

import java.util.Objects;

/**
 * Parses the textual rule syntax used in settings:
 *
 * <pre>{@code
 * "Bash"                       → tool=Bash, Specifier.Any
 * "Bash(npm run *)"            → tool=Bash, Specifier.Glob("npm run *")
 * "Read(./.env)"               → tool=Read, Specifier.Glob("./.env")
 * "WebFetch(domain:*.acme.io)" → tool=WebFetch, Specifier.Domain("*.acme.io")
 * "MCP(slack:post-message)"    → tool=MCP, Specifier.McpQualified("slack","post-message")
 * "*"                          → tool=*, Specifier.Any
 * }</pre>
 *
 * <p>Multiple comma-separated globs inside the parens collapse to multiple rules
 * with the same tool — use {@link #parseAll(String)} for that case.
 */
@PublicApi(since = "1.0.24")
public final class RuleParser {

    private RuleParser() {}

    /** Parse a single rule. Multi-glob expressions are not accepted here — use {@link #parseAll(String)}. */
    public static PermissionRule parse(String rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        String trimmed = rule.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("rule must not be blank");
        }

        int openParen = trimmed.indexOf('(');
        if (openParen < 0) {
            return new PermissionRule(trimmed, new PermissionRule.Specifier.Any());
        }

        if (!trimmed.endsWith(")")) {
            throw new IllegalArgumentException("unterminated rule specifier: " + rule);
        }

        String toolName = trimmed.substring(0, openParen).trim();
        String spec = trimmed.substring(openParen + 1, trimmed.length() - 1).trim();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("rule must name a tool: " + rule);
        }
        if (spec.isEmpty()) {
            return new PermissionRule(toolName, new PermissionRule.Specifier.Any());
        }

        return new PermissionRule(toolName, parseSpecifier(toolName, spec));
    }

    /**
     * Parse a rule possibly containing a comma-separated specifier list. Each
     * comma-separated entry produces an independent {@link PermissionRule}
     * targeting the same tool — equivalent to writing each as a separate line.
     */
    public static java.util.List<PermissionRule> parseAll(String rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        String trimmed = rule.trim();
        int openParen = trimmed.indexOf('(');
        if (openParen < 0 || !trimmed.endsWith(")")) {
            return java.util.List.of(parse(trimmed));
        }
        String toolName = trimmed.substring(0, openParen).trim();
        String specList = trimmed.substring(openParen + 1, trimmed.length() - 1).trim();
        if (specList.isEmpty()) {
            return java.util.List.of(new PermissionRule(toolName, new PermissionRule.Specifier.Any()));
        }
        // Split on commas not inside MCP colons. For MVP we split bare commas.
        String[] parts = specList.split("\\s*,\\s*");
        java.util.List<PermissionRule> out = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            out.add(new PermissionRule(toolName, parseSpecifier(toolName, part)));
        }
        return java.util.List.copyOf(out);
    }

    private static PermissionRule.Specifier parseSpecifier(String toolName, String spec) {
        if (spec.startsWith("domain:")) {
            return new PermissionRule.Specifier.Domain(spec.substring("domain:".length()));
        }
        if ("MCP".equalsIgnoreCase(toolName) && spec.contains(":")) {
            int colon = spec.indexOf(':');
            String server = spec.substring(0, colon).trim();
            String tool = spec.substring(colon + 1).trim();
            return new PermissionRule.Specifier.McpQualified(server, tool);
        }
        return new PermissionRule.Specifier.Glob(spec);
    }
}
