package ai.intelliswarm.swarmai.agent.permission;

import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.permission.PermissionDecision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionModeTest {

    @Test
    void modesDoNotOverrideExplicitDecisions() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertThat(mode.resolve(PermissionDecision.DENY, PermissionLevel.READ_ONLY))
                    .as("DENY survives %s", mode).isEqualTo(PermissionDecision.DENY);
            assertThat(mode.resolve(PermissionDecision.ALLOW, PermissionLevel.READ_ONLY))
                    .as("ALLOW survives %s", mode).isEqualTo(PermissionDecision.ALLOW);
            assertThat(mode.resolve(PermissionDecision.ASK, PermissionLevel.WORKSPACE_WRITE))
                    .as("ASK survives %s", mode).isEqualTo(PermissionDecision.ASK);
        }
    }

    @Test
    void defaultAllowsReadAndAsksForWrite() {
        assertThat(PermissionMode.DEFAULT.resolve(PermissionDecision.UNMATCHED, PermissionLevel.READ_ONLY))
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(PermissionMode.DEFAULT.resolve(PermissionDecision.UNMATCHED, PermissionLevel.WORKSPACE_WRITE))
                .isEqualTo(PermissionDecision.ASK);
        assertThat(PermissionMode.DEFAULT.resolve(PermissionDecision.UNMATCHED, PermissionLevel.DANGEROUS))
                .isEqualTo(PermissionDecision.ASK);
    }

    @Test
    void acceptEditsAllowsWriteButAsksOnDangerous() {
        assertThat(PermissionMode.ACCEPT_EDITS.resolve(PermissionDecision.UNMATCHED, PermissionLevel.WORKSPACE_WRITE))
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(PermissionMode.ACCEPT_EDITS.resolve(PermissionDecision.UNMATCHED, PermissionLevel.DANGEROUS))
                .isEqualTo(PermissionDecision.ASK);
        assertThat(PermissionMode.ACCEPT_EDITS.resolve(PermissionDecision.UNMATCHED, PermissionLevel.REQUIRES_APPROVAL))
                .isEqualTo(PermissionDecision.ASK);
    }

    @Test
    void planModeDeniesWrites() {
        assertThat(PermissionMode.PLAN.resolve(PermissionDecision.UNMATCHED, PermissionLevel.READ_ONLY))
                .isEqualTo(PermissionDecision.ALLOW);
        assertThat(PermissionMode.PLAN.resolve(PermissionDecision.UNMATCHED, PermissionLevel.WORKSPACE_WRITE))
                .isEqualTo(PermissionDecision.DENY);
        assertThat(PermissionMode.PLAN.resolve(PermissionDecision.UNMATCHED, PermissionLevel.DANGEROUS))
                .isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void autoLeavesUnmatchedForExternalClassifier() {
        assertThat(PermissionMode.AUTO.resolve(PermissionDecision.UNMATCHED, PermissionLevel.WORKSPACE_WRITE))
                .isEqualTo(PermissionDecision.UNMATCHED);
    }

    @Test
    void dontAskDeniesEverythingUnmatched() {
        assertThat(PermissionMode.DONT_ASK.resolve(PermissionDecision.UNMATCHED, PermissionLevel.READ_ONLY))
                .isEqualTo(PermissionDecision.DENY);
        assertThat(PermissionMode.DONT_ASK.resolve(PermissionDecision.UNMATCHED, PermissionLevel.DANGEROUS))
                .isEqualTo(PermissionDecision.DENY);
    }

    @Test
    void bypassPermissionsAllowsEverythingUnmatched() {
        assertThat(PermissionMode.BYPASS_PERMISSIONS.resolve(PermissionDecision.UNMATCHED, PermissionLevel.DANGEROUS))
                .isEqualTo(PermissionDecision.ALLOW);
    }

    @Test
    void nullToolLevelDefaultsToReadOnly() {
        assertThat(PermissionMode.DEFAULT.resolve(PermissionDecision.UNMATCHED, null))
                .isEqualTo(PermissionDecision.ALLOW);
    }
}
