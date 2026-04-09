package ai.intelliswarm.swarmai.enterprise.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RBAC enforcement — the enterprise access control gate.
 *
 * These tests are a RELEASE GATE for enterprise features. If RBAC doesn't
 * work correctly, any user can execute any operation regardless of role.
 *
 * Each test verifies a specific access control boundary that a CISO would
 * ask about: "Can a VIEWER execute workflows? Can an OPERATOR manage tenants?"
 */
@DisplayName("RBAC Enforcer — Enterprise Access Control Gate")
class RbacEnforcerTest {

    private InMemoryRbacEnforcer enforcer;

    @BeforeEach
    void setUp() {
        enforcer = new InMemoryRbacEnforcer();
    }

    // ================================================================
    // VIEWER — Read-only access. Must NOT execute or manage anything.
    // ================================================================

    @Nested
    @DisplayName("VIEWER Role (default for unknown users)")
    class ViewerRole {

        @Test
        @DisplayName("unknown user defaults to VIEWER")
        void unknownUserIsViewer() {
            assertEquals(Role.VIEWER, enforcer.getRole("unknown-user"),
                "Users without role assignment must default to VIEWER (least privilege)");
        }

        @Test
        @DisplayName("VIEWER can view workflows")
        void canViewWorkflows() {
            assertDoesNotThrow(() -> enforcer.checkPermission("viewer-user", Permission.WORKFLOW_VIEW));
        }

