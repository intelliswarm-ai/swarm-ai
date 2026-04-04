package ai.intelliswarm.swarmai.enterprise.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Predefined roles in the SwarmAI RBAC model. Each role maps to an immutable
 * set of {@link Permission}s returned by {@link #getPermissions()}.
 *
 * <ul>
 *   <li><b>ADMIN</b> &ndash; full access to every operation.</li>
 *   <li><b>OPERATOR</b> &ndash; execute and view workflows, manage skills and tools.</li>
 *   <li><b>VIEWER</b> &ndash; read-only access to workflow information.</li>
 *   <li><b>AGENT_MANAGER</b> &ndash; execute and view workflows, manage skills.</li>
 * </ul>
 *
 * @see Permission
 * @see RbacEnforcer
 */
public enum Role {

    /** Full access to all operations. */
    ADMIN {
        @Override
        public Set<Permission> getPermissions() {
            return Collections.unmodifiableSet(EnumSet.allOf(Permission.class));
        }
    },

    /** Execute/view workflows, manage skills and tools. */
    OPERATOR {
        @Override
        public Set<Permission> getPermissions() {
            return Collections.unmodifiableSet(EnumSet.of(
                    Permission.WORKFLOW_EXECUTE,
                    Permission.WORKFLOW_VIEW,
                    Permission.SKILL_MANAGE,
                    Permission.TOOL_MANAGE
            ));
        }
    },

    /** Read-only access to workflow information. */
    VIEWER {
        @Override
        public Set<Permission> getPermissions() {
            return Collections.unmodifiableSet(EnumSet.of(
                    Permission.WORKFLOW_VIEW
            ));
        }
    },

    /** Execute/view workflows and manage skills. */
    AGENT_MANAGER {
        @Override
        public Set<Permission> getPermissions() {
            return Collections.unmodifiableSet(EnumSet.of(
                    Permission.WORKFLOW_EXECUTE,
                    Permission.WORKFLOW_VIEW,
                    Permission.SKILL_MANAGE
            ));
        }
    };

    /**
     * Returns the immutable set of permissions granted to this role.
     *
     * @return unmodifiable set of {@link Permission}s
     */
    public abstract Set<Permission> getPermissions();
}
