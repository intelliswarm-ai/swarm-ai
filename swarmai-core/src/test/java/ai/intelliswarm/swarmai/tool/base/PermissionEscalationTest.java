package ai.intelliswarm.swarmai.tool.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for permission escalation prevention.
 *
 * These tests verify that the permission model cannot be bypassed.
 * Every failure here is a SECURITY VULNERABILITY that allows agents
 * to execute operations above their privilege level.
 */
@DisplayName("Permission Escalation Prevention — Security Gate")
class PermissionEscalationTest {

    @Nested
    @DisplayName("PermissionLevel.isPermittedBy() — Enforcement Logic")
    class PermissionEnforcement {

        @Test
        @DisplayName("READ_ONLY agent can use READ_ONLY tools")
        void readOnlyPermitsReadOnly() {
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.READ_ONLY));
        }

        @Test
        @DisplayName("READ_ONLY agent CANNOT use WORKSPACE_WRITE tools")
        void readOnlyBlocksWrite() {
            assertFalse(PermissionLevel.WORKSPACE_WRITE.isPermittedBy(PermissionLevel.READ_ONLY),
                "READ_ONLY agent must NOT invoke WORKSPACE_WRITE tools");
        }

        @Test
        @DisplayName("READ_ONLY agent CANNOT use DANGEROUS tools")
        void readOnlyBlocksDangerous() {
            assertFalse(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.READ_ONLY),
                "READ_ONLY agent must NOT invoke DANGEROUS tools (shell, network, delete)");
        }

        @Test
        @DisplayName("WORKSPACE_WRITE agent CANNOT use DANGEROUS tools")
        void writeBlocksDangerous() {
            assertFalse(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.WORKSPACE_WRITE),
                "WORKSPACE_WRITE agent must NOT invoke DANGEROUS tools");
        }

        @Test
        @DisplayName("DANGEROUS agent can use all non-approval tools")
        void dangerousPermitsLower() {
            assertTrue(PermissionLevel.READ_ONLY.isPermittedBy(PermissionLevel.DANGEROUS));
            assertTrue(PermissionLevel.WORKSPACE_WRITE.isPermittedBy(PermissionLevel.DANGEROUS));
            assertTrue(PermissionLevel.DANGEROUS.isPermittedBy(PermissionLevel.DANGEROUS));
        }

        @Test
        @DisplayName("REQUIRES_APPROVAL is above all other levels")
        void requiresApprovalIsHighest() {
            assertFalse(PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(PermissionLevel.DANGEROUS),
                "REQUIRES_APPROVAL tools must NOT be invoked even by DANGEROUS agents");
        }

        @Test
        @DisplayName("SECURITY VULNERABILITY: null agentMode permits DANGEROUS tools")
        void nullAgentModePermitsDangerous() {
            // This is a REAL BUG: isPermittedBy(null) returns true for ALL levels
            // An agent created without a permissionMode can execute shell commands
            boolean permitted = PermissionLevel.DANGEROUS.isPermittedBy(null);

            // This SHOULD be false — null permission should default to READ_ONLY, not allow everything
            assertFalse(permitted,
                "SECURITY VULNERABILITY: null agentMode permits DANGEROUS tools. " +
                "An agent without explicit permissionMode can execute shell commands, " +
                "delete files, and make network connections. " +
                "Fix: isPermittedBy(null) should return false or default to READ_ONLY.");
        }

        @Test
        @DisplayName("SECURITY VULNERABILITY: null agentMode permits REQUIRES_APPROVAL tools")
        void nullAgentModePermitsApproval() {
            boolean permitted = PermissionLevel.REQUIRES_APPROVAL.isPermittedBy(null);

            assertFalse(permitted,
                "SECURITY VULNERABILITY: null agentMode bypasses REQUIRES_APPROVAL. " +
                "An agent without permissionMode can skip governance gates entirely.");
        }
    }
}
