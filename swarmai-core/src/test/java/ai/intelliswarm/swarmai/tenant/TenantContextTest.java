package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tenant Context Tests")
class TenantContextTest {

    @AfterEach
    void tearDown() {
        ObservabilityContext.clear();
    }

    @Nested
    @DisplayName("TenantContext")
    class TenantContextTests {

        @Test
        @DisplayName("returns null when no context is set")
        void currentTenantId_noContext_returnsNull() {
            assertNull(TenantContext.currentTenantId());
        }

        @Test
        @DisplayName("isSet returns false when no context is set")
        void isSet_noContext_returnsFalse() {
            assertFalse(TenantContext.isSet());
        }

        @Test
        @DisplayName("sets and reads tenant ID")
        void setAndRead_tenantId() {
            TenantContext.setTenantId("tenant-alpha");

            assertEquals("tenant-alpha", TenantContext.currentTenantId());
            assertTrue(TenantContext.isSet());
        }

        @Test
        @DisplayName("overwrites tenant ID")
        void setTenantId_overwritesPrevious() {
            TenantContext.setTenantId("tenant-alpha");
            TenantContext.setTenantId("tenant-beta");

            assertEquals("tenant-beta", TenantContext.currentTenantId());
        }

        @Test
        @DisplayName("clears with ObservabilityContext")
        void clear_removesContext() {
            TenantContext.setTenantId("tenant-alpha");
            ObservabilityContext.clear();

            assertNull(TenantContext.currentTenantId());
            assertFalse(TenantContext.isSet());
        }
    }

    @Nested
    @DisplayName("TenantAwareMemory")
    class TenantAwareMemoryTests {

        private InMemoryMemory delegate;
        private TenantAwareMemory tenantMemory;

        @BeforeEach
        void setUp() {
            delegate = new InMemoryMemory();
            tenantMemory = new TenantAwareMemory(delegate);
        }

        @Test
        @DisplayName("delegates directly when no tenant is set")
        void save_noTenant_delegatesDirectly() {
            tenantMemory.save("agent-1", "some content", null);

            List<String> results = delegate.getRecentMemories("agent-1", 10);
            assertEquals(1, results.size());
            assertEquals("some content", results.get(0));
        }

        @Test
        @DisplayName("prefixes agentId with tenantId when tenant is set")
        void save_withTenant_prefixesAgentId() {
            TenantContext.setTenantId("tenant-alpha");

            tenantMemory.save("agent-1", "tenant content", null);

            // Direct delegate should have prefixed key
            List<String> directResults = delegate.getRecentMemories("tenant-alpha::agent-1", 10);
            assertEquals(1, directResults.size());
            assertEquals("tenant content", directResults.get(0));

            // Original agent ID should have no results in the delegate
            List<String> unprefixedResults = delegate.getRecentMemories("agent-1", 10);
            assertTrue(unprefixedResults.isEmpty());
        }

        @Test
        @DisplayName("isolates memories between tenants")
        void save_differentTenants_isolated() {
            // Tenant Alpha saves
            TenantContext.setTenantId("tenant-alpha");
            tenantMemory.save("agent-1", "alpha content", null);

            // Tenant Beta saves
            TenantContext.setTenantId("tenant-beta");
            tenantMemory.save("agent-1", "beta content", null);

            // Check isolation: tenant-alpha should only see its own content
            TenantContext.setTenantId("tenant-alpha");
            List<String> alphaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertEquals(1, alphaResults.size());
            assertEquals("alpha content", alphaResults.get(0));

            // Check isolation: tenant-beta should only see its own content
            TenantContext.setTenantId("tenant-beta");
            List<String> betaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertEquals(1, betaResults.size());
            assertEquals("beta content", betaResults.get(0));
        }

        @Test
        @DisplayName("clearForAgent only clears tenant-scoped agent")
        void clearForAgent_onlyClears_tenantScopedAgent() {
            TenantContext.setTenantId("tenant-alpha");
            tenantMemory.save("agent-1", "alpha content", null);

            TenantContext.setTenantId("tenant-beta");
            tenantMemory.save("agent-1", "beta content", null);

            // Clear only tenant-alpha's agent-1
            TenantContext.setTenantId("tenant-alpha");
            tenantMemory.clearForAgent("agent-1");

            // tenant-alpha's data should be gone
            List<String> alphaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertTrue(alphaResults.isEmpty());

            // tenant-beta's data should remain
            TenantContext.setTenantId("tenant-beta");
            List<String> betaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertEquals(1, betaResults.size());
        }

