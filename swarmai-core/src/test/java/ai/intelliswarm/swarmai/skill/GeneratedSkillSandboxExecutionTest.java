package ai.intelliswarm.swarmai.skill;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes generated Groovy through the real {@code GeneratedSkill.execute()} path
 * under the hardened sandbox.
 *
 * <p>The validator tests only COMPILE skill code. These tests run it, which is what
 * catches the two failure modes that matter in production:
 * <ol>
 *   <li>the sandbox rejecting legitimate skills at runtime (breaking every
 *       self-improving workflow), and</li>
 *   <li>an escape that compiles cleanly but executes anyway.</li>
 * </ol>
 */
@DisplayName("GeneratedSkill sandbox — runtime execution")
class GeneratedSkillSandboxExecutionTest {

    private static BaseTool echoTool() {
        return new BaseTool() {
            @Override
            public String getFunctionName() {
                return "echo";
            }

            @Override
            public String getDescription() {
                return "Echoes the 'text' parameter back";
            }

            @Override
            public Object execute(Map<String, Object> params) {
                return "echoed:" + params.get("text");
            }

            @Override
            public Map<String, Object> getParameterSchema() {
                return Map.of();
            }

            @Override
            public boolean isAsync() {
                return false;
            }
        };
    }

    private static GeneratedSkill codeSkill(String code) {
        SkillDefinition definition = SkillDefinition.builder()
                .name("sandbox_exec_skill")
                .description("Skill under sandbox execution test")
                .type(SkillType.CODE)
                .code(code)
                .build();
        GeneratedSkill skill = new GeneratedSkill(definition);
        skill.setAvailableTools(Map.of("echo", echoTool()));
        return skill;
    }

    @Nested
    @DisplayName("legitimate skills still run")
    class LegitimateExecution {

        @Test
        @DisplayName("tools.<name>.execute(...) works at runtime, not just at compile time")
        void toolExecuteRunsThroughSandbox() {
            GeneratedSkill skill = codeSkill(
                    "def out = tools.echo.execute(Map.of('text', params.get('word')))\nout");

            Object result = skill.execute(Map.of("word", "hello"));

            assertEquals("echoed:hello", result,
                    "The documented tool-calling convention must survive the sandbox");
        }

        @Test
        @DisplayName("subscript tool access works at runtime")
        void toolSubscriptExecuteRuns() {
            GeneratedSkill skill = codeSkill(
                    "def out = tools['echo'].execute(Map.of('text', 'sub'))\nout");

            assertEquals("echoed:sub", skill.execute(Map.of()));
        }

        @Test
        @DisplayName("ordinary data wrangling (collections, JSON, regex, time) runs")
        void dataWranglingRuns() {
            GeneratedSkill skill = codeSkill("""
                    import groovy.json.JsonSlurper
                    def parsed = new JsonSlurper().parseText('{"a":1,"b":2}')
                    def sum = parsed.values().sum()
                    def joined = [3, 1, 2].sort().join('-')
                    def matched = ('order-42' =~ /(\\d+)/)[0][1]
                    def result = "sum=${sum};joined=${joined};matched=${matched}"
                    result
                    """);

            assertEquals("sum=3;joined=1-2-3;matched=42", skill.execute(Map.of()));
        }
    }

    /**
     * {@link GeneratedSkill#execute} fails SOFT by contract: it catches the exception
     * and returns an {@code "Error: ..."} string rather than throwing, so a bad skill
     * degrades the agent's turn instead of killing the swarm. A blocked exploit
     * therefore shows up as an error string plus — the part that actually matters —
     * no side effect.
     */
    @Nested
    @DisplayName("escapes are blocked at execution, not merely at validation")
    class BlockedExecution {

        private static void assertBlocked(Object result) {
            assertTrue(String.valueOf(result).startsWith("Error: Skill execution failed"),
                    "Sandboxed code must be refused at execution time. Got: " + result);
        }

        @Test
        @DisplayName("String.execute() shell escape does not run")
        void stringExecuteBlocked() {
            GeneratedSkill skill = codeSkill("def out = 'echo pwned'.execute().text\nout");

            assertBlocked(skill.execute(Map.of()));
        }

        @Test
        @DisplayName("fully-qualified java.io.File does not run and writes nothing to disk")
        void fileAccessBlocked() {
            java.io.File probe = new java.io.File(System.getProperty("java.io.tmpdir"),
                    "swarmai-sandbox-probe.txt");
            probe.delete();

            GeneratedSkill skill = codeSkill(
                    "def f = new java.io.File('" + probe.getAbsolutePath().replace("\\", "\\\\") + "')\n"
                            + "f.text = 'pwned'\n'done'");

            assertBlocked(skill.execute(Map.of()));
            assertFalse(probe.exists(),
                    "SANDBOX ESCAPE: the skill actually wrote to disk at " + probe);
        }

        @Test
        @DisplayName("reflective classloader access does not run")
        void reflectionBlocked() {
            GeneratedSkill skill = codeSkill("def cl = ''.class.classLoader\n'done'");

            assertBlocked(skill.execute(Map.of()));
        }
    }

    @Nested
    @DisplayName("test/assertion runners share the same sandbox")
    class TestRunnerSandbox {

        @Test
        @DisplayName("executeTest() compiles test code under the hardened config too")
        void executeTestIsSandboxed() {
            GeneratedSkill skill = codeSkill("'ok'");

            // The test code itself is a second injection surface — it must be sandboxed.
            assertThrows(Exception.class,
                    () -> skill.executeTest("'echo pwned'.execute()"),
                    "Test code must run under the same sandbox as skill code");
        }

        @Test
        @DisplayName("ordinary assertions in test code still run")
        void ordinaryTestCodeRuns() {
            GeneratedSkill skill = codeSkill("'ok'");

            assertDoesNotThrow(() -> skill.executeTest("assert result == 'ok'"),
                    "Legitimate assertions must keep working");
        }
    }

    @Nested
    @DisplayName("HYBRID skills")
    class HybridSkills {

        @Test
        @DisplayName("hybrid code phase runs under the sandbox and feeds the prompt")
        void hybridCodeRuns() {
            SkillDefinition definition = SkillDefinition.builder()
                    .name("hybrid_skill")
                    .description("Hybrid skill under sandbox")
                    .type(SkillType.HYBRID)
                    .code("def result = tools.echo.execute(Map.of('text', 'hybrid'))\nresult")
                    .instructionBody("Summarize the data above.")
                    .build();
            GeneratedSkill skill = new GeneratedSkill(definition);
            skill.setAvailableTools(Map.of("echo", echoTool()));

            String output = String.valueOf(skill.execute(Map.of()));

            assertTrue(output.contains("echoed:hybrid"),
                    "Hybrid code phase output must reach the prompt. Got: " + output);
        }
    }

    @Nested
    @DisplayName("bindings")
    class Bindings {

        @Test
        @DisplayName("the tools binding itself is still reachable")
        void toolsBindingPresent() {
            GeneratedSkill skill = codeSkill("tools.containsKey('echo') ? 'yes' : 'no'");

            assertEquals("yes", skill.execute(Map.of()));
        }
    }
}
