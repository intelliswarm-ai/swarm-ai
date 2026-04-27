package ai.intelliswarm.swarmai.tool.os;

import ai.intelliswarm.swarmai.tool.os.shared.BashRunner;
import ai.intelliswarm.swarmai.tool.os.shared.OsContext;
import ai.intelliswarm.swarmai.tool.os.shared.OsType;
import ai.intelliswarm.swarmai.tool.safety.AutoApproveMutationGuard;
import ai.intelliswarm.swarmai.tool.safety.MutationGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProcessTool — cross-platform list/start/kill")
class ProcessToolTest {

    private ProcessTool tool(OsType os, MutationGuard guard, List<String> denylist) {
        return new ProcessTool(new OsContext(os), new BashRunner(), guard,
                denylist, Duration.ofSeconds(5), true);
    }

    private ProcessTool tool() {
        // Use the actual host OS — list path needs ProcessHandle on Windows or ps on POSIX.
        return new ProcessTool(new OsContext(), new BashRunner(),
                new AutoApproveMutationGuard(),
                List.of("system", "kernel_task", "systemd"),
                Duration.ofSeconds(5), true);
    }

    @Test
    @DisplayName("Tool metadata is correct + cross-platform OS list")
    void metadata() {
        ProcessTool t = tool();
        assertThat(t.getFunctionName()).isEqualTo("os_process");
        assertThat(t.getCategory()).isEqualTo("os-control");
        assertThat(t.getTags()).contains("os-control", "process", "cross-platform");
        assertThat(t.getRequirements().os()).containsExactlyInAnyOrder("windows", "darwin", "linux");
    }

    @Test
    @DisplayName("list returns at least the JVM running this test")
    void listIncludesSelf() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "list"));
        assertThat(result).containsKey("processes");
        Integer count = (Integer) result.get("count");
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @DisplayName("list with filter narrows the result set")
    void listWithFilter() {
        @SuppressWarnings("unchecked")
        Map<String, Object> all = (Map<String, Object>) tool().execute(Map.of(
                "operation", "list"));
        @SuppressWarnings("unchecked")
        Map<String, Object> filtered = (Map<String, Object>) tool().execute(Map.of(
                "operation", "list", "filter", "definitely-no-process-named-this-zzz"));
        assertThat((Integer) filtered.get("count")).isLessThanOrEqualTo((Integer) all.get("count"));
        assertThat((Integer) filtered.get("count")).isEqualTo(0);
    }

    @Test
    @DisplayName("kill missing both pid and name returns a clear error")
    void killWithoutTarget() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "kill", "apply", true));
        assertThat(result.get("error").toString()).contains("'pid' or 'name'");
    }

    @Test
    @DisplayName("kill of a denylisted name is refused outright — even with apply=true and auto-approve")
    void killDenylistedName() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "kill", "name", "systemd", "apply", true));
        assertThat(result.get("error").toString()).contains("denied").contains("systemd");
    }


    @Test
    @DisplayName("kill by name re-checks denylist for each matched process")
    void killByNameRechecksDenylistPerMatch() {
        ProcessTool t = tool(new OsContext().type(), new AutoApproveMutationGuard(), List.of("java"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) t.execute(Map.of(
                "operation", "kill", "name", "jav", "apply", true));
        assertThat(result.get("matched")).isEqualTo(0L);
        assertThat(result).containsKey("skippedDenylisted");
        assertThat(((Number) result.get("skippedDenylisted")).longValue()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("kill plan-only (apply omitted) returns a plan with HIGH risk and no kill action")
    void killPlanOnly() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "kill", "name", "some-fake-process-name-zzz"));
        assertThat(result.get("planned")).isEqualTo(true);
        assertThat(result.get("risk")).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("kill of a non-existent pid surfaces a clear error")
    void killNonExistentPid() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "kill", "pid", 999_999_999, "apply", true));
        assertThat(result.get("error").toString()).contains("no process with pid");
    }

    @Test
    @DisplayName("Mutating op rejected by guard surfaces a clear error")
    void rejectedByGuard() {
        MutationGuard reject = plan -> MutationGuard.Decision.reject("test", "denied");
        ProcessTool t = tool(new OsContext().type(), reject, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) t.execute(Map.of(
                "operation", "kill", "name", "totally-fake-process-zzzz", "apply", true));
        assertThat(result.get("error").toString()).contains("rejected");
    }

    @Test
    @DisplayName("Unknown operation returns a clear error")
    void unknownOperation() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool().execute(Map.of(
                "operation", "halt_and_catch_fire"));
        assertThat(result.get("error").toString()).contains("unknown operation");
    }
}
