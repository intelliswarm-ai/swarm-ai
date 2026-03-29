package ai.intelliswarm.swarmai.tenant;

/**
 * Thrown when a tenant operation would exceed the configured resource quota.
 * Carries contextual information about which tenant and quota type were violated.
 */
public class TenantQuotaExceededException extends RuntimeException {

    private final String tenantId;
    private final String quotaType;

    /**
     * Creates a new quota exceeded exception.
     *
     * @param tenantId  the tenant that exceeded the quota
     * @param quotaType the type of quota that was exceeded (e.g., "workflow", "skill")
     * @param message   a human-readable description of the violation
     */
    public TenantQuotaExceededException(String tenantId, String quotaType, String message) {
        super(message);
        this.tenantId = tenantId;
        this.quotaType = quotaType;
    }

    /**
     * Returns the tenant ID that exceeded the quota.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the type of quota that was exceeded.
     */
    public String getQuotaType() {
        return quotaType;
    }
}
