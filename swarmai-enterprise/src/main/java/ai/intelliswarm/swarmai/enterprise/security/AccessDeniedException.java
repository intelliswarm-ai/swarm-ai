package ai.intelliswarm.swarmai.enterprise.security;

/**
 * Thrown when a user attempts an operation for which their current
 * {@link Role} does not grant the required {@link Permission}.
 *
 * <p>Carries the user identifier, the denied permission, and the role
 * that was evaluated so that callers can produce actionable audit logs
 * and error messages.
 *
 * @see RbacEnforcer
 */
public class AccessDeniedException extends RuntimeException {

    private final String userId;
    private final Permission permission;
    private final Role role;

    /**
     * Creates a new access-denied exception.
     *
     * @param userId     the identifier of the user whose access was denied
     * @param permission the permission that was required
     * @param role       the role assigned to the user at the time of the check
     */
    public AccessDeniedException(String userId, Permission permission, Role role) {
        super(String.format(
                "Access denied: user '%s' with role %s does not have permission %s",
                userId, role, permission));
        this.userId = userId;
        this.permission = permission;
        this.role = role;
    }

    /**
     * Returns the identifier of the user whose access was denied.
     *
     * @return user id
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the permission that was required but not granted.
     *
     * @return the denied permission
     */
    public Permission getPermission() {
        return permission;
    }

    /**
     * Returns the role assigned to the user at the time of the check.
     *
     * @return the user's role
     */
    public Role getRole() {
        return role;
    }
}
