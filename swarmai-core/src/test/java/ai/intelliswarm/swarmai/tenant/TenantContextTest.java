package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
