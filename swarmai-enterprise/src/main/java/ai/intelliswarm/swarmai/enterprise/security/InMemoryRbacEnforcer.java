package ai.intelliswarm.swarmai.enterprise.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link RbacEnforcer}.
 *
 * <p>Thread-safe: all role assignments are stored in a {@link ConcurrentHashMap}.
 * Users without an explicit role assignment are treated as {@link Role#VIEWER}.
 *
 * <p>Suitable for single-instance deployments and testing. For distributed
 * deployments, an implementation backed by a persistent store (e.g. database
 * or Redis) is recommended.
 */
public class InMemoryRbacEnforcer implements RbacEnforcer {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryRbacEnforcer.class);

    /** Default role for users without an explicit assignment. */
    private static final Role DEFAULT_ROLE = Role.VIEWER;

    private final ConcurrentHashMap<String, Role> roleAssignments = new ConcurrentHashMap<>();

    @Override
    public void checkPermission(String userId, Permission permission) throws AccessDeniedException {
        Role role = getRole(userId);
        if (!role.getPermissions().contains(permission)) {
            logger.warn("Access denied: user='{}', role={}, requiredPermission={}",
                    userId, role, permission);
            throw new AccessDeniedException(userId, permission, role);
        }
        logger.debug("Access granted: user='{}', role={}, permission={}", userId, role, permission);
    }

    @Override
    public boolean hasPermission(String userId, Permission permission) {
        Role role = getRole(userId);
        boolean granted = role.getPermissions().contains(permission);
        logger.debug("Permission check: user='{}', role={}, permission={}, granted={}",
                userId, role, permission, granted);
        return granted;
    }

    @Override
    public void assignRole(String userId, Role role) {
        Role previous = roleAssignments.put(userId, role);
        if (previous == null) {
            logger.info("Role assigned: user='{}', role={}", userId, role);
        } else {
            logger.info("Role changed: user='{}', previousRole={}, newRole={}", userId, previous, role);
        }
    }

    @Override
    public Role getRole(String userId) {
        return roleAssignments.getOrDefault(userId, DEFAULT_ROLE);
    }
}
