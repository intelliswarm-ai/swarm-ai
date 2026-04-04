package ai.intelliswarm.swarmai.enterprise.tenant;

import ai.intelliswarm.swarmai.knowledge.InMemoryKnowledge;
import ai.intelliswarm.swarmai.memory.InMemoryMemory;
import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import ai.intelliswarm.swarmai.tenant.TenantContext;
import ai.intelliswarm.swarmai.tenant.TenantQuotaExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Enterprise Tenant Tests")
class EnterpriseTenantTest {

    @AfterEach
    void tearDown() {
        ObservabilityContext.clear();
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

            List<String> directResults = delegate.getRecentMemories("tenant-alpha::agent-1", 10);
            assertEquals(1, directResults.size());
            assertEquals("tenant content", directResults.get(0));

            List<String> unprefixedResults = delegate.getRecentMemories("agent-1", 10);
            assertTrue(unprefixedResults.isEmpty());
        }

        @Test
        @DisplayName("isolates memories between tenants")
        void save_differentTenants_isolated() {
            TenantContext.setTenantId("tenant-alpha");
            tenantMemory.save("agent-1", "alpha content", null);

            TenantContext.setTenantId("tenant-beta");
            tenantMemory.save("agent-1", "beta content", null);

            TenantContext.setTenantId("tenant-alpha");
            List<String> alphaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertEquals(1, alphaResults.size());
            assertEquals("alpha content", alphaResults.get(0));

            TenantContext.setTenantId("tenant-beta");
            List<String> betaResults = tenantMemory.getRecentMemories("agent-1", 10);
            assertEquals(1, betaResults.size());
            assertEquals("beta content", betaResults.get(0));
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

            TenantContext.setTenantId("tenant-alpha");
            List<String> alphaSources = tenantKnowledge.getSources();
            assertEquals(1, alphaSources.size());
            assertEquals("source-1", alphaSources.get(0));
        }
    }

    @Nested
    @DisplayName("InMemoryTenantQuotaEnforcer")
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
        }

        @Test
        @DisplayName("tracks active workflow count correctly")
        void activeWorkflowCount_tracksCorrectly() {
            assertEquals(0, enforcer.getActiveWorkflowCount("tenant-x"));
            enforcer.recordWorkflowStart("tenant-x");
            assertEquals(1, enforcer.getActiveWorkflowCount("tenant-x"));
            enforcer.recordWorkflowEnd("tenant-x");
            assertEquals(0, enforcer.getActiveWorkflowCount("tenant-x"));
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
        }

        @Test
        @DisplayName("builder overrides values")
        void builder_overrideValues() {
            TenantResourceQuota quota = TenantResourceQuota.builder("custom-tenant")
                    .maxConcurrentWorkflows(50)
                    .maxSkills(500)
                    .build();
            assertEquals(50, quota.maxConcurrentWorkflows());
            assertEquals(500, quota.maxSkills());
        }
    }
}
