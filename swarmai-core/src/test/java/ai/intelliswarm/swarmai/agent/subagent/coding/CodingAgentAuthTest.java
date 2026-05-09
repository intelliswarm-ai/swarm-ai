package ai.intelliswarm.swarmai.agent.subagent.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link CodingAgentAuth}.
 *
 * <p>The auth helper reads from {@code System.getenv} which we cannot
 * reliably override in-process, so most tests verify the
 * <em>filesystem-side</em> detection path: write/don't-write
 * {@code auth.json} under a temp dir, point {@code user.home} at it via
 * {@link System#setProperty}, and assert.
 */
class CodingAgentAuthTest {

    /** Snapshot {@code user.home} so each test can restore it. */
    private String savedUserHome;

    @org.junit.jupiter.api.BeforeEach
    void snapshotUserHome() {
        savedUserHome = System.getProperty("user.home");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreUserHome() {
        if (savedUserHome != null) {
            System.setProperty("user.home", savedUserHome);
        }
    }

    @Nested
    @DisplayName("Codex auth detection")
    class Codex {

        @Test
        @DisplayName("codexHome() defaults to ~/.codex when CODEX_HOME unset")
        void defaultsToUserHome(@TempDir Path tmp) {
            System.setProperty("user.home", tmp.toString());
            // We can't unset CODEX_HOME in-process, but if it happens to be
            // unset on the test runner the default branch executes; if it's
            // set, we still verify the path is non-null and points somewhere.
            Path home = CodingAgentAuth.codexHome();
            assertTrue(home != null && !home.toString().isBlank());
            if (System.getenv("CODEX_HOME") == null) {
                assertEquals(tmp.resolve(".codex"), home);
            }
        }

        @Test
        @DisplayName("codexHasCachedLogin() = false when auth.json missing")
        void noCachedLoginWhenFileMissing(@TempDir Path tmp) {
            assumeNoCodexHomeEnv();
            System.setProperty("user.home", tmp.toString());
            assertFalse(CodingAgentAuth.codexHasCachedLogin());
        }

        @Test
        @DisplayName("codexHasCachedLogin() = true when auth.json present")
        void cachedLoginWhenFilePresent(@TempDir Path tmp) throws IOException {
            assumeNoCodexHomeEnv();
            System.setProperty("user.home", tmp.toString());
            Path codexHome = tmp.resolve(".codex");
            Files.createDirectories(codexHome);
            Files.writeString(codexHome.resolve("auth.json"), "{\"token\":\"xxx\"}");

            assertTrue(CodingAgentAuth.codexHasCachedLogin());
        }

        @Test
        @DisplayName("requireCodexAuth() throws with helpful message when neither login nor env is set")
        void requireThrowsWhenAbsent(@TempDir Path tmp) {
            assumeNoCodexHomeEnv();
            assumeNoOpenAiKey();
            System.setProperty("user.home", tmp.toString());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    CodingAgentAuth::requireCodexAuth);
            assertTrue(e.getMessage().contains("codex login"),
                    "error message should mention `codex login`: " + e.getMessage());
            assertTrue(e.getMessage().contains("OPENAI_API_KEY"),
                    "error message should mention OPENAI_API_KEY: " + e.getMessage());
        }

        @Test
        @DisplayName("hasCodexAuth() = true when cached login exists, even if no env key")
        void cachedLoginIsEnough(@TempDir Path tmp) throws IOException {
            assumeNoCodexHomeEnv();
            System.setProperty("user.home", tmp.toString());
            Path codexHome = tmp.resolve(".codex");
            Files.createDirectories(codexHome);
            Files.writeString(codexHome.resolve("auth.json"), "{}");

            assertTrue(CodingAgentAuth.hasCodexAuth());
        }
    }

    @Nested
    @DisplayName("Claude Code auth detection")
    class Claude {

        @Test
        @DisplayName("claudeHasCachedLogin() = true when .credentials.json present")
        void cachedLogin(@TempDir Path tmp) throws IOException {
            assumeNoClaudeHomeEnv();
            System.setProperty("user.home", tmp.toString());
            Path claudeHome = tmp.resolve(".claude");
            Files.createDirectories(claudeHome);
            Files.writeString(claudeHome.resolve(".credentials.json"), "{}");

            assertTrue(CodingAgentAuth.claudeHasCachedLogin());
        }

        @Test
        @DisplayName("requireClaudeAuth() throws when nothing is set")
        void requireThrowsWhenAbsent(@TempDir Path tmp) {
            assumeNoClaudeHomeEnv();
            assumeNoAnthropicKey();
            System.setProperty("user.home", tmp.toString());

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    CodingAgentAuth::requireClaudeAuth);
            assertTrue(e.getMessage().contains("claude login"));
            assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"));
        }
    }

    @Nested
    @DisplayName("env extraction")
    class EnvExtraction {

        @Test
        @DisplayName("codexEnvFromSystem() only includes set, non-blank vars")
        void codexEnvOnlyIncludesSet() {
            Map<String, String> env = CodingAgentAuth.codexEnvFromSystem();
            // Either empty (clean test env) or contains keys we asked for.
            for (String key : env.keySet()) {
                assertTrue(java.util.List.of("OPENAI_API_KEY", "OPENAI_BASE_URL",
                                "OPENAI_ORG_ID", "CODEX_HOME").contains(key),
                        "unexpected env key surfaced: " + key);
            }
            for (String value : env.values()) {
                assertFalse(value.isBlank(), "env values should be non-blank");
            }
        }

        @Test
        @DisplayName("claudeEnvFromSystem() returns immutable-friendly map of allowed keys")
        void claudeEnvOnlyAllowedKeys() {
            Map<String, String> env = CodingAgentAuth.claudeEnvFromSystem();
            for (String key : env.keySet()) {
                assertTrue(java.util.List.of("ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL",
                                "CLAUDE_CONFIG_DIR", "CLAUDE_CODE_USE_BEDROCK",
                                "CLAUDE_CODE_USE_VERTEX").contains(key),
                        "unexpected env key surfaced: " + key);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Test helpers — skip rather than fail when the host happens to have
    // env vars set that would invalidate our user.home redirection.
    // ---------------------------------------------------------------------

    private static void assumeNoCodexHomeEnv() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CODEX_HOME") == null,
                "CODEX_HOME is set on this host; user.home redirection would not take effect");
    }

    private static void assumeNoClaudeHomeEnv() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("CLAUDE_CONFIG_DIR") == null,
                "CLAUDE_CONFIG_DIR is set on this host");
    }

    private static void assumeNoOpenAiKey() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("OPENAI_API_KEY") == null
                || System.getenv("OPENAI_API_KEY").isBlank(),
                "OPENAI_API_KEY is set on this host; requireCodexAuth would not throw");
    }

    private static void assumeNoAnthropicKey() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("ANTHROPIC_API_KEY") == null
                || System.getenv("ANTHROPIC_API_KEY").isBlank(),
                "ANTHROPIC_API_KEY is set on this host; requireClaudeAuth would not throw");
    }
}
