package ai.intelliswarm.swarmai.enterprise.security;

/**
 * Role-Based Access Control (RBAC) enforcer for SwarmAI enterprise operations.
 *
 * <p>Implementations map each user to a {@link Role} and resolve permissions
 * through the role's {@link Role#getPermissions()} set. The enforcer can be
 * used both as a hard gate ({@link #checkPermission}) that throws on denial,
 * and as a soft query ({@link #hasPermission}) for conditional logic.
 *
 * @see Role
 * @see Permission
 * @see AccessDeniedException
 */
public interface RbacEnforcer {

    /**
     * Asserts that the given user holds the specified permission.
     *
     * @param userId     the identifier of the user to check
     * @param permission the required permission
     * @throws AccessDeniedException if the user's role does not include the permission
     */
    void checkPermission(String userId, Permission permission) throws AccessDeniedException;

    /**
     * Returns {@code true} if the given user holds the specified permission.
     *
     * @param userId     the identifier of the user to check
     * @param permission the permission to test
     * @return {@code true} when the user's role grants the permission
     */
    boolean hasPermission(String userId, Permission permission);

    /**
     * Assigns a role to the given user, replacing any previously assigned role.
     *
     * @param userId the identifier of the user
     * @param role   the role to assign
     */
    void assignRole(String userId, Role role);

    /**
     * Returns the role currently assigned to the given user.
     *
     * @param userId the identifier of the user
     * @return the user's role, or a default role if none has been explicitly assigned
     */
    Role getRole(String userId);
}