        @Test
        @DisplayName("size and isEmpty delegate to underlying memory")
        void sizeAndIsEmpty_delegateDirectly() {
            assertTrue(tenantMemory.isEmpty());
            assertEquals(0, tenantMemory.size());

            TenantContext.setTenantId("tenant-alpha");
            tenantMemory.save("agent-1", "content", null);

            assertFalse(tenantMemory.isEmpty());
            assertEquals(1, tenantMemory.size());
        }
    }

    @Nested
    @DisplayName("TenantAwareKnowledge")
    class TenantAwareKnowledgeTests {

        private InMemoryKnowledge delegate;
        private TenantAwareKnowledge tenantKnowledge;

        @BeforeEach
        void setUp() {
            delegate = new InMemoryKnowledge();
            tenantKnowledge = new TenantAwareKnowledge(delegate);
        }

        @Test
        @DisplayName("delegates directly when no tenant is set")
        void addSource_noTenant_delegatesDirectly() {
            tenantKnowledge.addSource("source-1", "content", null);

            assertTrue(delegate.hasSource("source-1"));
        }

        @Test
        @DisplayName("prefixes sourceId with tenantId when tenant is set")
        void addSource_withTenant_prefixesSourceId() {
            TenantContext.setTenantId("tenant-alpha");

            tenantKnowledge.addSource("source-1", "alpha content", null);

            assertTrue(delegate.hasSource("tenant-alpha::source-1"));
            assertFalse(delegate.hasSource("source-1"));
        }

        @Test
        @DisplayName("getSources filters by current tenant")
        void getSources_filtersByTenant() {
            TenantContext.setTenantId("tenant-alpha");
            tenantKnowledge.addSource("source-1", "alpha content", null);

            TenantContext.setTenantId("tenant-beta");
            tenantKnowledge.addSource("source-2", "beta content", null);

            // Alpha should only see source-1
            TenantContext.setTenantId("tenant-alpha");
            List<String> alphaSources = tenantKnowledge.getSources();
            assertEquals(1, alphaSources.size());
            assertEquals("source-1", alphaSources.get(0));

            // Beta should only see source-2
            TenantContext.setTenantId("tenant-beta");
            List<String> betaSources = tenantKnowledge.getSources();
            assertEquals(1, betaSources.size());
            assertEquals("source-2", betaSources.get(0));
        }

        @Test
        @DisplayName("hasSource checks tenant-scoped ID")
        void hasSource_checksTenantScoped() {
            TenantContext.setTenantId("tenant-alpha");
            tenantKnowledge.addSource("source-1", "content", null);

            assertTrue(tenantKnowledge.hasSource("source-1"));

            TenantContext.setTenantId("tenant-beta");
            assertFalse(tenantKnowledge.hasSource("source-1"));
        }

        @Test
        @DisplayName("removeSource removes tenant-scoped source")
        void removeSource_removesTenantScoped() {
            TenantContext.setTenantId("tenant-alpha");
            tenantKnowledge.addSource("source-1", "content", null);

            TenantContext.setTenantId("tenant-beta");
            tenantKnowledge.addSource("source-1", "other content", null);

            // Remove alpha's source
            TenantContext.setTenantId("tenant-alpha");
            tenantKnowledge.removeSource("source-1");

            assertFalse(tenantKnowledge.hasSource("source-1"));

            // Beta's source should still exist
            TenantContext.setTenantId("tenant-beta");
            assertTrue(tenantKnowledge.hasSource("source-1"));
        }
    }

    @Nested
    @DisplayName("TenantQuotaEnforcer")
    class TenantQuotaEnforcerTests {

        private InMemoryTenantQuotaEnforcer enforcer;

        @BeforeEach
        void setUp() {
            TenantResourceQuota defaultQuota = TenantResourceQuota.builder("__default__")
                    .maxConcurrentWorkflows(2)
                    .maxSkills(5)
                    .build();

            TenantResourceQuota premiumQuota = TenantResourceQuota.builder("premium-tenant")
                    .maxConcurrentWorkflows(10)
                    .maxSkills(50)
                    .build();

            enforcer = new InMemoryTenantQuotaEnforcer(
                    Map.of("premium-tenant", premiumQuota),
                    defaultQuota
            );
        }

        @Test
        @DisplayName("allows workflows within quota")
        void checkWorkflowQuota_withinLimit_noException() {
            assertDoesNotThrow(() -> enforcer.checkWorkflowQuota("regular-tenant"));
        }

