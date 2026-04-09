package ai.intelliswarm.swarmai.enterprise.tenant;

import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.memory.Memory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.tenant.TenantContext;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tenant isolation tests.
 *
 * These tests verify that multi-tenant data isolation ACTUALLY WORKS —
 * not just that the API exists, but that Tenant A CANNOT access Tenant B's data
 * under any circumstances. Every failure here is a DATA BREACH vulnerability.
 */
@DisplayName("Tenant Isolation — Adversarial Data Breach Prevention")
class TenantIsolationTest {

    private Memory underlying;
    private TenantAwareMemory memory;

    @BeforeEach
    void setUp() {
        underlying = new InMemoryMemory();
        memory = new TenantAwareMemory(underlying);
        ObservabilityContext.clear();
    }

    @AfterEach
    void cleanup() {
        ObservabilityContext.clear();
    }

    private void setTenant(String tenantId) {
        ObservabilityContext.create().withTenantId(tenantId);
    }

    // ================================================================
    // MEMORY ISOLATION — save() and getRecentMemories() must be scoped
    // ================================================================

    @Nested
    @DisplayName("Memory Save/Retrieve Isolation")
    class MemoryIsolation {

        @Test
        @DisplayName("Tenant A's memories are invisible to Tenant B via getRecentMemories()")
        void tenantAInvisibleToTenantB() {
            // Tenant A saves data
            setTenant("tenant-a");
            memory.save("agent-1", "Tenant A secret financial data", Map.of());
            memory.save("agent-1", "Tenant A customer records", Map.of());

            // Tenant B tries to read same agentId
            ObservabilityContext.clear();
            setTenant("tenant-b");
            List<String> tenantBResults = memory.getRecentMemories("agent-1", 10);

            assertTrue(tenantBResults.isEmpty(),
                "DATA BREACH: Tenant B can see Tenant A's memories via getRecentMemories(). " +
                "Found: " + tenantBResults);
        }

        @Test
        @DisplayName("Tenant A can read its own memories")
        void tenantACanReadOwnData() {
            setTenant("tenant-a");
            memory.save("agent-1", "My private data", Map.of());

            List<String> results = memory.getRecentMemories("agent-1", 10);
            assertFalse(results.isEmpty(), "Tenant A should see its own data");
            assertTrue(results.get(0).contains("My private data"));
        }

        @Test
        @DisplayName("same agentId in different tenants stores separately")
        void sameAgentIdDifferentTenants() {
            setTenant("tenant-a");
            memory.save("shared-agent", "Tenant A data", Map.of());

            ObservabilityContext.clear();
            setTenant("tenant-b");
            memory.save("shared-agent", "Tenant B data", Map.of());

            // Verify each tenant only sees their data
            ObservabilityContext.clear();
            setTenant("tenant-a");
            List<String> aResults = memory.getRecentMemories("shared-agent", 10);

            ObservabilityContext.clear();
            setTenant("tenant-b");
            List<String> bResults = memory.getRecentMemories("shared-agent", 10);

            assertTrue(aResults.stream().allMatch(r -> r.contains("Tenant A")),
                "Tenant A should only see its own data. Got: " + aResults);
            assertTrue(bResults.stream().allMatch(r -> r.contains("Tenant B")),
                "Tenant B should only see its own data. Got: " + bResults);
        }
    }

    // ================================================================
    // SEARCH ISOLATION — search() must also be tenant-scoped
    // ================================================================

    @Nested
    @DisplayName("Search Isolation")
    class SearchIsolation {

        @Test
        @DisplayName("search() is tenant-scoped — Tenant B cannot find Tenant A's data")
        void searchIsTenantScoped() {
            // Tenant A saves sensitive data
            setTenant("tenant-a");
            memory.save("agent-1", "Tenant A confidential revenue: $5M", Map.of());

            // Tenant B searches for "revenue"
            ObservabilityContext.clear();
            setTenant("tenant-b");
            List<String> results = memory.search("revenue", 10);

            assertTrue(results.isEmpty(),
                "Tenant B must NOT see Tenant A's data via search(). Found: " + results);
        }

