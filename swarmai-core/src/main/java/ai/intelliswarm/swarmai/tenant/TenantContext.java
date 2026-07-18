package ai.intelliswarm.swarmai.tenant;

import ai.intelliswarm.swarmai.observability.core.ObservabilityContext;

/**
 * Static facade over ObservabilityContext attributes for tenant identification.
 * Provides a convenient API for reading and writing the current tenant ID
 * without directly coupling to the observability layer.
 */
public final class TenantContext {

    private static final String TENANT_ID_ATTRIBUTE = "tenantId";

    /**
     * The reserved separator between tenant id and agent id in scoped keys (see
     * {@code TenantAwareMemory}). A tenant id must not contain it, or prefix-based scoping stops
     * being injective and tenants collide: a tenant {@code "acme"} scoped to prefix {@code "acme::"}
     * matches a tenant {@code "acme::sub"}'s agent ids ({@code "acme::sub::agent1"} starts with
     * {@code "acme::"}), so {@code acme} reads {@code acme::sub}'s memories. Rejecting the separator
     * at the boundary keeps the prefix relation a true tree.
     */
    public static final String RESERVED_SEPARATOR = "::";

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
        if (tenantId != null && tenantId.contains(RESERVED_SEPARATOR)) {
            // Fail loud at the boundary rather than silently admit a colliding tenant id.
            throw new IllegalArgumentException(
                    "Tenant id must not contain the reserved separator '" + RESERVED_SEPARATOR
                            + "' (it is used to scope tenant data and would allow cross-tenant "
                            + "reads): " + tenantId);
        }
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
