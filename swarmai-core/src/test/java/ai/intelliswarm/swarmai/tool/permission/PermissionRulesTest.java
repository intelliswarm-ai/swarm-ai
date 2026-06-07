package ai.intelliswarm.swarmai.tool.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionRulesTest {

    // ---------- RuleParser ----------

    @Test
    void parseBareToolNameProducesAnySpecifier() {
        PermissionRule rule = RuleParser.parse("Bash");
        assertThat(rule.toolName()).isEqualTo("Bash");
        assertThat(rule.specifier()).isInstanceOf(PermissionRule.Specifier.Any.class);
    }

    @Test
    void parseGlobSpecifier() {
        PermissionRule rule = RuleParser.parse("Bash(npm run *)");
        assertThat(rule.toolName()).isEqualTo("Bash");
        var glob = (PermissionRule.Specifier.Glob) rule.specifier();
        assertThat(glob.pattern()).isEqualTo("npm run *");
    }

    @Test
    void parseDomainSpecifier() {
        PermissionRule rule = RuleParser.parse("WebFetch(domain:*.acme.io)");
        var dom = (PermissionRule.Specifier.Domain) rule.specifier();
        assertThat(dom.pattern()).isEqualTo("*.acme.io");
    }

    @Test
    void parseMcpQualifiedSpecifier() {
        PermissionRule rule = RuleParser.parse("MCP(slack:post-message)");
        var mcp = (PermissionRule.Specifier.McpQualified) rule.specifier();
        assertThat(mcp.server()).isEqualTo("slack");
        assertThat(mcp.tool()).isEqualTo("post-message");
    }

    @Test
    void parseAllSplitsCommaSeparatedSpecifiers() {
        var rules = RuleParser.parseAll("Bash(npm run *, git *)");
        assertThat(rules).hasSize(2);
        assertThat(((PermissionRule.Specifier.Glob) rules.get(0).specifier()).pattern())
                .isEqualTo("npm run *");
        assertThat(((PermissionRule.Specifier.Glob) rules.get(1).specifier()).pattern())
                .isEqualTo("git *");
    }

    @Test
    void parseUnterminatedRuleRejected() {
        assertThatThrownBy(() -> RuleParser.parse("Bash(npm run"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Glob matching ----------

    @Test
    void bashGlobMatchesCommand() {
        PermissionRule rule = RuleParser.parse("Bash(npm run *)");
        assertThat(rule.matches(
                PermissionRequest.of("Bash", Map.of("command", "npm run build"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("Bash", Map.of("command", "rm -rf /"))))
                .isFalse();
    }

    @Test
    void readGlobMatchesExactPath() {
        PermissionRule rule = RuleParser.parse("Read(./.env)");
        assertThat(rule.matches(
                PermissionRequest.of("Read", Map.of("path", "./.env"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("Read", Map.of("path", "./.env.local"))))
                .isFalse();
    }

    @Test
    void globRespectsLiteralDots() {
        // The dot in .env must match literally — a bug would make it match any single char
        PermissionRule rule = RuleParser.parse("Read(./.env)");
        assertThat(rule.matches(
                PermissionRequest.of("Read", Map.of("path", "./Aenv"))))
                .isFalse();
    }

    // ---------- Domain matching ----------

    @Test
    void domainWildcardMatchesSubdomains() {
        PermissionRule rule = RuleParser.parse("WebFetch(domain:*.acme.io)");
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://api.acme.io/v1/x"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://deep.api.acme.io/"))))
                .isTrue();
        // bare domain doesn't match a *.foo pattern (must have at least one subdomain)
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://acme.io/"))))
                .isFalse();
        // different domain entirely
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://evil.com/"))))
                .isFalse();
    }

    @Test
    void exactDomainMatch() {
        PermissionRule rule = RuleParser.parse("WebFetch(domain:acme.io)");
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://acme.io/"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://api.acme.io/"))))
                .isFalse();
    }

    @Test
    void portInUrlIsStripped() {
        PermissionRule rule = RuleParser.parse("WebFetch(domain:internal.corp)");
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "http://internal.corp:8080/"))))
                .isTrue();
    }

    @Test
    void domainMatchesPathlessUrlsWithQueryOrFragment() {
        // Regression: extractHost previously stopped only at '/', so a URL like
        // "https://api.acme.io?x=1" parsed its host as "api.acme.io?x=1" and
        // domain rules false-negatived. extractHost must also terminate at
        // '?' and '#'.
        PermissionRule rule = RuleParser.parse("WebFetch(domain:*.acme.io)");
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://api.acme.io?x=1"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://api.acme.io#anchor"))))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.of("WebFetch", Map.of("url", "https://api.acme.io"))))
                .isTrue();
    }

    // ---------- MCP matching ----------

    @Test
    void mcpQualifiedMatchesExactServerAndTool() {
        PermissionRule rule = RuleParser.parse("MCP(slack:post-message)");
        assertThat(rule.matches(
                PermissionRequest.mcp("slack", "post-message", Map.of())))
                .isTrue();
        assertThat(rule.matches(
                PermissionRequest.mcp("slack", "read-channel", Map.of())))
                .isFalse();
        assertThat(rule.matches(
                PermissionRequest.mcp("github", "post-message", Map.of())))
                .isFalse();
    }

    @Test
    void mcpWildcardsMatchAnyServerOrTool() {
        PermissionRule serverWildcard = RuleParser.parse("MCP(*:post-message)");
        assertThat(serverWildcard.matches(
                PermissionRequest.mcp("slack", "post-message", Map.of())))
                .isTrue();
        assertThat(serverWildcard.matches(
                PermissionRequest.mcp("discord", "post-message", Map.of())))
                .isTrue();

        PermissionRule toolWildcard = RuleParser.parse("MCP(slack:*)");
        assertThat(toolWildcard.matches(
                PermissionRequest.mcp("slack", "any-tool", Map.of())))
                .isTrue();
    }

    // ---------- Wildcard tool name ----------

    @Test
    void wildcardToolMatchesAnything() {
        PermissionRule rule = new PermissionRule("*", new PermissionRule.Specifier.Any());
        assertThat(rule.matches(PermissionRequest.of("Bash", Map.of("command", "ls")))).isTrue();
        assertThat(rule.matches(PermissionRequest.of("WebFetch", Map.of("url", "http://x/")))).isTrue();
    }

    // ---------- Rule list evaluation ----------

    @Test
    void denyRulesWinOverAllow() {
        PermissionRules rules = PermissionRules.builder()
                .allow("Bash")
                .deny("Bash(rm -rf *)")
                .build();
        assertThat(rules.evaluate(PermissionRequest.of("Bash", Map.of("command", "ls"))))
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(rules.evaluate(PermissionRequest.of("Bash", Map.of("command", "rm -rf /"))))
                .isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void unmatchedReturnsUnmatched() {
        PermissionRules rules = PermissionRules.builder()
                .allow("Bash(npm *)")
                .build();
        assertThat(rules.evaluate(PermissionRequest.of("WebFetch", Map.of("url", "http://x/"))))
                .isEqualTo(PermissionDecision.UNMATCHED);
    }

    @Test
    void askRulesEvaluatedBeforeAllow() {
        PermissionRules rules = PermissionRules.builder()
                .ask("Bash(git push *)")
                .allow("Bash")
                .build();
        assertThat(rules.evaluate(PermissionRequest.of("Bash", Map.of("command", "git push origin main"))))
                .isEqualTo(PermissionDecision.ASK);
        assertThat(rules.evaluate(PermissionRequest.of("Bash", Map.of("command", "ls"))))
                .isEqualTo(PermissionDecision.ALLOW);
    }

    // ---------- Merge / layering ----------

    @Test
    void mergeConcatenatesAllLayers() {
        // Note: "Bash(npm publish*)" — trailing `*` (no space) matches both
        // "npm publish" and "npm publish --dry-run". Using "Bash(npm publish *)"
        // (with space) would require at least one trailing argument.
        PermissionRules base = PermissionRules.builder().allow("Bash(npm *)").build();
        PermissionRules project = PermissionRules.builder().deny("Bash(npm publish*)").build();
        PermissionRules merged = PermissionRules.merge(base, project);

        assertThat(merged.evaluate(PermissionRequest.of("Bash", Map.of("command", "npm install"))))
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(merged.evaluate(PermissionRequest.of("Bash", Map.of("command", "npm publish"))))
                .isEqualTo(PermissionDecision.DENY);
        assertThat(merged.evaluate(PermissionRequest.of("Bash", Map.of("command", "npm publish --dry-run"))))
                .isEqualTo(PermissionDecision.DENY);
    }
}