        @Test
        @DisplayName("throws when workflow quota exceeded")
        void checkWorkflowQuota_exceedsLimit_throwsException() {
            enforcer.recordWorkflowStart("regular-tenant");
            enforcer.recordWorkflowStart("regular-tenant");

            TenantQuotaExceededException ex = assertThrows(
                    TenantQuotaExceededException.class,
                    () -> enforcer.checkWorkflowQuota("regular-tenant")
            );

            assertEquals("regular-tenant", ex.getTenantId());
            assertEquals("workflow", ex.getQuotaType());
        }

        @Test
        @DisplayName("uses tenant-specific quota when available")
        void checkWorkflowQuota_premiumTenant_usesSpecificQuota() {
            // Premium tenant has limit of 10, fill up to 9
            for (int i = 0; i < 9; i++) {
                enforcer.recordWorkflowStart("premium-tenant");
            }

            // Should still be within quota
            assertDoesNotThrow(() -> enforcer.checkWorkflowQuota("premium-tenant"));

            // One more to hit the limit
            enforcer.recordWorkflowStart("premium-tenant");

            // Now should exceed
            assertThrows(
                    TenantQuotaExceededException.class,
                    () -> enforcer.checkWorkflowQuota("premium-tenant")
            );
        }

        @Test
        @DisplayName("tracks active workflow count correctly")
        void activeWorkflowCount_tracksCorrectly() {
            assertEquals(0, enforcer.getActiveWorkflowCount("tenant-x"));

            enforcer.recordWorkflowStart("tenant-x");
            assertEquals(1, enforcer.getActiveWorkflowCount("tenant-x"));

            enforcer.recordWorkflowStart("tenant-x");
            assertEquals(2, enforcer.getActiveWorkflowCount("tenant-x"));

            enforcer.recordWorkflowEnd("tenant-x");
            assertEquals(1, enforcer.getActiveWorkflowCount("tenant-x"));

            enforcer.recordWorkflowEnd("tenant-x");
            assertEquals(0, enforcer.getActiveWorkflowCount("tenant-x"));
        }

        @Test
        @DisplayName("workflow count does not go negative")
        void recordWorkflowEnd_noNegativeCount() {
            enforcer.recordWorkflowEnd("tenant-x");

            assertEquals(0, enforcer.getActiveWorkflowCount("tenant-x"));
        }

        @Test
        @DisplayName("allows skills within quota")
        void checkSkillQuota_withinLimit_noException() {
            assertDoesNotThrow(() -> enforcer.checkSkillQuota("regular-tenant", 3));
        }

        @Test
        @DisplayName("throws when skill quota exceeded")
        void checkSkillQuota_exceedsLimit_throwsException() {
            TenantQuotaExceededException ex = assertThrows(
                    TenantQuotaExceededException.class,
                    () -> enforcer.checkSkillQuota("regular-tenant", 5)
            );

            assertEquals("regular-tenant", ex.getTenantId());
            assertEquals("skill", ex.getQuotaType());
        }

        @Test
        @DisplayName("quota freed after workflow end allows new workflows")
        void quotaFreed_afterEnd_allowsNewWorkflows() {
            enforcer.recordWorkflowStart("regular-tenant");
            enforcer.recordWorkflowStart("regular-tenant");

            // At limit
            assertThrows(
                    TenantQuotaExceededException.class,
                    () -> enforcer.checkWorkflowQuota("regular-tenant")
            );

            // Free one slot
            enforcer.recordWorkflowEnd("regular-tenant");

            // Should be allowed again
            assertDoesNotThrow(() -> enforcer.checkWorkflowQuota("regular-tenant"));
        }
    }

    @Nested
    @DisplayName("TenantResourceQuota")
    class TenantResourceQuotaTests {

        @Test
        @DisplayName("builder uses default values")
        void builder_defaultValues() {
            TenantResourceQuota quota = TenantResourceQuota.builder("test-tenant").build();

            assertEquals("test-tenant", quota.tenantId());
            assertEquals(10, quota.maxConcurrentWorkflows());
            assertEquals(100, quota.maxSkills());
            assertEquals(10_000, quota.maxMemoryEntries());
            assertEquals(1_000_000, quota.maxTokenBudget());
        }

        @Test
        @DisplayName("builder overrides values")
        void builder_overrideValues() {
            TenantResourceQuota quota = TenantResourceQuota.builder("custom-tenant")
                    .maxConcurrentWorkflows(50)
                    .maxSkills(500)
                    .maxMemoryEntries(50_000)
                    .maxTokenBudget(5_000_000)
                    .build();

            assertEquals("custom-tenant", quota.tenantId());
            assertEquals(50, quota.maxConcurrentWorkflows());
            assertEquals(500, quota.maxSkills());
            assertEquals(50_000, quota.maxMemoryEntries());
            assertEquals(5_000_000, quota.maxTokenBudget());
        }
    }
}
