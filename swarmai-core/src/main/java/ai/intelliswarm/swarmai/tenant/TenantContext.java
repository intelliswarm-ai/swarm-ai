package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;

/**
 * Static facade over ObservabilityContext attributes for tenant identification.
 * Provides a convenient API for reading and writing the current tenant ID
 * without directly coupling to the observability layer.
 */
public final class TenantContext {

    private static final String TENANT_ID_ATTRIBUTE = "tenantId";

    private TenantContext() {
        // Static utility class
    }

    /**
     * Reads the current tenant ID from the ObservabilityContext.
     *
     * @return the current tenant ID, or null if not set
     */
    public static String currentTenantId() {
        ObservabilityContext ctx = ObservabilityContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        return ctx.getAttribute(TENANT_ID_ATTRIBUTE);
    }

    /**
     * Sets the tenant ID in the current ObservabilityContext.
     * Creates a new context if none exists.
     *
     * @param tenantId the tenant ID to set
     */
    public static void setTenantId(String tenantId) {
        ObservabilityContext.current().withAttribute(TENANT_ID_ATTRIBUTE, tenantId);
    }

    /**
     * Checks whether a tenant context is currently available.
     *
     * @return true if a tenant ID is set in the current context
     */
    public static boolean isSet() {
        return currentTenantId() != null;
    }
}