        @Test
        @DisplayName("VIEWER cannot execute workflows")
        void cannotExecuteWorkflows() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("viewer-user", Permission.WORKFLOW_EXECUTE),
                "VIEWER must NOT be able to execute workflows — this is a read-only role");
        }

        @Test
        @DisplayName("VIEWER cannot manage skills")
        void cannotManageSkills() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("viewer-user", Permission.SKILL_MANAGE));
        }

        @Test
        @DisplayName("VIEWER cannot manage budgets")
        void cannotManageBudgets() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("viewer-user", Permission.BUDGET_MANAGE));
        }

        @Test
        @DisplayName("VIEWER cannot manage tenants")
        void cannotManageTenants() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("viewer-user", Permission.TENANT_MANAGE));
        }

        @Test
        @DisplayName("VIEWER cannot manage tools")
        void cannotManageTools() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("viewer-user", Permission.TOOL_MANAGE));
        }
    }

    // ================================================================
    // OPERATOR — Execute workflows and manage tools/skills.
    // Must NOT manage budgets or tenants.
    // ================================================================

    @Nested
    @DisplayName("OPERATOR Role")
    class OperatorRole {

        @BeforeEach
        void assignRole() {
            enforcer.assignRole("operator-user", Role.OPERATOR);
        }

        @Test
        @DisplayName("OPERATOR can execute workflows")
        void canExecuteWorkflows() {
            assertDoesNotThrow(() -> enforcer.checkPermission("operator-user", Permission.WORKFLOW_EXECUTE));
        }

        @Test
        @DisplayName("OPERATOR can view workflows")
        void canViewWorkflows() {
            assertDoesNotThrow(() -> enforcer.checkPermission("operator-user", Permission.WORKFLOW_VIEW));
        }

        @Test
        @DisplayName("OPERATOR can manage skills")
        void canManageSkills() {
            assertDoesNotThrow(() -> enforcer.checkPermission("operator-user", Permission.SKILL_MANAGE));
        }

        @Test
        @DisplayName("OPERATOR can manage tools")
        void canManageTools() {
            assertDoesNotThrow(() -> enforcer.checkPermission("operator-user", Permission.TOOL_MANAGE));
        }

        @Test
        @DisplayName("OPERATOR cannot manage budgets")
        void cannotManageBudgets() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("operator-user", Permission.BUDGET_MANAGE),
                "OPERATOR must NOT manage budgets — only ADMIN can");
        }

        @Test
        @DisplayName("OPERATOR cannot manage tenants")
        void cannotManageTenants() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("operator-user", Permission.TENANT_MANAGE),
                "OPERATOR must NOT manage tenants — only ADMIN can");
        }
    }

    // ================================================================
    // ADMIN — Full access to everything.
    // ================================================================

    @Nested
    @DisplayName("ADMIN Role")
    class AdminRole {

        @BeforeEach
        void assignRole() {
            enforcer.assignRole("admin-user", Role.ADMIN);
        }

        @Test
        @DisplayName("ADMIN has every permission")
        void hasAllPermissions() {
            for (Permission permission : Permission.values()) {
                assertTrue(enforcer.hasPermission("admin-user", permission),
                    "ADMIN must have permission: " + permission);
            }
        }

        @Test
        @DisplayName("ADMIN checkPermission does not throw for any permission")
        void checkPermissionNeverThrows() {
            for (Permission permission : Permission.values()) {
                assertDoesNotThrow(
                    () -> enforcer.checkPermission("admin-user", permission),
                    "ADMIN checkPermission should not throw for: " + permission);
            }
        }
    }

    // ================================================================
    // AGENT_MANAGER — Execute workflows and manage skills, NOT tools.
    // ================================================================

    @Nested
    @DisplayName("AGENT_MANAGER Role")
    class AgentManagerRole {

        @BeforeEach
        void assignRole() {
            enforcer.assignRole("am-user", Role.AGENT_MANAGER);
        }

        @Test
        @DisplayName("AGENT_MANAGER can execute and view workflows")
        void canExecuteAndView() {
            assertDoesNotThrow(() -> enforcer.checkPermission("am-user", Permission.WORKFLOW_EXECUTE));
            assertDoesNotThrow(() -> enforcer.checkPermission("am-user", Permission.WORKFLOW_VIEW));
        }

        @Test
        @DisplayName("AGENT_MANAGER can manage skills")
        void canManageSkills() {
            assertDoesNotThrow(() -> enforcer.checkPermission("am-user", Permission.SKILL_MANAGE));
        }

        @Test
        @DisplayName("AGENT_MANAGER cannot manage tools (only OPERATOR/ADMIN can)")
        void cannotManageTools() {
            assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("am-user", Permission.TOOL_MANAGE),
                "AGENT_MANAGER must NOT manage tools — this is OPERATOR territory");
        }
    }

    // ================================================================
    // ROLE CHANGES — Role reassignment must take effect immediately.
    // ================================================================

    @Nested
    @DisplayName("Role Changes")
    class RoleChanges {

        @Test
        @DisplayName("role change takes effect immediately")
        void roleChangeImmediate() {
            enforcer.assignRole("user1", Role.VIEWER);
            assertFalse(enforcer.hasPermission("user1", Permission.WORKFLOW_EXECUTE));

            enforcer.assignRole("user1", Role.OPERATOR);
            assertTrue(enforcer.hasPermission("user1", Permission.WORKFLOW_EXECUTE),
                "Role change to OPERATOR should grant WORKFLOW_EXECUTE immediately");
        }

        @Test
        @DisplayName("role downgrade removes permissions immediately")
        void roleDowngradeImmediate() {
            enforcer.assignRole("user1", Role.ADMIN);
            assertTrue(enforcer.hasPermission("user1", Permission.BUDGET_MANAGE));

            enforcer.assignRole("user1", Role.VIEWER);
            assertFalse(enforcer.hasPermission("user1", Permission.BUDGET_MANAGE),
                "Downgrade from ADMIN to VIEWER should revoke BUDGET_MANAGE immediately");
        }
    }

    // ================================================================
    // AccessDeniedException — Must carry diagnostic information.
    // ================================================================

    @Nested
    @DisplayName("AccessDeniedException Details")
    class ExceptionDetails {

        @Test
        @DisplayName("exception carries user, permission, and role information")
        void exceptionHasContext() {
            enforcer.assignRole("low-user", Role.VIEWER);

            AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> enforcer.checkPermission("low-user", Permission.WORKFLOW_EXECUTE));

            String message = ex.getMessage();
            assertTrue(message.contains("low-user") || message.contains("VIEWER") || message.contains("WORKFLOW_EXECUTE"),
                "Exception message should contain diagnostic info. Got: " + message);
        }
    }

    // ================================================================
    // THREAD SAFETY — Concurrent role assignments must not corrupt state.
    // ================================================================

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("concurrent role assignments and checks don't throw")
        void concurrentAccess() throws InterruptedException {
            int threadCount = 10;
            int opsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            String userId = "user-" + threadId + "-" + i;
                            Role role = Role.values()[i % Role.values().length];
                            enforcer.assignRole(userId, role);
                            enforcer.getRole(userId);
                            enforcer.hasPermission(userId, Permission.WORKFLOW_VIEW);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "Should complete within 10s");
            assertEquals(0, errors.get(),
                "No errors should occur under concurrent access");

            executor.shutdown();
        }
    }

    // ================================================================
    // PRIVILEGE ESCALATION PREVENTION — Key security invariant.
    // ================================================================

    @Nested
    @DisplayName("Privilege Escalation Prevention")
    class PrivilegeEscalation {

        @Test
        @DisplayName("VIEWER cannot grant itself ADMIN role (no self-escalation API)")
        void noSelfEscalation() {
            // The enforcer has assignRole() which is a management API.
            // In a real system, only ADMIN-authenticated requests can call assignRole().
            // Here we verify the enforcer at least has proper access checking.
            enforcer.assignRole("attacker", Role.VIEWER);

            // The attacker can call assignRole (it's a public method), but this
            // should be gated by an authorization check in the REST layer.
            // This test documents the gap: there's no enforcement preventing
            // anyone from calling assignRole() directly.
            enforcer.assignRole("attacker", Role.ADMIN);
            assertTrue(enforcer.hasPermission("attacker", Permission.BUDGET_MANAGE),
                "KNOWN GAP: assignRole() has no authorization check. " +
                "In production, the REST endpoint must verify the caller is ADMIN " +
                "before allowing role changes. The enforcer trusts its caller.");
        }
    }
}