        @Test
        @DisplayName("search() returns own tenant's data correctly")
        void searchReturnsOwnTenantData() {
            setTenant("tenant-a");
            memory.save("agent-1", "Tenant A revenue report: $5M quarterly", Map.of());
            memory.save("agent-1", "Tenant A expense report: $3M operational", Map.of());

            List<String> results = memory.search("report", 10);

            assertEquals(2, results.size(),
                "Tenant A should find its own 2 reports. Got: " + results.size());
        }
    }

    // ================================================================
    // NULL TENANT CONTEXT — What happens when no tenant is set?
    // ================================================================

    @Nested
    @DisplayName("Null Tenant Context Behavior")
    class NullTenantContext {

        @Test
        @DisplayName("save without tenant context stores with raw agentId")
        void saveWithoutTenantUsesRawId() {
            // No tenant set — TenantContext.currentTenantId() returns null
            ObservabilityContext.clear();
            memory.save("agent-1", "Unscoped data", Map.of());

            // The data is stored under raw "agent-1" (no tenant prefix)
            // This means it's accessible to ALL tenants — a potential leak
            List<String> rawResults = underlying.getRecentMemories("agent-1", 10);
            assertFalse(rawResults.isEmpty(),
                "Data saved without tenant should be stored (backward compat)");
        }

        @Test
        @DisplayName("SECURITY CONCERN: null tenant can read all unscoped data")
        void nullTenantReadsUnscopedData() {
            // Save data without tenant
            ObservabilityContext.clear();
            memory.save("agent-1", "Unscoped secret", Map.of());

            // Now a tenant tries to read it
            setTenant("tenant-a");
            List<String> results = memory.getRecentMemories("agent-1", 10);

            // Tenant A should NOT see unscoped data — it was stored under "agent-1"
            // but Tenant A looks under "tenant-a::agent-1"
            assertTrue(results.isEmpty(),
                "Tenant A should not see data stored without tenant scope");
        }

        @Test
        @DisplayName("SECURITY CONCERN: no tenant set means no isolation")
        void noTenantNoIsolation() {
            // Two different callers, neither sets tenant
            ObservabilityContext.clear();
            memory.save("agent-1", "Caller 1 data", Map.of());

            ObservabilityContext.clear();
            List<String> results = memory.getRecentMemories("agent-1", 10);

            // Both see the same data because there's no tenant isolation
            assertFalse(results.isEmpty(),
                "Without tenant context, data is shared (backward compat). " +
                "This is a known limitation — the framework should warn when " +
                "multi-tenant mode is enabled but TenantContext is not set.");
        }
    }

    // ================================================================
    // clearForAgent() ISOLATION
    // ================================================================

    @Nested
    @DisplayName("Clear Isolation")
    class ClearIsolation {

        @Test
        @DisplayName("clearForAgent scoped to tenant")
        void clearScopedToTenant() {
            setTenant("tenant-a");
            memory.save("agent-1", "Tenant A data", Map.of());

            ObservabilityContext.clear();
            setTenant("tenant-b");
            memory.save("agent-1", "Tenant B data", Map.of());

            // Tenant A clears their agent
            ObservabilityContext.clear();
            setTenant("tenant-a");
            memory.clearForAgent("agent-1");

            // Tenant A's data should be gone
            List<String> aResults = memory.getRecentMemories("agent-1", 10);
            assertTrue(aResults.isEmpty(), "Tenant A's data should be cleared");

            // Tenant B's data should be untouched
            ObservabilityContext.clear();
            setTenant("tenant-b");
            List<String> bResults = memory.getRecentMemories("agent-1", 10);
            assertFalse(bResults.isEmpty(),
                "Tenant B's data should survive Tenant A's clearForAgent()");
        }
    }
}
